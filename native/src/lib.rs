//! MyL yerel kütüphanesi (JNI). Kotlin tarafı: `github.idrisokys.myl.Native`.
//! Her giriş noktası panikleri yakalar; hata durumunda null / -1 döner ve Kotlin yedek koduna düşer.

mod archive;
mod hash;
mod imaging;
mod meta;
mod scan;
mod search;
mod share_jni;
mod thumbcache;
mod tree;
mod util;

use jni::objects::{JByteArray, JClass, JObjectArray, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jobjectArray};
use jni::JNIEnv;
use std::collections::HashSet;
use std::ptr::null_mut;
use util::{guard, jstr, jstr_array, par_map, to_jstr_array};

#[no_mangle]
pub extern "system" fn Java_github_idrisokys_myl_Native_nVersion<'l>(_env: JNIEnv<'l>, _c: JClass<'l>) -> jint {
    1
}

/// Resim/video tarama → yollar, en yeni başta.
#[no_mangle]
pub extern "system" fn Java_github_idrisokys_myl_Native_nScanMedia<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    root: JString<'l>,
    skip: JString<'l>,
    exts: JObjectArray<'l>,
) -> jobjectArray {
    guard(null_mut(), || {
        let r = match jstr(&mut env, &root) {
            Some(s) => s,
            None => return null_mut(),
        };
        let sk = jstr(&mut env, &skip).unwrap_or_default();
        let ex: HashSet<String> = jstr_array(&mut env, &exts).into_iter().collect();
        let paths: Vec<String> = scan::scan_media(&r, &sk, &ex).into_iter().map(|f| f.path).collect();
        to_jstr_array(&mut env, &paths)
    })
}

/// Klasör meta verisi: her öğe için 5 alan [ad, "1"/"0" klasör, boyut, tarih(ms), alt öğe sayısı].
#[no_mangle]
pub extern "system" fn Java_github_idrisokys_myl_Native_nListDirMeta<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    dir: JString<'l>,
) -> jobjectArray {
    guard(null_mut(), || {
        let d = match jstr(&mut env, &dir) {
            Some(s) => s,
            None => return null_mut(),
        };
        match meta::list_dir_meta(&d) {
            Some(v) => {
                let mut flat = Vec::with_capacity(v.len() * 5);
                for m in v {
                    flat.push(m.name);
                    flat.push(if m.is_dir { "1" } else { "0" }.to_string());
                    flat.push(m.size.to_string());
                    flat.push(m.mtime.to_string());
                    flat.push(m.children.to_string());
                }
                to_jstr_array(&mut env, &flat)
            }
            None => null_mut(),
        }
    })
}

/// Link klasörü ağacı: her öğe için 4 alan [göreli yol, "D"/"F", boyut, tarih(ms)].
#[no_mangle]
pub extern "system" fn Java_github_idrisokys_myl_Native_nWalkNotes<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    root: JString<'l>,
    dl: jboolean,
    max_depth: jint,
) -> jobjectArray {
    guard(null_mut(), || {
        let r = match jstr(&mut env, &root) {
            Some(s) => s,
            None => return null_mut(),
        };
        let nodes = meta::walk_notes(&r, dl != 0, max_depth);
        let mut flat = Vec::with_capacity(nodes.len() * 4);
        for n in nodes {
            flat.push(n.rel);
            flat.push(if n.dir { "D" } else { "F" }.to_string());
            flat.push(n.size.to_string());
            flat.push(n.mtime.to_string());
        }
        to_jstr_array(&mut env, &flat)
    })
}

/// Not araması için klasör yürüyüşü (Kotlin `Store.search` içindeki dolaşım): her öğe için 4 alan
/// [göreli yol ("/" ile), "D"/"F", sabitli "1"/"0", klasör adı sorguyla eşleşti "1"/"0"]. Klasör içeriğinden önce gelir.
#[no_mangle]
pub extern "system" fn Java_github_idrisokys_myl_Native_nWalkForSearch<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    root: JString<'l>,
    query: JString<'l>,
) -> jobjectArray {
    guard(null_mut(), || {
        let (Some(r), Some(q)) = (jstr(&mut env, &root), jstr(&mut env, &query)) else { return null_mut() };
        let nodes = tree::walk_for_search(std::path::Path::new(&r), &q);
        let mut flat = Vec::with_capacity(nodes.len() * 4);
        for n in nodes {
            flat.push(n.rel);
            flat.push(if n.is_dir { "D" } else { "F" }.to_string());
            flat.push(if n.pinned { "1" } else { "0" }.to_string());
            flat.push(if n.name_hit { "1" } else { "0" }.to_string());
        }
        to_jstr_array(&mut env, &flat)
    })
}

