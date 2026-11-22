//! Hızlı Paylaş (alma sunucusu, gönderme) ve Link/Download eşitlemesi için JNI girişleri.
//! Protokol, sunucu, istemci ve eşitleme mantığı Plan (macOS) ile ORTAK `qshare_core` crate'indedir; burası yalnızca JNI köprüsüdür.
//! Kotlin tarafı: `github.idrisokys.myl.Native` (`serverStart`, `serverPoll`, `serverStop`, `send`, `sync`).
//!
//! * Sunucu olayları Kotlin'e geri çağrıyla DEĞİL, kuyruk + `serverPoll` ile iletilir: Rust iş parçacıklarının
//!   JVM'e bağlanması gerekmez, Kotlin tarafı tek bir arka plan iş parçacığında bekler.
//! * Her giriş noktası panikleri yakalar; hata durumunda null / -1 / 4 döner.

use qshare_core::share::client::{self, SendStatus};
use qshare_core::share::proto;
use qshare_core::share::server::{Config, Event, Server, Sink};
use qshare_core::share::sync::{self, Peer, Scope};
use crate::util::{guard, jstr, jstr_array, to_jstr_array};
use jni::objects::{JClass, JObjectArray, JString};
use jni::sys::{jboolean, jint, jlong, jobjectArray};
use jni::JNIEnv;
use std::collections::{HashMap, VecDeque};
use std::fs::File;
use std::mem::ManuallyDrop;
use std::os::unix::io::FromRawFd;
use std::path::PathBuf;
use std::ptr::null_mut;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Condvar, Mutex, OnceLock};
use std::time::Duration;

/// Olay türleri (Kotlin `Native.EV_*` ile aynı): 1 alınıyor(ad), 2 alındı(ad, klasör), 3 alma yarım kaldı(ad),
/// 4 eşitleme dosyaları değiştirdi, 5 bağlantı başladı, 6 bağlantı bitti.
type Ev = (u8, String, String);

/// Olay kuyruğunun en büyük uzunluğu (Kotlin dinlemiyorsa bellek şişmesin).
const QUEUE_MAX: usize = 1000;
/// `serverPoll` en çok bu kadar bekler; olay yoksa boş dizi döner (Kotlin döngüsü kapanma kontrolü yapabilsin).
const POLL_WAIT: Duration = Duration::from_millis(1500);

struct Shared {
    q: Mutex<VecDeque<Ev>>,
    cv: Condvar,
    closed: AtomicBool,
}

type Entry = (Server, Arc<Shared>);

fn reg() -> &'static Mutex<HashMap<i64, Entry>> {
    static R: OnceLock<Mutex<HashMap<i64, Entry>>> = OnceLock::new();
    R.get_or_init(|| Mutex::new(HashMap::new()))
}

static NEXT: AtomicI64 = AtomicI64::new(1);

fn opt_path(env: &mut JNIEnv, s: &JString) -> Option<PathBuf> {
    jstr(env, s).filter(|v| !v.is_empty()).map(PathBuf::from)
}

/// Bir olay gelene ya da `POLL_WAIT` dolana kadar bekler.
/// `Some([tür, ad, klasör])`, zaman aşımında `Some([])`, sunucu kapandıysa ya da bilinmiyorsa `None`.
fn poll(id: i64) -> Option<Vec<String>> {
    let sh = {
        let g = reg().lock().ok()?;
        g.get(&id)?.1.clone()
    };
    let mut q = sh.q.lock().ok()?;
    loop {
        if let Some((k, a, b)) = q.pop_front() {
            return Some(vec![k.to_string(), a, b]);
        }
        if sh.closed.load(Ordering::Relaxed) {
            return None;
        }
        let (g, t) = sh.cv.wait_timeout(q, POLL_WAIT).ok()?;
        q = g;
        if t.timed_out() && q.is_empty() {
            return Some(Vec::new());
        }
    }
}

/// Arayüz dili: `true` = İngilizce (Rust'tan dönen hata metinleri için).
#[no_mangle]
pub extern "system" fn Java_github_idrisokys_myl_Native_nSetLang<'l>(_env: JNIEnv<'l>, _c: JClass<'l>, english: jboolean) {
    guard((), || proto::set_english(english != 0));
}

