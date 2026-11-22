

## Rust yerel kütüphanesi

Ağır işler (dosya tarama, arama, ZIP, özet, küçük resim) `native/` altındaki Rust crate'inde. Yerelde derlemek için:

```
rustup target add aarch64-linux-android
cargo install cargo-ndk --locked
gradle assembleRelease      # buildRust görevi .so'yu otomatik üretir (ANDROID_NDK_HOME gerekir)
```

`cargo-ndk` yoksa uygulama yine derlenir ve eski Kotlin koduyla çalışır.

### Hızlı Paylaş / Link eşitlemesi (Rust)

Alma sunucusu, dosya gönderme ve Link/Download eşitlemesi de `native/src/share/` altındaki Rust kodunda
(Plan'ın `plan_core::share` modülünün Android uyarlaması; tel protokolü Kotlin sürümüyle aynıdır).
Kotlin tarafı `Native.serverStart / serverPoll / send / sync` köprüsünü kullanır; `libmylnative.so` yoksa ya da
çağrı başarısız olursa eski Kotlin kodu devreye girer. Testler: `cd native && cargo test --release`.
