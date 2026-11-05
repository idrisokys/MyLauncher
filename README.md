<h1 align="center">MyL — A Light and Fast Android Launcher</h1>

<p align="center">
  🇬🇧 <a href="#-english">English</a> · 🇹🇷 <a href="#-türkçe">Türkçe</a>
</p>

---

# 🇬🇧 English

# MyL — A Light and Fast Android Launcher

A simple, fast and no-frills Android home screen. All your apps on one screen, a powerful search bar, drag-and-drop folders and app shortcuts. No extra libraries (only `RecyclerView`), no background services, no ads, no tracking.

---

## ✨ Features

### 🏠 Home screen
- **4-column grid** with unlimited vertical scrolling
- **Right-to-left** layout (RTL): the first app starts at the top right
- The system **wallpaper** shows through; the status and navigation bars are transparent (edge-to-edge design)
- Newly installed apps are added to the **end of the list, alphabetically (Turkish collation)**; removed apps are cleaned up automatically
- Your order, folders and shortcuts are stored persistently

### 🔍 Smart search
Tap the **search bar** at the top to start searching.

| Result | Description |
|---|---|
| 👤 **Contacts** | Searches contacts by name (permission is requested on first search). **Tap** = phone call (a number picker appears if the contact has several), **long-press** = opens their WhatsApp chat |
| 📱 **Apps** | Prefix matches first. Contacts + apps are limited to 5 results in total; large text and a non-tappable blank line between results prevent mis-taps |
| 🌐 **Web** | One-tap search on Google, Yandex and IMDb |

- The keyboard **Search** key searches Google directly
- **Turkish-character aware**: `ısı`, `isi` and `Isı` all return the same result (ı/i, ö/o, ü/u, ş/s, ç/c, ğ/g)
- Contact lookup runs in the background, so the UI never freezes while typing

### ⚙️ Settings button
Next to the edit button there is a **⚙ button** with: **Back up layout** (saves the order, folders and shortcuts as a `.json` file in the Downloads folder), **Restore layout** (pick a backup file to replace the current layout; apps no longer installed are dropped) and **Settings** (system settings).

### 🖼 Set as wallpaper (share menu)
Share any image from any app and pick **"Duvar kağıdı yap"** (Set as wallpaper). The image is fitted to the screen without cropping and applied to both the **home screen and lock screen**. It has no UI of its own; it only appears in the share menu.

### 📁 Edit mode and folders
The **✥ button** at the top right toggles edit mode. In edit mode the ⚙ button turns into a **folder button**.

- **Long-press and drag** an icon
  - Drop it on the **center** of another icon → the two icons **swap places** (no folder is created by dragging). A **blue ring** on the target shows which icon will be swapped
  - Drop it on the **edge** of an icon (between two icons) → it is **inserted between them**. A **blue bar** shows where it will go
- **Folder button**: pick **"New folder"** (with a name) or an **existing folder**, tick any number of apps and tap **Add**
- In edit mode, tapping an app does **not** launch it
- The folder icon shows a **mini preview** of its first 4 apps
- Tap the folder name to **rename** it
- Folder menu: **Rename**, **Dissolve folder**
- Long-press an app inside a folder → **Remove from folder**
- A folder left with a single app is dissolved automatically

### 📌 Long-press menu (when edit mode is off)
**For apps**
- The app's own shortcuts (up to 8), e.g. "New message", "Camera → Selfie"
- Tapping a shortcut offers **Launch** or **Add to home screen**
- **App info** (system settings)
- **Uninstall**

**For shortcuts**
- **Launch**
- **Remove from home screen**

### 🔗 Pinned shortcuts
**"Add to home screen"** requests from other apps (e.g. pinning a chat) are accepted directly and added to the grid.

### 🎯 Default launcher behavior
- **Home button**: closes search, leaves edit mode, closes any open folder and scrolls to the top
- **Back button**: closes search/edit mode if open, does nothing on the home screen
- If MyL is not the default and is opened from its icon, the **default home app** setting opens automatically
- Tapping its own icon in the grid opens the same setting

---

## ⚡ Performance

This version was optimized from the ground up for speed:

- The app list and icons load on a **background thread**, so the main thread is never blocked
- Icons are rendered **once** into a bitmap at the target size and cached; nothing is recomputed while scrolling
- A **quick validation** runs every time you return to the home screen; if nothing changed, the UI is left untouched
- The grid is updated with **DiffUtil**, so only changed cells are refreshed
- Package events (install/uninstall/update) are **coalesced**; only the affected package's icon is refreshed
- If the system temporarily returns an empty list, your layout is **not wiped**
- **R8** code and resource shrinking is enabled in release builds

