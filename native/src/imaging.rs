//! Görsel işleme: küçük resim üretimi (Android Bitmap'e hazır RGBA) ve toplu dönüştürme.

use image::codecs::{jpeg::JpegEncoder, png::PngEncoder, webp::WebPEncoder};
use image::{DynamicImage, ExtendedColorType, ImageEncoder, ImageFormat, ImageReader};
use std::fs::{self, File};
use std::io::{BufReader, BufWriter, Write};
use std::path::{Path, PathBuf};

struct Pix {
    w: u32,
    h: u32,
    rgba: Vec<u8>,
}

fn es<E: ToString>(e: E) -> String {
    e.to_string()
}

/// EXIF yönü (1..8); okunamazsa 1.
fn orientation(path: &str) -> u32 {
    let f = match File::open(path) {
        Ok(f) => f,
        Err(_) => return 1,
    };
    let mut br = BufReader::new(f);
    match exif::Reader::new().read_from_container(&mut br) {
        Ok(ex) => ex
            .get_field(exif::Tag::Orientation, exif::In::PRIMARY)
            .and_then(|f| f.value.get_uint(0))
            .unwrap_or(1),
        Err(_) => 1,
    }
}

/// Kotlin `decodeImg` ile aynı örnekleme kuralı.
/// fit=true: en büyük kenar max_dim'i geçmesin. fit=false: en küçük kenar >= max_dim kalsın.
fn sample_factor(w: u32, h: u32, max_dim: u32, fit: bool) -> u32 {
    let max_dim = max_dim.max(1);
    let mut s = 1u32;
    if fit {
        while w.max(h) / s > max_dim {
            s *= 2;
        }
    } else {
        while w.min(h) / (s * 2) >= max_dim {
            s *= 2;
        }
    }
    s
}

fn decode_jpeg(path: &str, tw: u32, th: u32) -> Option<Pix> {
    let f = File::open(path).ok()?;
    let mut d = jpeg_decoder::Decoder::new(BufReader::with_capacity(128 * 1024, f));
    d.read_info().ok()?;
    let orig = d.info()?;
    if tw < orig.width as u32 || th < orig.height as u32 {
        // DCT ölçekleme (1/2, 1/4, 1/8): tam çözmekten çok daha hızlı.
        d.scale(tw.min(65535) as u16, th.min(65535) as u16).ok()?;
    }
    let pixels = d.decode().ok()?;
    let info = d.info()?;
    let (w, h) = (info.width as u32, info.height as u32);
    let n = w as usize * h as usize;
    let mut rgba = Vec::with_capacity(n * 4);
    match info.pixel_format {
        jpeg_decoder::PixelFormat::RGB24 => {
            if pixels.len() != n * 3 {
                return None;
            }
            for p in pixels.chunks_exact(3) {
                rgba.extend_from_slice(&[p[0], p[1], p[2], 255]);
            }
        }
        jpeg_decoder::PixelFormat::L8 => {
            if pixels.len() != n {
                return None;
            }
            for &g in &pixels {
                rgba.extend_from_slice(&[g, g, g, 255]);
            }
        }
        _ => return None, // CMYK / 16 bit: Kotlin (BitmapFactory) yolu devralır
    }
    Some(Pix { w, h, rgba })
}

fn decode_generic(path: &str) -> Option<Pix> {
    let img = ImageReader::open(path).ok()?.with_guessed_format().ok()?.decode().ok()?;
    let buf = img.into_rgba8();
    let (w, h) = buf.dimensions();
    Some(Pix { w, h, rgba: buf.into_raw() })
}

fn premultiply(p: &mut [u8]) {
    for px in p.chunks_exact_mut(4) {
        let a = px[3] as u32;
        if a != 255 {
            px[0] = ((px[0] as u32 * a + 127) / 255) as u8;
            px[1] = ((px[1] as u32 * a + 127) / 255) as u8;
            px[2] = ((px[2] as u32 * a + 127) / 255) as u8;
        }
    }
}

/// Alan ortalamalı (box) küçültme; kaliteli ve basit.
fn box_resize(src: &[u8], sw: u32, sh: u32, tw: u32, th: u32) -> Vec<u8> {
    let (sw, sh, tw, th) = (sw as usize, sh as usize, tw as usize, th as usize);
    let mut out = vec![0u8; tw * th * 4];
    let xr: Vec<(usize, usize)> = (0..tw)
        .map(|dx| {
            let x0 = dx * sw / tw;
            let x1 = (((dx + 1) * sw + tw - 1) / tw).max(x0 + 1).min(sw);
            (x0, x1)
        })
        .collect();
    for dy in 0..th {
        let y0 = dy * sh / th;
        let y1 = (((dy + 1) * sh + th - 1) / th).max(y0 + 1).min(sh);
        for dx in 0..tw {
            let (x0, x1) = xr[dx];
            let mut acc = [0u32; 4];
            for y in y0..y1 {
                for px in src[(y * sw + x0) * 4..(y * sw + x1) * 4].chunks_exact(4) {
                    acc[0] += px[0] as u32;
                    acc[1] += px[1] as u32;
                    acc[2] += px[2] as u32;
                    acc[3] += px[3] as u32;
                }
            }
            let cnt = ((y1 - y0) * (x1 - x0)) as u32;
            let o = (dy * tw + dx) * 4;
            for c in 0..4 {
                out[o + c] = ((acc[c] + cnt / 2) / cnt) as u8;
            }
        }
    }
    out
}

