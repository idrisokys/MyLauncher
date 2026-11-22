//! Galeri küçük resimleri için disk önbelleği.
//!
//! Neden var: `GalleryActivity` yalnızca bellekte `LruCache` tutuyordu; uygulama her açıldığında (ve bellek
//! baskısında) küçük resimler yeniden çözülüyor, video kareleri yeniden çıkarılıyordu. Burada her küçük resim
//! JPEG olarak saklanır; sonraki açılışta çözmek özgün dosyayı çözmekten çok daha hızlıdır.
//!
//! * Anahtar: yol + dosya boyutu + değişme zamanı (ms) + etiket (küçük resim türü) → dosya değişince girdi kendiliğinden geçersiz olur.
//! * Biçim: JPEG (kalite 85). Saydam (alfa < 255) görüntüler JPEG'e yazılmaz (alfa kaybolurdu); onlar önbelleksiz çözülür.
//! * Yazma atomiktir (önce `.part`, sonra `rename`); okunamayan/bozuk girdi sessizce "yok" sayılır.
//! * `trim`: toplam boyut sınırı aşılınca en eski girdiler silinir.
//!
//! Bayt biçimi `imaging::decode_for_android` ile aynıdır: [w:i32 LE][h:i32 LE] + RGBA (opak olduğundan premultiplied ile aynı).

use image::codecs::jpeg::JpegEncoder;
use image::{ExtendedColorType, ImageEncoder, ImageReader};
use std::fs::{self, File};
use std::io::{BufWriter, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::UNIX_EPOCH;

const QUALITY: u8 = 85;
/// Tek bir küçük resim için üst sınır (yanlış/kötü girdi bellek şişirmesin).
const MAX_PIXELS: usize = 4096 * 4096;

fn fnv1a(data: &[u8]) -> u64 {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for &b in data {
        h ^= b as u64;
        h = h.wrapping_mul(0x0000_0100_0000_01b3);
    }
    h
}

/// Önbellek anahtarı (16 onaltılık hane); kaynak dosya okunamıyorsa None.
fn key(path: &str, tag: i32) -> Option<String> {
    let m = fs::metadata(path).ok()?;
    let ms = m.modified().ok()?.duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0);
    let mut buf: Vec<u8> = Vec::with_capacity(path.len() + 24);
    buf.extend_from_slice(path.as_bytes());
    buf.push(0);
    buf.extend_from_slice(&m.len().to_le_bytes());
    buf.extend_from_slice(&ms.to_le_bytes());
    buf.extend_from_slice(&tag.to_le_bytes());
    Some(format!("{:016x}", fnv1a(&buf)))
}

/// `<önbellek>/<ilk 2 hane>/<anahtar>.jpg`: tek klasörde on binlerce dosya birikmesin.
fn entry_path(dir: &Path, k: &str) -> PathBuf {
    dir.join(&k[..2]).join(format!("{}.jpg", k))
}

/// Önbellekteki küçük resim: [w:i32 LE][h:i32 LE] + RGBA; yoksa / bayatsa / bozuksa None.
pub fn get(path: &str, cache_dir: &str, tag: i32) -> Option<Vec<u8>> {
    let k = key(path, tag)?;
    let p = entry_path(Path::new(cache_dir), &k);
    let img = ImageReader::open(&p).ok()?.with_guessed_format().ok()?.decode().ok()?;
    let rgba = img.into_rgba8();
    let (w, h) = rgba.dimensions();
    if w == 0 || h == 0 {
        return None;
    }
    let mut out = Vec::with_capacity(8 + rgba.as_raw().len());
    out.extend_from_slice(&(w as i32).to_le_bytes());
    out.extend_from_slice(&(h as i32).to_le_bytes());
    out.extend_from_slice(rgba.as_raw());
    Some(out)
}

/// Küçük resmi (RGBA, w×h) önbelleğe yazar. Saydam görüntüyü ya da tutarsız boyutu yazmaz: false.
pub fn put(path: &str, cache_dir: &str, tag: i32, w: u32, h: u32, rgba: &[u8]) -> bool {
    let n = (w as usize).saturating_mul(h as usize);
    if w == 0 || h == 0 || n > MAX_PIXELS || rgba.len() != n * 4 {
        return false;
    }
    if rgba.chunks_exact(4).any(|p| p[3] != 255) {
        return false;
    }
    let Some(k) = key(path, tag) else { return false };
    let dst = entry_path(Path::new(cache_dir), &k);
    let Some(parent) = dst.parent() else { return false };
    if fs::create_dir_all(parent).is_err() {
        return false;
    }
    let mut rgb: Vec<u8> = Vec::with_capacity(n * 3);
    for p in rgba.chunks_exact(4) {
        rgb.extend_from_slice(&p[..3]);
    }
    static SEQ: AtomicU64 = AtomicU64::new(0);
    let tmp = parent.join(format!("{}.{}.part", k, SEQ.fetch_add(1, Ordering::Relaxed)));
    let written = (|| -> Result<(), String> {
        let mut w_out = BufWriter::new(File::create(&tmp).map_err(|e| e.to_string())?);
        JpegEncoder::new_with_quality(&mut w_out, QUALITY)
            .write_image(&rgb, w, h, ExtendedColorType::Rgb8)
            .map_err(|e| e.to_string())?;
        w_out.flush().map_err(|e| e.to_string())
    })();
    if written.is_err() || fs::rename(&tmp, &dst).is_err() {
        let _ = fs::remove_file(&tmp);
        return false;
    }
    true
}

