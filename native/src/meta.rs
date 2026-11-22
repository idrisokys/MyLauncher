//! Dosya meta verisi: klasör listesi (boyut/tarih/alt öğe sayısı) ve Link klasörü ağacı.

use crate::scan::mtime_ms;
use crate::util::par_map;
use std::fs;
use std::path::Path;

pub struct Meta {
    pub name: String,
    pub is_dir: bool,
    pub size: u64,
    pub mtime: i64,
    pub children: u32,
}

/// Klasör içeriği; her öğe için boyut, tarih ve (klasörse) alt öğe sayısı. Okunamazsa None.
pub fn list_dir_meta(dir: &str) -> Option<Vec<Meta>> {
    let rd = fs::read_dir(dir).ok()?;
    let names: Vec<String> = rd
        .flatten()
        .filter_map(|e| e.file_name().to_str().map(|s| s.to_string()))
        .collect();
    let base = Path::new(dir);
    let threads = if names.len() > 256 { 4 } else { 1 };
    Some(par_map(&names, threads, |name| {
        let p = base.join(name);
        match fs::metadata(&p) {
            Ok(md) => {
                let is_dir = md.is_dir();
                let children = if is_dir {
                    fs::read_dir(&p)
                        .map(|r| r.filter(|e| e.is_ok()).count() as u32)
                        .unwrap_or(0)
                } else {
                    0
                };
                Meta {
                    name: name.clone(),
                    is_dir,
                    size: if is_dir { 0 } else { md.len() },
                    mtime: mtime_ms(&md),
                    children,
                }
            }
            Err(_) => Meta { name: name.clone(), is_dir: false, size: 0, mtime: 0, children: 0 },
        }
    }))
}

fn ends_ci(s: &str, suf: &str) -> bool {
    s.len() >= suf.len()
        && s.is_char_boundary(s.len() - suf.len())
        && s[s.len() - suf.len()..].eq_ignore_ascii_case(suf)
}

/// Kotlin `NoteSync.isNoteName` ile aynı.
fn is_note_name(n: &str, dl: bool) -> bool {
    if dl {
        !n.starts_with('.')
            && !n.chars().any(|c| (c as u32) < 32 || ":*?\"<>|\\".contains(c))
            && !ends_ci(n, ".part")
            && !ends_ci(n, ".tmp")
            && !ends_ci(n, ".crdownload")
    } else {
        !ends_ci(n, ".part") && (ends_ci(n, ".url") || ends_ci(n, ".txt"))
    }
}

pub struct Node {
    pub rel: String,
    pub dir: bool,
    pub size: u64,
    pub mtime: i64,
}

/// Kotlin `NoteSync.local()` ile aynı yürüyüş.
pub fn walk_notes(root: &str, dl: bool, max_depth: i32) -> Vec<Node> {
    let mut out = Vec::new();
    walk(Path::new(root), "", 0, dl, max_depth, &mut out);
    out
}

fn walk(d: &Path, prefix: &str, depth: i32, dl: bool, max_depth: i32, out: &mut Vec<Node>) {
    if depth > max_depth {
        return;
    }
    let rd = match fs::read_dir(d) {
        Ok(r) => r,
        Err(_) => return,
    };
    for e in rd.flatten() {
        let name = match e.file_name().to_str() {
            Some(n) => n.to_string(),
            None => continue,
        };
        let rel = format!("{}{}", prefix, name);
        let path = e.path();
        let md = fs::metadata(&path).ok();
        let is_dir = md.as_ref().map(|m| m.is_dir()).unwrap_or(false);
        if is_dir {
            if dl && name.starts_with('.') {
                continue;
            }
            out.push(Node { rel: rel.clone(), dir: true, size: 0, mtime: 0 });
            walk(&path, &format!("{}/", rel), depth + 1, dl, max_depth, out);
        } else if is_note_name(&name, dl) {
            let (size, mt) = md.map(|m| (m.len(), mtime_ms(&m))).unwrap_or((0, 0));
            out.push(Node { rel, dir: false, size, mtime: mt });
        }
    }
}