/// Not arama: her isabet için 4 alan [dizin, titleMatch "1"/"0", isNote "1"/"0", ayrıntı].
#[no_mangle]
pub extern "system" fn Java_github_idrisokys_myl_Native_nSearchNotes<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    paths: JObjectArray<'l>,
    query: JString<'l>,
) -> jobjectArray {
    guard(null_mut(), || {
        let q = match jstr(&mut env, &query) {
            Some(s) => s,
            None => return null_mut(),
        };
        let ps = jstr_array(&mut env, &paths);
        let hits = search::match_notes(&ps, &q);
        let mut flat = Vec::with_capacity(hits.len() * 4);
        for h in hits {
            flat.push(h.idx.to_string());
            flat.push(if h.title_match { "1" } else { "0" }.to_string());
            flat.push(if h.is_note { "1" } else { "0" }.to_string());
            flat.push(h.detail);
        }
        to_jstr_array(&mut env, &flat)
    })
}

/// ZIP aç → giriş sayısı, hata: -1.
#[no_mangle]
pub extern "system" fn Java_github_idrisokys_myl_Native_nUnzip<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    zip: JString<'l>,
    dest: JString<'l>,
) -> jint {
    guard(-1, || {
        let (z, d) = match (jstr(&mut env, &zip), jstr(&mut env, &dest)) {
            (Some(z), Some(d)) => (z, d),
            _ => return -1,
        };
        match archive::unzip(&z, &d) {
            Ok(n) => n.min(i32::MAX as usize) as jint,
            Err(_) => -1,
        }
    })
}

/// ZIP oluştur → eklenen dosya sayısı, hata: -1.
#[no_mangle]
pub extern "system" fn Java_github_idrisokys_myl_Native_nZipPaths<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    sources: JObjectArray<'l>,
    dest: JString<'l>,
) -> jint {
    guard(-1, || {
        let d = match jstr(&mut env, &dest) {
            Some(d) => d,
            None => return -1,
        };
        let src = jstr_array(&mut env, &sources);
        match archive::zip_paths(&src, &d) {
            Ok(n) => n.min(i32::MAX as usize) as jint,
            Err(_) => -1,
        }
    })
}

/// Tek dosya özeti (hex). Hata: null.
#[no_mangle]
pub extern "system" fn Java_github_idrisokys_myl_Native_nHashFile<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    path: JString<'l>,
    algo: JString<'l>,
) -> jobjectArray {
    // Tek elemanlı dizi olarak döner (hata → null); böylece dönüş türü diğerleriyle aynı kalır.
    guard(null_mut(), || {
        let (p, a) = match (jstr(&mut env, &path), jstr(&mut env, &algo)) {
            (Some(p), Some(a)) => (p, a),
            _ => return null_mut(),
        };
        let algo = match hash::parse_algo(&a) {
            Some(x) => x,
            None => return null_mut(),
        };
        match hash::hash_file(&p, algo) {
            Ok(h) => to_jstr_array(&mut env, &[h]),
            Err(_) => null_mut(),
        }
    })
}

/// Çoklu dosya özeti (paralel); başarısızlar için "".
#[no_mangle]
pub extern "system" fn Java_github_idrisokys_myl_Native_nHashFiles<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    paths: JObjectArray<'l>,
    algo: JString<'l>,
) -> jobjectArray {
    guard(null_mut(), || {
        let a = match jstr(&mut env, &algo) {
            Some(a) => a,
            None => return null_mut(),
        };
        let algo = match hash::parse_algo(&a) {
            Some(x) => x,
            None => return null_mut(),
        };
        let ps = jstr_array(&mut env, &paths);
        let out = hash::hash_files(&ps, algo);
        to_jstr_array(&mut env, &out)
    })
}