---

## 🔐 Permissions

| Permission | Why |
|---|---|
| `READ_CONTACTS` | Show contact results in search (asked on first search; if denied, only contact results are missing) |
| `CALL_PHONE` | Place a call directly when a contact is tapped in search (asked on first call; if denied, the dialer opens instead) |
| `QUERY_ALL_PACKAGES` | List all installed apps and their shortcuts |
| `REQUEST_DELETE_PACKAGES` | Open the uninstall screen from the menu |
| `SET_WALLPAPER` | Set the shared image as wallpaper (normal permission, no prompt) |

There is **no internet permission**. Web searches are handed off to your browser. Data is stored only on the device (`SharedPreferences`), and backup is disabled (`allowBackup=false`).

---

## 🛠 Technical details

| | |
|---|---|
| Language | Kotlin 1.9.22 |
| Android Gradle Plugin | 8.2.2 |
| minSdk / targetSdk / compileSdk | 30 (Android 11) / 34 / 34 |
| Dependency | `androidx.recyclerview:recyclerview:1.3.2` |
| UI | Built entirely in code (no XML layouts) |
| Package | `io.github.idrisokys.myl` |

### Project structure
```
MyLauncher/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/io/github/idrisokys/myl/MainActivity.kt   # all logic + FolderDrawable
        ├── java/io/github/idrisokys/myl/SetWallpaperActivity.kt   # share menu: set wallpaper
        └── res/
            ├── drawable/   # app icon and edit button
            ├── mipmap-anydpi/
            └── values/styles.xml                   # wallpaper-themed window
```

---

## 🚀 Build and install

1. Open the project in **Android Studio** (Hedgehog or newer recommended, JDK 17).
2. Once Gradle sync finishes, run it on a connected phone with **Run ▶**.
3. To produce an APK: **Build → Build Bundle(s) / APK(s) → Build APK(s)**
   To sign with your own key, see **Signing a release APK** below.
4. From the command line: `gradle wrapper`, then `./gradlew assembleRelease`
5. On first launch, choose MyL as your **default home app**.

---

## 🔏 Signing a release APK