/// Alma sunucusunu başlatır. Dönüş: `(tanıtıcı << 16) | port`; hata: -1. `port` 0 ise boş port seçilir.
/// `link_root` / `dl_root` / `trash` boş (null ya da "") olabilir: Link eşitlemesi / Download eşitlemesi kapalı, silme kalıcı.
/// Sunucu eski (dosya başına) protokolü konuşur: Android'in Kotlin sunucusuyla aynı tel biçimi.
#[no_mangle]
pub extern "system" fn Java_github_idrisokys_myl_Native_nServerStart<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    recv_dir: JString<'l>,
    link_root: JString<'l>,
    dl_root: JString<'l>,
    trash: JString<'l>,
    port: jint,
) -> jlong {
    guard(-1i64, || {
        let Some(recv) = opt_path(&mut env, &recv_dir) else { return -1 };
        let link = opt_path(&mut env, &link_root);
        let dl = opt_path(&mut env, &dl_root);
        let trash = opt_path(&mut env, &trash);
        if !(0..=65535).contains(&port) {
            return -1;
        }
        let cfg = Config {
            recv_dir: recv,
            link_root: link,
            legacy_only: true,
            dl_root: dl,
            trash,
            android_names: true,
            link_hidden_dirs: true,
        };
        let sh = Arc::new(Shared { q: Mutex::new(VecDeque::new()), cv: Condvar::new(), closed: AtomicBool::new(false) });
        let sh2 = sh.clone();
        let sink: Sink = Arc::new(move |e: Event| {
            let ev: Ev = match e {
                Event::Receiving(n) => (1, n, String::new()),
                Event::Received { name, folder } => (2, name, folder.to_string_lossy().into_owned()),
                Event::Failed(n) => (3, n, String::new()),
                Event::NotesChanged => (4, String::new(), String::new()),
                Event::Busy(true) => (5, String::new(), String::new()),
                Event::Busy(false) => (6, String::new(), String::new()),
            };
            if let Ok(mut q) = sh2.q.lock() {
                if q.len() >= QUEUE_MAX {
                    q.pop_front();
                }
                q.push_back(ev);
            }
            sh2.cv.notify_one();
        });
        match Server::start(cfg, port as u16, sink) {
            Ok(s) => {
                let p = s.port() as i64;
                let id = NEXT.fetch_add(1, Ordering::Relaxed);
                match reg().lock() {
                    Ok(mut g) => {
                        g.insert(id, (s, sh));
                        (id << 16) | p
                    }
                    Err(_) => -1,
                }
            }
            Err(_) => -1,
        }
    })
}

/// Sunucu olaylarını bekler: [tür, ad, klasör]; zaman aşımında boş dizi; sunucu kapandıysa null.
/// Engelleyicidir (en çok ~1,5 sn): tek bir arka plan iş parçacığında döngüyle çağrılmalıdır.
#[no_mangle]
pub extern "system" fn Java_github_idrisokys_myl_Native_nServerPoll<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, id: jlong) -> jobjectArray {
    guard(null_mut(), || match poll(id) {
        Some(v) => to_jstr_array(&mut env, &v),
        None => null_mut(),
    })
}

/// Sunucuyu durdurur (dinleyici kapanır; `serverPoll` null döner; süren aktarımlar kendi başına biter).
#[no_mangle]
pub extern "system" fn Java_github_idrisokys_myl_Native_nServerStop<'l>(_env: JNIEnv<'l>, _c: JClass<'l>, id: jlong) {
    guard((), || {
        let e = reg().lock().ok().and_then(|mut g| g.remove(&id));
        if let Some((srv, sh)) = e {
            sh.closed.store(true, Ordering::Relaxed);
            sh.cv.notify_all();
            drop(srv);
        }
    })
}

/// Açık bir dosya tanıtıcısından `len` bayt gönderir (`POST /u`). Tanıtıcıyı KAPATMAZ (Kotlin kapatır; bağlantı
/// kurulamazsa aynı tanıtıcıyla yeniden denenebilir). Dönüş: 0 tamam, 1 bağlanılamadı, 2 kaynak kısaldı,
/// 3 karşı cihaz kabul etmedi, 4 G/Ç hatası.
#[no_mangle]
pub extern "system" fn Java_github_idrisokys_myl_Native_nSend<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    addr: JString<'l>,
    name: JString<'l>,
    fd: jint,
    len: jlong,
) -> jint {
    guard(SendStatus::Io as jint, || {
        let Some(a) = jstr(&mut env, &addr) else { return SendStatus::Io as jint };
        let Some(n) = jstr(&mut env, &name) else { return SendStatus::Io as jint };
        if fd < 0 || len < 0 {
            return SendStatus::Io as jint;
        }
        // `File` düşünce tanıtıcıyı kapatırdı; ManuallyDrop ile sahiplik Kotlin'de kalır.
        let mut f = ManuallyDrop::new(unsafe { File::from_raw_fd(fd) });
        client::send_reader(&a, &n, len as u64, &mut *f, &mut |_: u64, _: u64| {}) as jint
    })
}

