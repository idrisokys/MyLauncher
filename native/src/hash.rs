//! Dosya özeti hesaplama (akış halinde, 1 MiB tampon).

use crate::util::par_map;
use sha2::{Digest, Sha256};
use std::fmt::Write as _;
use std::fs::File;
use std::io::{self, Read};

#[derive(Clone, Copy)]
pub enum Algo {
    Sha256,
    Md5,
    Crc32,
}

pub fn parse_algo(a: &str) -> Option<Algo> {
    match a.to_ascii_lowercase().as_str() {
        "sha256" | "sha-256" => Some(Algo::Sha256),
        "md5" => Some(Algo::Md5),
        "crc32" => Some(Algo::Crc32),
        _ => None,
    }
}

fn hex(b: &[u8]) -> String {
    let mut s = String::with_capacity(b.len() * 2);
    for x in b {
        let _ = write!(s, "{:02x}", x);
    }
    s
}

pub fn hash_file(path: &str, algo: Algo) -> io::Result<String> {
    let mut f = File::open(path)?;
    let mut buf = vec![0u8; 1 << 20];
    match algo {
        Algo::Sha256 => {
            let mut h = Sha256::new();
            loop {
                let n = f.read(&mut buf)?;
                if n == 0 {
                    break;
                }
                h.update(&buf[..n]);
            }
            Ok(hex(&h.finalize()))
        }
        Algo::Md5 => {
            let mut h = md5::Md5::new();
            loop {
                let n = f.read(&mut buf)?;
                if n == 0 {
                    break;
                }
                h.update(&buf[..n]);
            }
            Ok(hex(&h.finalize()))
        }
        Algo::Crc32 => {
            let mut h = crc32fast::Hasher::new();
            loop {
                let n = f.read(&mut buf)?;
                if n == 0 {
                    break;
                }
                h.update(&buf[..n]);
            }
            Ok(format!("{:08x}", h.finalize()))
        }
    }
}

/// Birden çok dosya paralel; başarısız olanlar için boş dize.
pub fn hash_files(paths: &[String], algo: Algo) -> Vec<String> {
    par_map(paths, 4, |p| hash_file(p, algo).unwrap_or_default())
}