/// Küçük resim / tam ekran çözme: [w:i32 LE][h:i32 LE] + premultiplied RGBA. Desteklenmiyorsa null.
#[no_mangle]
pub extern "system" fn Java_github_idrisokys_myl_Native_nDecodeImage<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    path: JString<'l>,
    max_dim: jint,
    fit: jboolean,
) -> jbyteArray {
    let r: Option<Vec<u8>> = guard(None, || {
        let p = jstr(&mut env, &path)?;
        imaging::decode_for_android(&p, max_dim.max(1) as u32, fit != 0)
    });
    match r {
        Some(b) => env.byte_array_from_slice(&b).map(|a| a.into_raw()).unwrap_or(null_mut()),
        None => null_mut(),
    }
}

/// Galeri küçük resim önbelleği (disk, JPEG): varsa [w:i32 LE][h:i32 LE] + RGBA, yoksa/bayatsa null.
/// `tag`: küçük resim türü (Kotlin sabiti); anahtar yol + boyut + değişme zamanı + etiketten üretilir.
#[no_mangle]
pub extern "system" fn Java_github_idrisokys_myl_Native_nThumbGet<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    path: JString<'l>,
    cache_dir: JString<'l>,
    tag: jint,
) -> jbyteArray {
    let r: Option<Vec<u8>> = guard(None, || {
        let p = jstr(&mut env, &path)?;
        let d = jstr(&mut env, &cache_dir)?;
        thumbcache::get(&p, &d, tag)
    });
    match r {
        Some(b) => env.byte_array_from_slice(&b).map(|a| a.into_raw()).unwrap_or(null_mut()),
        None => null_mut(),
    }
}

/// Küçük resmi (premultiplied RGBA, w×h) önbelleğe yazar. Saydam / tutarsız girdi yazılmaz: false.
#[no_mangle]
pub extern "system" fn Java_github_idrisokys_myl_Native_nThumbPut<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    path: JString<'l>,
    cache_dir: JString<'l>,
    tag: jint,
    w: jint,
    h: jint,
    rgba: JByteArray<'l>,
) -> jboolean {
    guard(0, || {
        if w <= 0 || h <= 0 {
            return 0;
        }
        let (Some(p), Some(d)) = (jstr(&mut env, &path), jstr(&mut env, &cache_dir)) else { return 0 };
        let Ok(px) = env.convert_byte_array(&rgba) else { return 0 };
        thumbcache::put(&p, &d, tag, w as u32, h as u32, &px) as jboolean
    })
}

/// Önbellek boyutu `max_bytes`'ı aşarsa en eski girdileri siler; kalan toplam bayt.
#[no_mangle]
pub extern "system" fn Java_github_idrisokys_myl_Native_nThumbTrim<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    cache_dir: JString<'l>,
    max_bytes: jlong,
) -> jlong {
    guard(0, || match jstr(&mut env, &cache_dir) {
        Some(d) => thumbcache::trim(&d, max_bytes.max(0) as u64) as jlong,
        None => 0,
    })
}

/// Toplu resim dönüştürme (paralel, en çok 2 iş parçacığı). Her giriş için çıktı yolu, başarısızsa "".
#[no_mangle]
pub extern "system" fn Java_github_idrisokys_myl_Native_nConvertImages<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    srcs: JObjectArray<'l>,
    dest_dir: JString<'l>,
    format: JString<'l>,
    quality: jint,
    max_dim: jint,
) -> jobjectArray {
    guard(null_mut(), || {
        let (d, f) = match (jstr(&mut env, &dest_dir), jstr(&mut env, &format)) {
            (Some(d), Some(f)) => (d, f),
            _ => return null_mut(),
        };
        let ss = jstr_array(&mut env, &srcs);
        let q = quality.clamp(1, 100) as u8;
        let md = max_dim.max(0) as u32;
        let out: Vec<String> = par_map(&ss, 2, |s| {
            imaging::convert_one(s, &d, &f, q, md).unwrap_or_default()
        });
        to_jstr_array(&mut env, &out)
    })
}
