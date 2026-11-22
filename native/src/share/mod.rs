//! Hızlı Paylaş (dosya alma/gönderme) ve Notlarım eşitlemesi — Swift'teki `QuickShare` + `NoteSync`'in Rust karşılığı.
//!
//! Tel protokolü Android ile aynıdır (düz TCP + HTTP/1.1 benzeri):
//!   POST /u  (X-Name, Content-Length; eşitlemede X-Sync: 1, X-Mtime)  dosya yaz
//!   GET  /m  Link manifestosu      GET /f  dosya oku (X-Name)
//!   POST /d  klasör oluştur        DELETE /x  sil (X-Name, X-Dir; kalıcı silme)
//!
//! Protokol 2 (yalnızca Plan ↔ Plan; `GET /m` yanıtındaki `X-Plan-Proto: 2` ile anlaşılır):
//!   POST /b  toplu gönder (akış)   POST /g  toplu al (gövde: satır satır ad)
//! Eski cihazlar (Android) için dosya başına isteklere geri dönülür.
//!
//! * `bundle` – toplu aktarım biçimi (tek bağlantıda çok dosya)
//! * `proto`  – kodlama, paralel çalıştırıcı, ad temizleme, güvenli yol çözümleme, başlık ayrıştırma
//! * `server` – alma sunucusu (dinleyici + bağlantı işleyici)
//! * `client` – bağlantı, dosya/klasör gönderme
//! * `sync`   – Link eşitleme istemcisi (modlar 0/1/2) ve yerel manifesto
//!
//! mDNS/NSD (`_qshare._tcp`) yayını ve cihaz bulma Kotlin'de kalır; Rust'a `ip:port` verilir.
//!
//! Bu kopya Plan'ın (`plan_core::share`) Android uyarlamasıdır; farklar:
//! * `Scope`: Link (.url/.txt) ya da Download (`X-Root: dl`, her dosya türü) eşitlemesi
//! * silinenler `.onemsiz` klasörüne taşınır (geri alınabilir); Plan'da silme kalıcıdır
//! * alınan aynı adlı dosya `ad_1.uzantı` olur (`Config::android_names`)
//! * `Event::Busy`: Kotlin tarafı Wi-Fi/CPU kilidini bağlantı süresince tutar
//! * `client::send_reader`: `ContentResolver` dosya tanıtıcısından gönderme

pub mod bundle;
pub mod client;
pub mod proto;
pub mod server;
pub mod sync;
