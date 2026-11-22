//! ZIP açma (paralel, zip-slip korumalı) ve ZIP oluşturma.

use crate::util::cpu_threads;
use std::fs::{self, File};
use std::io::{self, BufReader, BufWriter, Write};
use std::path::{Path, PathBuf};
use std::sync::Mutex;

fn es<E: ToString>(e: E) -> String {
    e.to_string()
}

/// Arşivi `dest` içine açar; açılan giriş sayısını döndürür. Güvensiz yol (zip-slip) hata sayılır.
pub fn unzip(zip_path: &str, dest: &str) -> Result<usize, String> {
    let dest = PathBuf::from(dest);
    fs::create_dir_all(&dest).map_err(es)?;
    let count = {
        let f = File::open(zip_path).map_err(es)?;
        let a = zip::ZipArchive::new(BufReader::new(f)).map_err(es)?;
        a.len()
    };
    let threads = if count < 16 { 1 } else { cpu_threads(1, 4) };
    let err: Mutex<Option<String>> = Mutex::new(None);
    std::thread::scope(|s| {
        for part in 0..threads {
            let dest = &dest;
            let err = &err;
            s.spawn(move || {
                if let Err(e) = extract_part(zip_path, dest, part, threads) {
                    let mut g = err.lock().unwrap();
                    if g.is_none() {
                        *g = Some(e);
                    }
                }
            });
        }
    });
    match err.into_inner().unwrap() {
        Some(e) => Err(e),
        None => Ok(count),
    }
}

fn extract_part(zip_path: &str, dest: &Path, part: usize, parts: usize) -> Result<(), String> {
    let f = File::open(zip_path).map_err(es)?;
    let mut ar = zip::ZipArchive::new(BufReader::with_capacity(256 * 1024, f)).map_err(es)?;
    let mut i = part;
    while i < ar.len() {
        let mut e = ar.by_index(i).map_err(es)?;
        let rel = match e.enclosed_name() {
            Some(p) => p,
            None => return Err("zip-slip".to_string()),
        };
        let out = dest.join(rel);
        if e.is_dir() {
            fs::create_dir_all(&out).map_err(es)?;
        } else {
            if let Some(p) = out.parent() {
                fs::create_dir_all(p).map_err(es)?;
            }
            let mut w = BufWriter::with_capacity(256 * 1024, File::create(&out).map_err(es)?);
            io::copy(&mut e, &mut w).map_err(es)?;
            w.flush().map_err(es)?;
        }
        i += parts;
    }
    Ok(())
}

/// `sources` (dosya/klasör) öğelerini ZIP'e yazar; her öğe kendi adıyla kökte yer alır. Dosya sayısını döndürür.
pub fn zip_paths(sources: &[String], dest_zip: &str) -> Result<usize, String> {
    let file = File::create(dest_zip).map_err(es)?;
    // Hedef ZIP kaynakların içindeyse kendi kendini paketlemesin.
    let dest_canon = Path::new(dest_zip).canonicalize().ok();
    let mut zw = zip::ZipWriter::new(BufWriter::with_capacity(256 * 1024, file));
    let opts = zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
    let mut n = 0usize;
    for s in sources {
        let p = Path::new(s);
        let name = p.file_name().and_then(|x| x.to_str()).ok_or_else(|| "bad name".to_string())?;
        add_path(&mut zw, p, name, &opts, dest_canon.as_deref(), &mut n)?;
    }
    zw.finish().map_err(es)?.flush().map_err(es)?;
    Ok(n)
}

fn add_path<W: Write + io::Seek>(
    zw: &mut zip::ZipWriter<W>,
    p: &Path,
    name: &str,
    opts: &zip::write::SimpleFileOptions,
    skip: Option<&Path>,
    n: &mut usize,
) -> Result<(), String> {
    if let (Some(sk), Ok(c)) = (skip, p.canonicalize()) {
        if c == sk {
            return Ok(());
        }
    }
    let md = fs::metadata(p).map_err(es)?;
    if md.is_dir() {
        zw.add_directory(format!("{}/", name), opts.clone()).map_err(es)?;
        let mut kids: Vec<_> = fs::read_dir(p).map_err(es)?.flatten().collect();
        kids.sort_by_key(|e| e.file_name());
        for k in kids {
            let kn = match k.file_name().to_str() {
                Some(x) => x.to_string(),
                None => continue,
            };
            add_path(zw, &k.path(), &format!("{}/{}", name, kn), opts, skip, n)?;
        }
    } else if md.is_file() {
        zw.start_file(name, opts.clone()).map_err(es)?;
        let mut r = BufReader::with_capacity(256 * 1024, File::open(p).map_err(es)?);
        io::copy(&mut r, zw).map_err(es)?;
        *n += 1;
    }
    Ok(())
}