/// Toplam boyut `max_bytes`'ı aşarsa en eski girdileri (değişme zamanına göre) %80'e inene kadar siler.
/// Dönüş: kalan toplam bayt.
pub fn trim(cache_dir: &str, max_bytes: u64) -> u64 {
    let mut files: Vec<(PathBuf, u64, std::time::SystemTime)> = Vec::new();
    let Ok(top) = fs::read_dir(cache_dir) else { return 0 };
    for sub in top.flatten() {
        let sp = sub.path();
        if !sp.is_dir() {
            continue;
        }
        let Ok(rd) = fs::read_dir(&sp) else { continue };
        for f in rd.flatten() {
            if let Ok(m) = f.metadata() {
                if m.is_file() {
                    files.push((f.path(), m.len(), m.modified().unwrap_or(UNIX_EPOCH)));
                }
            }
        }
    }
    let mut total: u64 = files.iter().map(|f| f.1).sum();
    if total <= max_bytes {
        return total;
    }
    files.sort_by_key(|f| f.2);
    let target = max_bytes / 10 * 8;
    for (p, len, _) in files {
        if total <= target {
            break;
        }
        if fs::remove_file(&p).is_ok() {
            total = total.saturating_sub(len);
        }
    }
    total
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("myl_thumb_{}_{}", name, std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d
    }

    fn solid(w: u32, h: u32, r: u8, g: u8, b: u8, a: u8) -> Vec<u8> {
        let mut v = Vec::new();
        for _ in 0..(w * h) {
            v.extend_from_slice(&[r, g, b, a]);
        }
        v
    }

    #[test]
    fn roundtrip_returns_android_layout_close_to_original() {
        let d = tmp("rt");
        let src = d.join("a.jpg");
        fs::write(&src, b"kaynak").unwrap();
        let (sp, cd) = (src.to_str().unwrap(), d.join("c"));
        let cd = cd.to_str().unwrap();
        assert!(get(sp, cd, 360).is_none());
        assert!(put(sp, cd, 360, 16, 24, &solid(16, 24, 200, 100, 50, 255)));
        let b = get(sp, cd, 360).expect("önbellekte olmalı");
        assert_eq!(i32::from_le_bytes([b[0], b[1], b[2], b[3]]), 16);
        assert_eq!(i32::from_le_bytes([b[4], b[5], b[6], b[7]]), 24);
        assert_eq!(b.len(), 8 + 16 * 24 * 4);
        for (got, want) in [(b[8], 200i32), (b[9], 100), (b[10], 50)] {
            assert!((got as i32 - want).abs() <= 6, "{} vs {}", got, want);
        }
        assert_eq!(b[11], 255);
        // etiket ayrı girdi: başka tür küçük resim karışmaz
        assert!(get(sp, cd, 361).is_none());
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn changed_source_invalidates_entry() {
        let d = tmp("stale");
        let src = d.join("a.jpg");
        fs::write(&src, b"bir").unwrap();
        let sp = src.to_str().unwrap();
        let cd = d.join("c");
        let cd = cd.to_str().unwrap();
        assert!(put(sp, cd, 1, 8, 8, &solid(8, 8, 10, 20, 30, 255)));
        assert!(get(sp, cd, 1).is_some());
        fs::write(&src, b"daha uzun yeni icerik").unwrap(); // boyut değişti → anahtar değişti
        assert!(get(sp, cd, 1).is_none());
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn transparent_or_inconsistent_input_is_not_stored() {
        let d = tmp("rej");
        let src = d.join("a.png");
        fs::write(&src, b"x").unwrap();
        let sp = src.to_str().unwrap();
        let cd = d.join("c");
        let cd = cd.to_str().unwrap();
        assert!(!put(sp, cd, 1, 4, 4, &solid(4, 4, 1, 2, 3, 128)));
        assert!(!put(sp, cd, 1, 4, 4, &solid(4, 4, 1, 2, 3, 255)[..10]));
        assert!(!put(sp, cd, 1, 0, 4, &[]));
        assert!(!put("/yok/boyle/dosya", cd, 1, 4, 4, &solid(4, 4, 1, 2, 3, 255)));
        assert!(get(sp, cd, 1).is_none());
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn corrupt_entry_is_treated_as_missing() {
        let d = tmp("bad");
        let src = d.join("a.jpg");
        fs::write(&src, b"x").unwrap();
        let sp = src.to_str().unwrap();
        let cd = d.join("c");
        let cds = cd.to_str().unwrap();
        let k = key(sp, 7).unwrap();
        let p = entry_path(&cd, &k);
        fs::create_dir_all(p.parent().unwrap()).unwrap();
        fs::write(&p, b"jpeg degil").unwrap();
        assert!(get(sp, cds, 7).is_none());
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn trim_removes_oldest_until_under_limit() {
        let d = tmp("trim");
        let cd = d.join("c");
        fs::create_dir_all(cd.join("aa")).unwrap();
        for i in 0..10 {
            fs::write(cd.join("aa").join(format!("{}.jpg", i)), vec![0u8; 100]).unwrap();
            std::thread::sleep(std::time::Duration::from_millis(15));
        }
        let cds = cd.to_str().unwrap();
        assert_eq!(trim(cds, 5000), 1000); // sınırın altında: dokunulmaz
        let left = trim(cds, 500); // hedef 400
        assert!(left <= 400, "{}", left);
        assert!(left >= 300);
        // en yeni dosya hayatta kalır
        assert!(cd.join("aa").join("9.jpg").exists());
        assert!(!cd.join("aa").join("0.jpg").exists());
        let _ = fs::remove_dir_all(&d);
    }
}