/// Link ya da Download eşitlemesi (ağ + disk; arka plan iş parçacığında çağrılmalı).
/// `mode`: 0 iki cihazda aynı, 1 bu cihaza göre, 2 karşıdakine göre. `peers`: [ad, "ip:port"]…
/// Çıktı: [hata ("" = yok), cihaz, gönderilen, alınan, silinen, dosya bazlı hatalar…]; hata durumunda null.
#[no_mangle]
pub extern "system" fn Java_github_idrisokys_myl_Native_nSync<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    mode: jint,
    root: JString<'l>,
    download: jboolean,
    trash: JString<'l>,
    peers: JObjectArray<'l>,
) -> jobjectArray {
    guard(null_mut(), || {
        let Some(root) = jstr(&mut env, &root) else { return null_mut() };
        let trash = opt_path(&mut env, &trash);
        let flat = jstr_array(&mut env, &peers);
        let targets: Vec<Peer> =
            flat.chunks(2).filter(|c| c.len() == 2).map(|c| Peer { name: c[0].clone(), addr: c[1].clone() }).collect();
        let dl = download != 0;
        // Android: gizli klasörler yalnızca Download kapsamında atlanır (Kotlin `NoteSync.local` ile aynı)
        let sc = Scope { root: PathBuf::from(root), dl, trash, skip_hidden_dirs: dl };
        let r = sync::run(mode.max(0) as u32, Some(&sc), &targets);
        let mut out: Vec<String> = vec![
            r.error.clone().unwrap_or_default(),
            r.peers.to_string(),
            r.sent.to_string(),
            r.got.to_string(),
            r.deleted.to_string(),
        ];
        out.extend(r.errors.iter().cloned());
        to_jstr_array(&mut env, &out)
    })
}

/// Tek seferlik mDNS cihaz taraması (`qshare_core::mdns`): tüm cihazlar tek soketle paralel çözülür.
/// Dönüş: her cihaz için 5 alan [örnek adı, ip, port, TXT "i" (kimlik), TXT "n" (ad)]; hata: null.
/// `token` ile `nMdnsCancel(token)` taramayı erken bitirir. `local`: çıkış arayüzünün IPv4'ü (boş olabilir).
#[no_mangle]
pub extern "system" fn Java_github_idrisokys_myl_Native_nMdnsBrowse<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    token: jlong,
    service: JString<'l>,
    local: JString<'l>,
    skip: JString<'l>,
    timeout_ms: jint,
) -> jobjectArray {
    guard(null_mut(), || {
        let Some(svc) = jstr(&mut env, &service).filter(|s| !s.is_empty()) else { return null_mut() };
        let local = jstr(&mut env, &local).and_then(|s| s.trim().parse::<std::net::Ipv4Addr>().ok());
        let skip = jstr(&mut env, &skip).filter(|s| !s.is_empty());
        let mut o = qshare_core::mdns::Options::new(&svc, local, Duration::from_millis(timeout_ms.clamp(200, 15_000) as u64));
        o.skip_instance = skip;
        match qshare_core::mdns::browse_cancellable(token, &o) {
            Ok(found) => {
                let mut flat: Vec<String> = Vec::with_capacity(found.len() * 5);
                for f in found {
                    flat.push(f.instance.clone());
                    flat.push(f.addr.to_string());
                    flat.push(f.port.to_string());
                    flat.push(f.txt_get("i").unwrap_or("").to_string());
                    flat.push(f.txt_get("n").unwrap_or("").to_string());
                }
                to_jstr_array(&mut env, &flat)
            }
            Err(_) => null_mut(),
        }
    })
}

/// Çalışan `nMdnsBrowse(token, ..)` taramasını durdurur.
#[no_mangle]
pub extern "system" fn Java_github_idrisokys_myl_Native_nMdnsCancel<'l>(_env: JNIEnv<'l>, _c: JClass<'l>, token: jlong) {
    guard((), || qshare_core::mdns::cancel(token));
}