1. Create a keystore once (keep the `.jks` file and passwords safe, you cannot update the app without them):
   ```
   keytool -genkeypair -v -keystore myl-release.jks -alias myl -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Put `myl-release.jks` in the project root, copy `keystore.properties.example` to `keystore.properties` and fill in your details.
3. Build: `./gradlew assembleRelease`
   Output: `app/build/outputs/apk/release/app-release.apk`
4. Increase `versionCode` in `app/build.gradle.kts` for every new release.

If `keystore.properties` is missing, the release APK is signed with the debug key. `keystore.properties` and `*.jks` are already in `.gitignore`; never commit them.

---

## ⚠️ Known limitations

- Single page: no horizontally swiping multiple home screens
- No widget support
- No icon pack / custom theme support
- Work profile and multi-user apps are not listed (main user only)
- Animated icons (e.g. live calendar/clock) are shown statically
- Some vendors (e.g. ColorOS) can miss package events, so a quick check runs every time you return to the home screen

---

## 📄 License

Personal project. Feel free to use and modify it as you like.

---

<br>

# 🇹🇷 Türkçe

# MyL — Hafif ve Hızlı Android Launcher

Sade, hızlı ve gösterişten uzak bir Android ana ekran uygulaması. Tek ekranda tüm uygulamalar, güçlü bir arama çubuğu, sürükle-bırak klasörler ve uygulama kısayolları. Ek kütüphane yok (yalnızca `RecyclerView`), arka plan servisi yok, reklam ve izleme yok.

---

## ✨ Özellikler

### 🏠 Ana ekran
- **4 sütunlu ızgara**, aşağı doğru sınırsız kaydırma
- **Sağdan sola** diziliş (RTL): ilk uygulama sağ üstte başlar
- Sistem **duvar kağıdı** arkada görünür, durum ve gezinme çubukları saydamdır (kenardan kenara tasarım)
- Yeni kurulan uygulamalar listenin **sonuna, alfabetik (Türkçe sıralama)** eklenir; kaldırılanlar otomatik temizlenir
- Sıralamanız, klasörleriniz ve kısayollarınız kalıcı olarak saklanır

### 🔍 Akıllı arama
Üstteki **arama çubuğuna** dokununca arama açılır.

| Sonuç | Açıklama |
|---|---|
| 👤 **Kişiler** | Rehberde ada göre arar (ilk aramada izin istenir). **Dokununca** telefon araması yapar (birden çok numara varsa seçim çıkar), **basılı tutunca** WhatsApp sohbetini açar |
| 📱 **Uygulamalar** | Baştan eşleşenler üstte. Kişi + uygulama sonuçları toplam 5 ile sınırlı; büyük yazı ve sonuçlar arasındaki dokunulmaz boş satır yanlış dokunmayı önler |
| 🌐 **Web** | Google, Yandex ve IMDb'de tek dokunuşla arama |

- Klavyede **Ara** tuşu doğrudan Google'da arar
- **Türkçe karakter duyarlı**: `ısı`, `isi`, `Isı` hepsi aynı sonucu bulur (ı/i, ö/o, ü/u, ş/s, ç/c, ğ/g)
- Kişi araması arka planda yapılır, yazarken arayüz donmaz

### ⚙️ Ayarlar düğmesi
Düzenle düğmesinin yanında bir **⚙ düğmesi** vardır: **Düzeni yedekle** (sıra, klasörler ve kısayolları İndirilenler klasörüne `.json` dosyası olarak kaydeder), **Düzeni geri al** (yedek dosyasını seçince mevcut düzenin yerine yükler; artık yüklü olmayan uygulamalar atlanır) ve **Ayarlar** (sistem ayarları).

### 🖼 Duvar kağıdı yap (Paylaş menüsü)
Herhangi bir uygulamadan bir görseli paylaşıp **"Duvar kağıdı yap"** seçeneğine dokunun. Görsel kırpılmadan ekrana sığdırılır ve **ana ekran ile kilit ekranına** birlikte uygulanır. Kendi arayüzü yoktur, yalnızca Paylaş menüsünde görünür.

### 📁 Düzenleme modu ve klasörler
Sağ üstteki **✥ düğmesi** düzenleme modunu açar/kapatır. Düzenleme modunda ⚙ düğmesi **klasör düğmesine** dönüşür.

- Simgeye **uzun bas ve sürükle**
  - Başka bir simgenin **ortasına** bırak → iki simge **yer değiştirir** (sürükleyerek klasör oluşmaz). Hedefteki **mavi halka** hangi simgenin yer değiştireceğini gösterir
  - Bir simgenin **kenarına** (iki simgenin arasına) bırak → o iki simgenin **arasına girer**. **Mavi çubuk** nereye gireceğini gösterir
- **Klasör düğmesi**: **"Yeni klasör"** (adıyla) ya da **var olan bir klasörü** seç, istediğin kadar uygulamayı işaretle ve **Ekle**'ye dokun
- Düzenleme modunda uygulamaya dokunmak uygulamayı **açmaz**
- Klasör simgesi içindeki ilk 4 uygulamanın **mini önizlemesini** gösterir
- Klasörün adına dokunarak **yeniden adlandır**
- Klasör menüsü: **Yeniden adlandır**, **Klasörü dağıt**
- Klasör içindeki bir uygulamaya uzun bas → **Klasörden çıkar**
- İçinde tek uygulama kalan klasör otomatik dağılır

### 📌 Uzun basma menüsü (düzenleme kapalıyken)
**Uygulamalar için**
- Uygulamanın kendi kısayolları (en fazla 8), örn. "Yeni mesaj", "Kamera → Selfie"
- Bir kısayola dokununca **Başlat** veya **Ana ekrana ekle** seçeneği
- **Uygulama bilgisi** (sistem ayarları)
- **Kaldır**

**Kısayollar için**
- **Başlat**
- **Ana ekrandan kaldır**

### 🔗 Kısayol sabitleme
Başka uygulamalardan gelen **"Ana ekrana ekle"** istekleri (ör. bir sohbeti ana ekrana eklemek) doğrudan kabul edilir ve ızgaraya eklenir.

### 🎯 Varsayılan launcher davranışı
- **Ana ekran tuşu**: aramayı kapatır, düzenleme modundan çıkar, açık klasörü kapatır ve en başa kaydırır
- **Geri tuşu**: arama/düzenleme açıksa onu kapatır, ana ekranda hiçbir şey yapmaz
- MyL varsayılan değilse ve simgesinden açılırsa, **varsayılan ana ekran seçimi** ayarı otomatik açılır
- Kendi simgesine dokunmak da aynı ayarı açar

---

## ⚡ Performans

Bu sürüm hız için baştan optimize edildi:

- Uygulama listesi ve simgeler **arka plan thread'inde** yüklenir, ana thread bloklanmaz
- Simgeler **bir kez** hedef boyutta bitmap'e çizilir ve önbellekte tutulur; kaydırma sırasında yeniden hesaplanmaz
- Ana ekrana her dönüşte **hızlı doğrulama** yapılır, değişiklik yoksa ekrana dokunulmaz
- Izgara **DiffUtil** ile güncellenir, yalnızca değişen hücreler yenilenir
- Paket olayları (kurulum/kaldırma/güncelleme) **birleştirilir**; yalnızca ilgili paketin simgesi yenilenir
- Sistem geçici olarak boş liste dönerse düzen **silinmez**
- Release derlemesinde **R8** kod ve kaynak küçültme açık

---

## 🔐 İzinler

| İzin | Neden |
|---|---|
| `READ_CONTACTS` | Aramada kişi sonuçlarını göstermek (ilk aramada istenir, reddedersen sadece kişi sonuçları çıkmaz) |
| `CALL_PHONE` | Aramada bir kişiye dokununca doğrudan arama yapmak (ilk aramada istenir, reddedersen çevirici açılır) |
| `QUERY_ALL_PACKAGES` | Yüklü tüm uygulamaları ve kısayollarını listelemek |
| `REQUEST_DELETE_PACKAGES` | Menüden uygulama kaldırma ekranını açmak |
| `SET_WALLPAPER` | Paylaşılan görseli duvar kağıdı yapmak (normal izin, sorulmaz) |

İnternet izni **yoktur**. Web aramaları tarayıcıya yönlendirilerek yapılır. Veriler yalnızca cihazda (`SharedPreferences`) tutulur, yedekleme kapalıdır (`allowBackup=false`).

---

## 🛠 Teknik bilgiler

| | |
|---|---|
| Dil | Kotlin 1.9.22 |
| Android Gradle Plugin | 8.2.2 |
| minSdk / targetSdk / compileSdk | 30 (Android 11) / 34 / 34 |
| Bağımlılık | `androidx.recyclerview:recyclerview:1.3.2` |
| Arayüz | Tamamen kodla oluşturulur (XML layout yok) |
| Paket | `io.github.idrisokys.myl` |

### Proje yapısı
```
MyLauncher/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/io/github/idrisokys/myl/MainActivity.kt   # tüm mantık + FolderDrawable
        ├── java/io/github/idrisokys/myl/SetWallpaperActivity.kt   # Paylaş menüsü: duvar kağıdı yap
        └── res/
            ├── drawable/   # uygulama ikonu ve düzenleme düğmesi
            ├── mipmap-anydpi/
            └── values/styles.xml                   # duvar kağıdı temalı pencere
