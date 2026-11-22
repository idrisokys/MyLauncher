//! Notlar klasörünün dolaşımı: Kotlin `Store.list/ordered` + `Store.search` içindeki yürüyüşün birebir karşılığı.
//!
//! Neden var: Kotlin tarafı her klasörde kilit alıp `.order.txt` / `.pinned.txt` okuyor, sıralıyor ve her klasör adı için
//! harf başına `Normalizer` çağırıyordu. Burada tüm ağaç tek JNI çağrısıyla gezilir; dosya içeriği eşleştirmesi
//! (`search::match_notes`) zaten Rust'tadır ve Kotlin'deki 64'lük grup döngüsü (iptal denetimi) olduğu gibi kalır.
//!
//! Kurallar (Kotlin ile aynı; Plan'ın `tree.rs`'inden FARKLI olarak ad eşleştirmesi NFC'siz, tam eşleşmedir):
//! * `.` ile başlayanlar ve `.url`/`.txt` dışındaki dosyalar listelenmez; klasörler listelenir
//! * `.order.txt` sırası (tekrarlar atılır), kalanlar klasörler önce + küçük harfli ada göre
//! * `.pinned.txt` içindekiler başa alınır (kendi aralarında mevcut sıra korunur)

use crate::search::{fold, terms_of};
use std::collections::{HashMap, HashSet};
use std::fs;
use std::path::Path;

const ORDER: &str = ".order.txt";
const PINNED: &str = ".pinned.txt";
/// Sembolik bağ döngülerine karşı derinlik sınırı.
const MAX_DEPTH: usize = 64;

struct Node {
    name: String,
    is_dir: bool,
    pinned: bool,
}

/// Dolaşımda bir öğe: kök klasöre göre `/` ile ayrılmış yol.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Walked {
    pub rel: String,
    pub is_dir: bool,
    pub pinned: bool,
    /// Yalnızca klasörler için: yazılan her kelime klasör adında geçiyor (Kotlin `folderHits`).
    pub name_hit: bool,
}

fn ends_ci(s: &str, suf: &str) -> bool {
    s.to_lowercase().ends_with(suf)
}

/// Kotlin `readLines().filter { it.isNotBlank() }` (satırlar kırpılmaz).
fn read_lines(path: &Path) -> Vec<String> {
    let Ok(bytes) = fs::read(path) else { return Vec::new() };
    let text = String::from_utf8_lossy(&bytes).replace("\r\n", "\n").replace('\r', "\n");
    text.split('\n').filter(|l| !l.trim().is_empty()).map(str::to_string).collect()
}

/// Bir klasörün sıralı, süzülmüş içeriği (Kotlin `Store.ordered`).
fn list(dir: &Path) -> Vec<Node> {
    let Ok(rd) = fs::read_dir(dir) else { return Vec::new() };
    let mut files: Vec<Node> = Vec::new();
    for e in rd.flatten() {
        let name = e.file_name().to_string_lossy().into_owned();
        if name.starts_with('.') {
            continue;
        }
        let is_dir = fs::metadata(e.path()).map(|m| m.is_dir()).unwrap_or(false);
        if is_dir || ends_ci(&name, ".url") || ends_ci(&name, ".txt") {
            files.push(Node { name, is_dir, pinned: false });
        }
    }

    let mut by_name: HashMap<&str, usize> = HashMap::new();
    for (i, f) in files.iter().enumerate() {
        by_name.insert(f.name.as_str(), i);
    }
    let mut seen_lines: HashSet<String> = HashSet::new();
    let mut used: HashSet<usize> = HashSet::new();
    let mut order: Vec<usize> = Vec::new();
    for line in read_lines(&dir.join(ORDER)) {
        if !seen_lines.insert(line.clone()) {
            continue; // distinct
        }
        if let Some(&i) = by_name.get(line.as_str()) {
            order.push(i);
            used.insert(i);
        }
    }
    let mut rest: Vec<usize> = (0..files.len()).filter(|i| !used.contains(i)).collect();
    rest.sort_by_cached_key(|&i| (!files[i].is_dir, files[i].name.to_lowercase()));
    order.extend(rest);

    let pins: HashSet<String> = read_lines(&dir.join(PINNED)).into_iter().collect();
    let mut slots: Vec<Option<Node>> = files.into_iter().map(Some).collect();
    let mut all: Vec<Node> = order.into_iter().filter_map(|i| slots[i].take()).collect();
    if pins.is_empty() {
        return all;
    }
    for n in all.iter_mut() {
        n.pinned = pins.contains(&n.name);
    }
    let (mut first, rest): (Vec<Node>, Vec<Node>) = all.into_iter().partition(|n| n.pinned);
    first.extend(rest);
    first
}

