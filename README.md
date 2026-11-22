

## Rust yerel kütüphanesi

Ağır işler (dosya tarama, arama, ZIP, özet, küçük resim) `native/` altındaki Rust crate'inde. Yerelde derlemek için:

```
rustup target add aarch64-linux-android
cargo install cargo-ndk --locked
gradle assembleRelease      # buildRust görevi .so'yu otomatik üretir (ANDROID_NDK_HOME gerekir)
```

`cargo-ndk` yoksa uygulama yine derlenir ve eski Kotlin koduyla çalışır.