```

---

## 🚀 Derleme ve kurulum

1. Projeyi **Android Studio**'da açın (Hedgehog veya daha yeni önerilir, JDK 17).
2. Gradle senkronizasyonu bitince bağlı telefonda **Run ▶** ile çalıştırın.
3. APK üretmek için: **Build → Build Bundle(s) / APK(s) → Build APK(s)**
   Kendi anahtarınızla imzalamak için aşağıdaki **Release APK imzalama** bölümüne bakın.
4. Komut satırından: `gradle wrapper` ardından `./gradlew assembleRelease`
5. İlk açılışta MyL'yi **varsayılan ana ekran uygulaması** olarak seçin.

---

## 🔏 Release APK imzalama

1. Bir kez keystore oluştur (`.jks` dosyasını ve şifreleri sakla, bunlar olmadan uygulamayı güncelleyemezsin):
   ```
   keytool -genkeypair -v -keystore myl-release.jks -alias myl -keyalg RSA -keysize 2048 -validity 10000
   ```
2. `myl-release.jks` dosyasını proje köküne koy, `keystore.properties.example` dosyasını `keystore.properties` olarak kopyalayıp bilgilerini doldur.
3. Derle: `./gradlew assembleRelease`
   Çıktı: `app/build/outputs/apk/release/app-release.apk`
4. Her yeni sürümde `app/build.gradle.kts` içindeki `versionCode` değerini artır.

`keystore.properties` yoksa release APK debug anahtarıyla imzalanır. `keystore.properties` ve `*.jks` zaten `.gitignore` içinde; asla paylaşma ya da GitHub'a yükleme.

---

## ⚠️ Bilinen sınırlamalar

- Tek sayfa: yatay kaydırılan çoklu ana ekran yoktur
- Widget desteği yoktur
- Simge paketi / özel tema desteği yoktur
- Çalışma profili ve çoklu kullanıcı uygulamaları listelenmez (yalnızca ana kullanıcı)
- Animasyonlu simgeler (ör. canlı takvim/saat) statik gösterilir
- Bazı üreticilerde (ör. ColorOS) paket olayları kaçabildiği için ana ekrana her dönüşte hızlı bir doğrulama yapılır

---

## 📄 Lisans

Kişisel proje. Dilediğiniz gibi kullanabilir ve değiştirebilirsiniz.
