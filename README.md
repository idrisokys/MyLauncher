

## Rust yerel kütüphanesi

Ağır işler (dosya tarama, arama, ZIP, özet, küçük resim) `native/` altındaki Rust crate'inde. Yerelde derlemek için:

```
rustup target add aarch64-linux-android
cargo install cargo-ndk --locked
gradle assembleRelease      # buildRust görevi .so'yu otomatik üretir (ANDROID_NDK_HOME gerekir)
```

`cargo-ndk` yoksa uygulama yine derlenir ve eski Kotlin koduyla çalışır.

### Hızlı Paylaş / Link ve Download eşitlemesi (Rust, Plan ile ORTAK)

Alma sunucusu, dosya gönderme ve Link/Download eşitlemesi, macOS uygulaması **Plan ile aynı** olan `qshare_core/`
crate'indedir (yalnızca std, bağımlılıksız). Artık iki projede ayrı kopyalar yok. `native/src/share_jni.rs` yalnızca
JNI köprüsüdür; Kotlin tarafı `Native.serverStart / serverPoll / send / sync` ile kullanır. `libmylnative.so` yoksa ya da
çağrı başarısız olursa eski Kotlin kodu devreye girer. Tel protokolü Kotlin sürümüyle aynıdır.

Android'e özgü davranışlar `qshare_core`'da yapılandırmayla seçilir: Download kapsamı (`X-Root: dl`), silinenlerin `.onemsiz`
klasörüne taşınması, `ad_1.uzantı` adlandırması, Link'te gizli klasörlerin eşitlenmesi, bağlantı başladı/bitti olayları
(Wi-Fi/CPU kilidi) ve dosya tanıtıcısından (`content://`) gönderme.

Testler: `cd qshare_core && cargo test --release` (protokol + eşitleme, uçtan uca) ve `cd native && cargo test --release`.

`qshare_core` iki repoda aynı dizinde durur. Tek kaynaktan yönetmek için git submodule yapılabilir:
`git submodule add <qshare_core-repo-adresi> qshare_core` (CI'da `actions/checkout` adımına `submodules: true` eklenir).

## Rust hız çalışması (son eklemeler)

- `qshare_core/mdns.rs` + `Native.mdnsBrowse`: cihaz bulma NsdManager'a EK olarak Rust'ta paralel yapılır (`Discovery.browse`); NsdManager yedek olarak çalışmaya devam eder.
- `qshare_core/share/server.rs`: alma sunucusu bloklayan `accept` kullanır (40 ms yoklama kalktı).
- `native/src/tree.rs` + `Native.walkForSearch`: `Store.search` klasör yürüyüşü Rust'ta (sıra, sabitleme, klasör adı eşleşmesi); Rust yoksa Kotlin yürüyüşü.
- `native/src/thumbcache.rs` + `Native.thumbGet/thumbPut/thumbTrim`: galeri küçük resimleri ve video kareleri `cacheDir/thumbs` altında JPEG olarak saklanır (96 MB sınır; saydam görüntüler önbelleğe yazılmaz).
- `GalleryActivity`: sıralama karşılaştırmasında dosya başına tek `lastModified()`; `CropWallpaperActivity.render()` arka plan iş parçacığında.

Bu değişiklikler Rust derleyicisi / Android SDK olmayan bir ortamda yazıldı: ilk CI çalıştırmasında derleme hatası çıkabilir.
