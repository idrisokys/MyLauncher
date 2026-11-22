//! Depolama taraması: resim/video dosyalarını paralel bulur, en yeni başta sıralar.
//! Kotlin'deki `GalleryActivity.scan()` ile aynı kurallar:
//!  - kök altındaki "Android" klasörü atlanır,
//!  - adı "." ile başlayan klasör ve dosyalar atlanır,
//!  - uzantı (küçük harfe çevrilmiş) verilen kümede olmalı.

use crate::util::cpu_threads;
use std::collections::HashSet;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Mutex;
use std::time::UNIX_EPOCH;

pub struct Found {
    pub path: String,
    pub mtime: i64,
}

pub fn mtime_ms(m: &fs::Metadata) -> i64 {
    m.modified()
        .ok()
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

pub fn scan_media(root: &str, skip: &str, exts: &HashSet<String>) -> Vec<Found> {
    let skip_p = PathBuf::from(skip);
    let queue: Mutex<Vec<PathBuf>> = Mutex::new(vec![PathBuf::from(root)]);
    let pending = AtomicUsize::new(1);
    let out: Mutex<Vec<Found>> = Mutex::new(Vec::new());
    let threads = cpu_threads(2, 6);

    std::thread::scope(|s| {
        for _ in 0..threads {
            s.spawn(|| {
                let mut local: Vec<Found> = Vec::new();
                loop {
                    let next = queue.lock().unwrap().pop();
                    match next {
                        Some(dir) => {
                            visit(&dir, &skip_p, exts, &queue, &pending, &mut local);
                            pending.fetch_sub(1, Ordering::AcqRel);
                        }
                        None => {
                            if pending.load(Ordering::Acquire) == 0 {
                                break;
                            }
                            std::thread::sleep(std::time::Duration::from_micros(300));
                        }
                    }
                }
                out.lock().unwrap().extend(local);
            });
        }
    });

    let mut v = out.into_inner().unwrap();
    v.sort_by(|a, b| b.mtime.cmp(&a.mtime).then_with(|| a.path.cmp(&b.path)));
    v
}

fn visit(
    dir: &Path,
    skip: &Path,
    exts: &HashSet<String>,
    queue: &Mutex<Vec<PathBuf>>,
    pending: &AtomicUsize,
    local: &mut Vec<Found>,
) {
    let rd = match fs::read_dir(dir) {
        Ok(r) => r,
        Err(_) => return,
    };
    for ent in rd.flatten() {
        let name_os = ent.file_name();
        let name = match name_os.to_str() {
            Some(n) => n,
            None => continue,
        };
        if name.starts_with('.') {
            continue;
        }
        let ft = match ent.file_type() {
            Ok(t) => t,
            Err(_) => continue,
        };
        let path = ent.path();

        // Sembolik bağ: hedefi dosyaysa dosya say, klasörse (döngü riski) atla.
        let (is_dir, is_file, symlink) = if ft.is_symlink() {
            match fs::metadata(&path) {
                Ok(m) => (false, m.is_file(), true),
                Err(_) => continue,
            }
        } else {
            (ft.is_dir(), ft.is_file(), false)
        };

        if is_dir {
            if path == skip {
                continue;
            }
            pending.fetch_add(1, Ordering::AcqRel);
            queue.lock().unwrap().push(path);
        } else if is_file {
            let ext = match name.rsplit_once('.') {
                Some((_, e)) => e.to_ascii_lowercase(),
                None => continue,
            };
            if !exts.contains(&ext) {
                continue;
            }
            let md = if symlink { fs::metadata(&path) } else { ent.metadata() };
            let mt = md.map(|m| mtime_ms(&m)).unwrap_or(0);
            if let Some(p) = path.to_str() {
                local.push(Found { path: p.to_string(), mtime: mt });
            }
        }
    }
}