fn rotate_cw90(p: Pix) -> Pix {
    let (w, h) = (p.w as usize, p.h as usize);
    let (dw, dh) = (h, w);
    let mut out = vec![0u8; p.rgba.len()];
    for dy in 0..dh {
        for dx in 0..dw {
            let (sx, sy) = (dy, h - 1 - dx);
            let s = (sy * w + sx) * 4;
            let d = (dy * dw + dx) * 4;
            out[d..d + 4].copy_from_slice(&p.rgba[s..s + 4]);
        }
    }
    Pix { w: dw as u32, h: dh as u32, rgba: out }
}

fn rotate_ccw90(p: Pix) -> Pix {
    let (w, h) = (p.w as usize, p.h as usize);
    let (dw, dh) = (h, w);
    let mut out = vec![0u8; p.rgba.len()];
    for dy in 0..dh {
        for dx in 0..dw {
            let (sx, sy) = (w - 1 - dy, dx);
            let s = (sy * w + sx) * 4;
            let d = (dy * dw + dx) * 4;
            out[d..d + 4].copy_from_slice(&p.rgba[s..s + 4]);
        }
    }
    Pix { w: dw as u32, h: dh as u32, rgba: out }
}

fn rotate_180(mut p: Pix) -> Pix {
    let n = p.rgba.len() / 4;
    for i in 0..n / 2 {
        let j = n - 1 - i;
        for c in 0..4 {
            p.rgba.swap(i * 4 + c, j * 4 + c);
        }
    }
    p
}

/// Android Bitmap için: [w:i32 LE][h:i32 LE] + premultiplied RGBA. Desteklenmeyen biçimde None (Kotlin yedeği çalışır).
pub fn decode_for_android(path: &str, max_dim: u32, fit: bool) -> Option<Vec<u8>> {
    let reader = ImageReader::open(path).ok()?.with_guessed_format().ok()?;
    let fmt = reader.format()?;
    let (w, h) = reader.into_dimensions().ok()?;
    if w == 0 || h == 0 {
        return None;
    }
    let s = sample_factor(w, h, max_dim, fit);
    let (tw, th) = ((w + s - 1) / s, (h + s - 1) / s);

    let mut pix = if fmt == ImageFormat::Jpeg { decode_jpeg(path, tw, th)? } else { decode_generic(path)? };
    premultiply(&mut pix.rgba);
    if s > 1 && (pix.w > tw || pix.h > th) {
        let (nw, nh) = (tw.min(pix.w), th.min(pix.h));
        pix = Pix { rgba: box_resize(&pix.rgba, pix.w, pix.h, nw, nh), w: nw, h: nh };
    }
    // Kotlin yalnızca 90/180/270 döndürmelerini uyguluyordu; aynısı.
    pix = match orientation(path) {
        6 => rotate_cw90(pix),
        3 => rotate_180(pix),
        8 => rotate_ccw90(pix),
        _ => pix,
    };

    let mut out = Vec::with_capacity(8 + pix.rgba.len());
    out.extend_from_slice(&(pix.w as i32).to_le_bytes());
    out.extend_from_slice(&(pix.h as i32).to_le_bytes());
    out.extend_from_slice(&pix.rgba);
    Some(out)
}

fn unique_path(dir: &Path, stem: &str, ext: &str) -> PathBuf {
    let mut p = dir.join(format!("{}.{}", stem, ext));
    let mut n = 1;
    while p.exists() {
        p = dir.join(format!("{}_{}.{}", stem, n, ext));
        n += 1;
    }
    p
}

/// Tek resmi dönüştürür (jpg / png / webp[kayıpsız]); EXIF yönünü uygular, max_dim > 0 ise sığacak şekilde küçültür.
pub fn convert_one(src: &str, dest_dir: &str, fmt: &str, quality: u8, max_dim: u32) -> Result<String, String> {
    let ext = match fmt.to_ascii_lowercase().as_str() {
        "jpg" | "jpeg" => "jpg",
        "png" => "png",
        "webp" => "webp",
        _ => return Err("unsupported format".to_string()),
    };
    let mut img: DynamicImage = ImageReader::open(src)
        .map_err(es)?
        .with_guessed_format()
        .map_err(es)?
        .decode()
        .map_err(es)?;
    img = match orientation(src) {
        6 => img.rotate90(),
        3 => img.rotate180(),
        8 => img.rotate270(),
        _ => img,
    };
    if max_dim > 0 && (img.width() > max_dim || img.height() > max_dim) {
        img = img.resize(max_dim, max_dim, image::imageops::FilterType::Lanczos3);
    }

    let stem = Path::new(src).file_stem().and_then(|s| s.to_str()).unwrap_or("image");
    fs::create_dir_all(dest_dir).map_err(es)?;
    let out = unique_path(Path::new(dest_dir), stem, ext);
    let tmp = PathBuf::from(format!("{}.part", out.display()));
    {
        let mut w = BufWriter::new(File::create(&tmp).map_err(es)?);
        match ext {
            "jpg" => {
                let rgb = img.to_rgb8();
                JpegEncoder::new_with_quality(&mut w, quality.clamp(1, 100))
                    .write_image(rgb.as_raw(), rgb.width(), rgb.height(), ExtendedColorType::Rgb8)
                    .map_err(es)?;
            }
            "png" => {
                let rgba = img.to_rgba8();
                PngEncoder::new(&mut w)
                    .write_image(rgba.as_raw(), rgba.width(), rgba.height(), ExtendedColorType::Rgba8)
                    .map_err(es)?;
            }
            _ => {
                let rgba = img.to_rgba8();
                WebPEncoder::new_lossless(&mut w)
                    .write_image(rgba.as_raw(), rgba.width(), rgba.height(), ExtendedColorType::Rgba8)
                    .map_err(es)?;
            }
        }
        w.flush().map_err(es)?;
    }
    fs::rename(&tmp, &out).map_err(|e| {
        let _ = fs::remove_file(&tmp);
        es(e)
    })?;
    Ok(out.to_string_lossy().into_owned())
}
