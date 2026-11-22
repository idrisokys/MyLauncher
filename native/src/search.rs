//! Not arama çekirdeği: Kotlin `Store.search` içindeki dosya okuma + eşleştirme kısmının birebir karşılığı.
//! Klasör yürüyüşü ve sıralama Kotlin'de kalır; burada yalnızca verilen dosyalar paralel okunup eşleştirilir.

use crate::util::par_map;
use std::fs;
use unicode_normalization::UnicodeNormalization;

pub struct HitInfo {
    pub idx: usize,
    pub title_match: bool,
    pub is_note: bool,
    pub detail: String,
}

fn fold_char(c: char) -> char {
    if (c as u32) < 128 {
        return c.to_ascii_lowercase();
    }
    match c {
        'İ' | 'ı' => 'i',
        'ş' | 'Ş' => 's',
        'ğ' | 'Ğ' => 'g',
        'ü' | 'Ü' => 'u',
        'ö' | 'Ö' => 'o',
        'ç' | 'Ç' => 'c',
        _ => {
            let first = std::iter::once(c).nfd().next().unwrap_or(c);
            first.to_lowercase().next().unwrap_or(first)
        }
    }
}

/// Kotlin `Store.fold`: büyük/küçük harf, Türkçe i/ı ve aksan farkını yok sayar; karakter sayısını korur.
pub fn fold(s: &str) -> String {
    s.chars().map(fold_char).collect()
}

fn is_ws(c: char) -> bool {
    matches!(c, ' ' | '\t' | '\n' | '\u{0B}' | '\u{0C}' | '\r')
}

pub fn terms_of(q: &str) -> Vec<String> {
    fold(q)
        .split(is_ws)
        .filter(|t| !t.is_empty())
        .map(|t| t.to_string())
        .collect()
}

fn collapse_ws(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut in_ws = false;
    for c in s.chars() {
        if is_ws(c) {
            if !in_ws {
                out.push(' ');
            }
            in_ws = true;
        } else {
            out.push(c);
            in_ws = false;
        }
    }
    out
}

fn strip_bom(s: &str) -> &str {
    s.strip_prefix('\u{FEFF}').unwrap_or(s)
}

fn read_text(path: &str) -> Option<String> {
    fs::read(path).ok().map(|b| String::from_utf8_lossy(&b).into_owned())
}

/// Kotlin `readLines().map { trim().removePrefix(BOM) }`
fn lines_of(text: &str) -> Vec<String> {
    text.replace("\r\n", "\n")
        .replace('\r', "\n")
        .split('\n')
        .map(|l| strip_bom(l.trim()).to_string())
        .collect()
}

fn prefix_ci<'a>(l: &'a str, p: &str) -> Option<&'a str> {
    l.get(..p.len()).filter(|h| h.eq_ignore_ascii_case(p)).map(|_| &l[p.len()..])
}

fn url_of(ls: &[String]) -> Option<String> {
    ls.iter()
        .find_map(|l| prefix_ci(l, "URL="))
        .map(|u| u.trim().to_string())
        .filter(|u| !u.is_empty())
}

fn unescape(dec: &str) -> String {
    let cs: Vec<char> = dec.chars().collect();
    let mut out = String::with_capacity(dec.len());
    let mut i = 0;
    while i < cs.len() {
        if cs[i] == '\\' && i + 1 < cs.len() {
            out.push(if cs[i + 1] == 'n' { '\n' } else { cs[i + 1] });
            i += 2;
        } else {
            out.push(cs[i]);
            i += 1;
        }
    }
    out
}

fn text_of(ls: &[String]) -> Option<String> {
    ls.iter().find_map(|l| prefix_ci(l, "TEXT=")).map(unescape)
}

fn ends_ci(s: &str, suf: &str) -> bool {
    s.len() >= suf.len()
        && s.is_char_boundary(s.len() - suf.len())
        && s[s.len() - suf.len()..].eq_ignore_ascii_case(suf)
}

fn match_file(path: &str, terms: &[String]) -> Option<(bool, bool, String)> {
    let name = path.rsplit('/').next().unwrap_or(path);
    let is_txt = ends_ci(name, ".txt");
    let title = if is_txt || ends_ci(name, ".url") { &name[..name.len() - 4] } else { name };

    let raw = read_text(path);
    let (url, text) = if is_txt {
        (None, raw.as_deref().map(|r| strip_bom(r).to_string()).unwrap_or_default())
    } else {
        let ls = raw.as_deref().map(lines_of).unwrap_or_default();
        (url_of(&ls), text_of(&ls).unwrap_or_default())
    };

    let ft = fold(title);
    let body: &str = url.as_deref().unwrap_or(&text);
    let fb = fold(body);
    if !terms.iter().all(|t| ft.contains(t.as_str()) || fb.contains(t.as_str())) {
        return None;
    }

    let detail = match &url {
        Some(u) => u.clone(),
        None => {
            let i = terms
                .iter()
                .filter_map(|t| fb.find(t.as_str()).map(|b| fb[..b].chars().count()))
                .min()
                .unwrap_or(0);
            let st = i.saturating_sub(30);
            let tc: Vec<char> = text.chars().collect();
            let s = st.min(tc.len());
            let e = tc.len().min(st + 120);
            let mut d = String::new();
            if st > 0 {
                d.push('…');
            }
            d.extend(tc[s..e].iter());
            collapse_ws(&d)
        }
    };
    let title_match = terms.iter().all(|t| ft.contains(t.as_str()));
    Some((title_match, url.is_none(), detail))
}

/// `paths` içindeki her dosyayı sorguyla eşleştirir; sonuçlar girdi sırasındadır.
pub fn match_notes(paths: &[String], query: &str) -> Vec<HitInfo> {
    let terms = terms_of(query);
    if terms.is_empty() {
        return Vec::new();
    }
    let idx: Vec<usize> = (0..paths.len()).collect();
    par_map(&idx, 4, |&i| {
        match_file(&paths[i], &terms).map(|(tm, note, detail)| HitInfo {
            idx: i,
            title_match: tm,
            is_note: note,
            detail,
        })
    })
    .into_iter()
    .flatten()
    .collect()
}