/// Tüm ağacı gezer: her klasör kendi içeriğinden ÖNCE gelir. `query` boş/boşluksa boş döner.
pub fn walk_for_search(root: &Path, query: &str) -> Vec<Walked> {
    let terms = terms_of(query);
    let mut out = Vec::new();
    if terms.is_empty() {
        return out;
    }
    go(root, "", 0, &terms, &mut out);
    out
}

fn go(dir: &Path, rel: &str, depth: usize, terms: &[String], out: &mut Vec<Walked>) {
    if depth >= MAX_DEPTH {
        return;
    }
    for n in list(dir) {
        let r = if rel.is_empty() { n.name.clone() } else { format!("{}/{}", rel, n.name) };
        if n.is_dir {
            let folded = fold(&n.name);
            let name_hit = terms.iter().all(|t| folded.contains(t.as_str()));
            out.push(Walked { rel: r.clone(), is_dir: true, pinned: n.pinned, name_hit });
            go(&dir.join(&n.name), &r, depth + 1, terms, out);
        } else {
            out.push(Walked { rel: r, is_dir: false, pinned: n.pinned, name_hit: false });
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(name: &str) -> std::path::PathBuf {
        let d = std::env::temp_dir().join(format!("myl_tree_{}_{}", name, std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d
    }

    fn rels(v: &[Walked]) -> Vec<&str> {
        v.iter().map(|w| w.rel.as_str()).collect()
    }

    #[test]
    fn default_order_dirs_first_then_lowercase_name_and_filters() {
        let d = tmp("order");
        fs::create_dir(d.join("Zeta")).unwrap();
        fs::create_dir(d.join("alfa")).unwrap();
        for f in ["b.url", "A.TXT", "x.png", ".gizli.txt"] {
            fs::write(d.join(f), "x").unwrap();
        }
        let l: Vec<String> = list(&d).into_iter().map(|n| n.name).collect();
        assert_eq!(l, vec!["alfa", "Zeta", "A.TXT", "b.url"]);
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn order_file_wins_unlisted_follow_and_duplicates_dropped() {
        let d = tmp("ordfile");
        for f in ["a.url", "b.url", "c.url"] {
            fs::write(d.join(f), "x").unwrap();
        }
        fs::write(d.join(ORDER), "c.url\r\nyok.url\nb.url\nc.url\n\n").unwrap();
        let l: Vec<String> = list(&d).into_iter().map(|n| n.name).collect();
        assert_eq!(l, vec!["c.url", "b.url", "a.url"]);
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn pinned_first_keeping_relative_order() {
        let d = tmp("pin");
        for f in ["a.url", "b.url", "c.url"] {
            fs::write(d.join(f), "x").unwrap();
        }
        fs::write(d.join(PINNED), "c.url\nb.url").unwrap();
        let l = list(&d);
        assert_eq!(l.iter().map(|n| n.name.as_str()).collect::<Vec<_>>(), vec!["b.url", "c.url", "a.url"]);
        assert_eq!(l.iter().map(|n| n.pinned).collect::<Vec<_>>(), vec![true, true, false]);
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn walk_emits_dirs_before_children_with_relative_paths_and_name_hits() {
        let d = tmp("walk");
        fs::create_dir(d.join("İş Planı")).unwrap();
        fs::write(d.join("İş Planı/not.txt"), "x").unwrap();
        fs::write(d.join("kök.url"), "x").unwrap();
        let w = walk_for_search(&d, "is plani");
        assert_eq!(rels(&w), vec!["İş Planı", "İş Planı/not.txt", "kök.url"]);
        assert!(w[0].is_dir && w[0].name_hit);
        assert!(!w[1].is_dir && !w[1].name_hit);
        assert!(walk_for_search(&d, "   ").is_empty());
        let _ = fs::remove_dir_all(&d);
    }
}
