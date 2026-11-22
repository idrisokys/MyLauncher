//! JNI yardımcıları ve basit paralel eşleme.

use jni::objects::{JObject, JObjectArray, JString};
use jni::sys::jobjectArray;
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Mutex;

/// Panik JVM'e sızmasın: yakalanır ve `default` döner.
pub fn guard<T>(default: T, f: impl FnOnce() -> T) -> T {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or(default)
}

pub fn jstr(env: &mut JNIEnv, s: &JString) -> Option<String> {
    if s.as_raw().is_null() {
        return None;
    }
    env.get_string(s).ok().map(String::from)
}

pub fn jstr_array(env: &mut JNIEnv, arr: &JObjectArray) -> Vec<String> {
    if arr.as_raw().is_null() {
        return Vec::new();
    }
    let n = env.get_array_length(arr).unwrap_or(0);
    let mut v = Vec::with_capacity(n.max(0) as usize);
    for i in 0..n {
        match env.get_object_array_element(arr, i) {
            Ok(o) => {
                let js = JString::from(o);
                v.push(jstr(env, &js).unwrap_or_default());
                let _ = env.delete_local_ref(js);
            }
            Err(_) => v.push(String::new()),
        }
    }
    v
}

pub fn to_jstr_array(env: &mut JNIEnv, items: &[String]) -> jobjectArray {
    let cls = match env.find_class("java/lang/String") {
        Ok(c) => c,
        Err(_) => return std::ptr::null_mut(),
    };
    let arr = match env.new_object_array(items.len() as i32, &cls, JObject::null()) {
        Ok(a) => a,
        Err(_) => return std::ptr::null_mut(),
    };
    for (i, s) in items.iter().enumerate() {
        if let Ok(js) = env.new_string(s) {
            let _ = env.set_object_array_element(&arr, i as i32, &js);
            let _ = env.delete_local_ref(js);
        }
    }
    arr.into_raw()
}

pub fn cpu_threads(min: usize, max: usize) -> usize {
    std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(2)
        .clamp(min, max)
}

/// Sıra korunarak paralel `map`.
pub fn par_map<T: Sync, R: Send>(items: &[T], max_threads: usize, f: impl Fn(&T) -> R + Sync) -> Vec<R> {
    let n = items.len();
    if n == 0 {
        return Vec::new();
    }
    let threads = max_threads.min(n).max(1);
    if threads == 1 {
        return items.iter().map(|x| f(x)).collect();
    }
    let next = AtomicUsize::new(0);
    let results: Mutex<Vec<(usize, R)>> = Mutex::new(Vec::with_capacity(n));
    std::thread::scope(|s| {
        for _ in 0..threads {
            s.spawn(|| {
                let mut local: Vec<(usize, R)> = Vec::new();
                loop {
                    let i = next.fetch_add(1, Ordering::Relaxed);
                    if i >= n {
                        break;
                    }
                    local.push((i, f(&items[i])));
                }
                results.lock().unwrap().extend(local);
            });
        }
    });
    let mut v = results.into_inner().unwrap();
    v.sort_by_key(|(i, _)| *i);
    v.into_iter().map(|(_, r)| r).collect()
}
