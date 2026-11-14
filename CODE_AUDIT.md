# MyL 1.11 (derleme düzeltmesi)

- `checkReleaseDuplicateClasses` hatası: `androidphotofilters` bağımlılığı eski `com.android.support:support-compat:25.3.0` getiriyordu; `androidx.core` ile aynı sınıfları içerdiği için çakışıyordu. `app` ve `basic-editor` modüllerinde `com.android.support` grubu bağımlılıklardan çıkarıldı.

# MyL 1.11

- Ana ekrandaki ayarlar (dişli) simgesine basılı tutunca Download klasörü artık sistemin İndirilenler ekranı yerine kendi **Dosyalarım** bölümünde açılır (`openDownloads()` → `FilesActivity.EXTRA_PATH`). Sürüm `1.11 (versionCode 14)`.

# MyL 1.10 — Kod denetimi ve değişiklikler

## 1.10 (Galerim'e sihirli değnek / temel düzenleyici — Gradle derlemesi denenmedi)

- Fossify **basic-editor** modülü projeye `basic-editor/` olarak eklendi (`settings.gradle.kts` → `include(":basic-editor")`, JitPack deposu eklendi, `app` ona bağlandı). Modülde AGP 8.x için `org.jetbrains.kotlin.android` eklentisi etkinleştirildi.
- Galerim tam ekran gezgininin üst barında **Paylaş'ın soluna sihirli değnek** düğmesi var (`Icons.magic()`). Basınca resim düzenleyicide açılır: filtre, kırpma/döndürme/aynalama, yeniden boyutlandırma, çizim, farklı kaydet / üzerine yaz.
- Düzenleyiciden **geri oku** (üst çubuk) veya sistem geri tuşu Galerim'in tam ekran gezginine döndürür. Kaydedilince gezgindeki resim yenilenir, küçük resim önbelleği temizlenir ve liste yeniden taranır ("farklı kaydet" ile oluşan yeni dosya listede görünür).
- Düğme yalnızca jpg / jpeg / png / webp için görünür (video, gif, svg düzenlenmez).
- Manifest: `org.fossify.editor.activities.EditActivity` (tema `EditorTheme`, parent `AppTheme.Base`) ve düzenleyicinin Paylaş düğmesi için `<applicationId>.provider` FileProvider'ı (`res/xml/provider_paths.xml`) eklendi.
- `EditActivity`'den commons'ın `checkAppSideloading()` denetimi kaldırıldı (elle kurulan uygulamada uyarı çıkmasın).
- Android 13+ cihazlarda düzenleyici ilk açılışta commons üzerinden "fotoğraflara erişim" iznini isteyebilir (Galerim'in kullandığı tüm dosyalara erişimden ayrı bir izindir).
- Sürüm `1.10 (versionCode 13)`.
- Lisans notu: `basic-editor/` GPL-3.0'dır (bkz. `basic-editor/LICENSE`); bu modülü içeren uygulama da GPL-3.0 uyumlu olmalıdır.

# MyL 1.9 — Kod denetimi ve değişiklikler

## 1.9 (Dosyalarım ve Galerim birleştirildi — Gradle derlemesi denenmedi)

- Ayrı uygulama olan **Dosyalarım** (dosya yöneticisi) ve **Galerim** (resim/video galerisi) bu uygulamanın bölümleri oldu; Notlarım gibi kendi görevinde çalışan ayrı ekranlar (`files/FilesActivity`, `gallery/GalleryActivity`) ve ana ekran ızgarasında kendi simgeleri var.
- Ortak kaynaklar `common/` paketinde: `Icons` (yol tabanlı simgeler), `KFileProvider` (tek içerik sağlayıcı, `<applicationId>.fp`), `StoragePerm` (tüm dosyalara erişim izni, tek yerden), `uniqueFile`, `promptText`.
- Ortak izinler: eklenen tek yeni izin `REQUEST_INSTALL_PACKAGES` (Dosyalarım'da APK'ya dokununca yükleme). Diğerleri (`MANAGE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`, `REQUEST_DELETE_PACKAGES`) zaten vardı.
- Yeni bağımlılık: `androidx.viewpager:viewpager:1.0.0` (Galerim tam ekran gezgini).
- Dosyalarım artık Paylaş menüsünde (`*/*`) görünür ve gelen dosyaları seçilen klasöre kaydeder.
- Galerim: kalem = seçim modu (basılı tutup silme duraklar), çöp = seçilenleri sil, taşı simgesi = klasöre/yeni klasöre taşı.
- Eski bağımsız uygulamaların ayarları (ana ekrana sabitlenen klasörler) bu uygulamaya taşınmaz; yeniden eklenmelidir.
- Ana ekran arama çubuğunun soluna kamera düğmesi eklendi: dokun = varsayılan kamera, basılı tut = Galerim.
- Ayarlar menüsüne klasör simgesi eklendi: ana dizindeki Link klasörünü Dosyalarım bölümünde açar (`FilesActivity.EXTRA_PATH`).
- Sürüm `1.9 (versionCode 12)`. Launcher, Notlarım ve duvar kağıdı kodlarına dokunulmadı.

# MyL 1.8 — Kod denetimi ve değişiklikler

## 1.8 (araç zinciri yükseltmesi — derleme denenmedi)

- Android Gradle Plugin 8.5.2 → 8.13.2, Gradle 8.7 → 8.13 (CI dosyası da güncellendi).
- Kotlin 1.9.24 → 2.2.20; Compose derleyicisi artık `org.jetbrains.kotlin.plugin.compose` eklentisiyle gelir (`composeOptions` kaldırıldı).
- `kotlinOptions` (Kotlin 2.2'de hata) → `kotlin { compilerOptions }`; `resourceConfigurations` → `androidResources.localeFilters`.
- Compose BOM 2024.06.00 → 2025.12.00, activity-compose 1.9.0 → 1.10.1, RecyclerView 1.3.2 → 1.4.0, AndroidX Core 1.12.0 → 1.16.0.
- `compileSdk` 34 → 36. `targetSdk` bilerek 34'te bırakıldı (davranış değişikliği riski).
- `NotesActivity.kt`: kaldırılan `animateItemPlacement()` → `animateItem()` (GitHub derlemesinde çıkan tek hata).
- Geri dönüş: bir önceki 1.7 zip'i çalışan sürümdür.

## 1.7 değişiklikleri

## 1.7 değişiklikleri

- Tanı kaydı özelliği tamamen kaldırıldı: menü satırı, çökme/yaşam döngüsü kayıt kodu, simge ve ilgili importlar. Eski sürümlerden kalan `life.txt` ve `crash.txt` dosyaları silinmez; zararsızdır.
- Arama sonuçlarından "Google'da ara" kaldırıldı; yerine ColorOS 13 ayarlarında yerel arama eklendi (`SettingsIndex.kt`).
- Uygulama listesi yenilemesinde beklenmedik bir hata (bellek hatası dahil) `loading` bayrağını takılı bırakmasın diye yakalama genişletildi.
- Sürüm `1.7 (versionCode 10)`.

## 1.6 değişiklikleri

## Yapılan değişiklikler

- `Store.search()` artık her aramada yeni 6 iş parçacıklı havuz kurup kapatmıyor. Uygulama boyunca yeniden kullanılan, en fazla 4 iş parçacıklı bir havuz kullanıyor.
- Arama sonuçları dosyaların tamamlanma sırasına göre toplanıyor. Önceki sürümde listenin başındaki yavaş bir dosya, daha önce tamamlanan diğer sonuçların ekrana gelmesini geciktirebiliyordu.
- Arama iptal edildiğinde bekleyen işler iptal ediliyor; böylece kullanıcı yeni sorguya geçtiğinde eski sorgunun gereksiz işi azaltılıyor.
- Notlarım ekranındaki yenileme sırasında arama, artık önceki `root` değişkenini değil yeni bulunan depolama kökünü kullanıyor.
- `CropWallpaperActivity.kt` içindeki kullanılmadığı doğrulanan iki import kaldırıldı.
- Uygulama sürümü `1.5 (versionCode 8)` → `1.6 (versionCode 9)` olarak artırıldı.

## Denetimde korunanlar

- Launcher, klasörler, kısayollar, sıralama ve yedekleme davranışları yeniden yazılmadı.
- Notlarım'ın `.url` / `.txt` dosya biçimi ve mevcut `<storage>/Link` veri konumu korundu.
- Mevcut Android Gradle Plugin / Kotlin / Compose sürümleri ve SDK hedefleri bu bakım paketinde değiştirilmedi. Bunları güncellemek, Kotlin 2.x Compose Compiler eklentisine ve daha yeni Android Gradle Plugin'e geçiş gerektiriyor; derleme ortamı olmadan bu çapta araç zinciri değişikliği yapmak çalışan projeyi bozma riski taşır.
- Kullanılan bağımlılıkların (`RecyclerView`, AndroidX Core, Compose Material 3, Compose Icons, Activity Compose) kaynak kodda karşılıkları bulunduğu için rastgele kaldırılmadı.

## Bilinen doğrulama sınırı

Bu çalışma ortamında Gradle ve Android SDK kurulu olmadığı için APK derlemesi ve cihaz/emülatör testi çalıştırılamadı. Kaynak değişiklikleri ve XML dosyaları statik olarak kontrol edildi; son doğrulama Mac üzerinde Android Studio ile `assembleRelease` ve gerçek cihaz testi olmalıdır.

## Mac üzerinde derleme

1. ZIP'i açın ve klasörü Android Studio ile açın.
2. JDK 17 seçili olsun.
3. Gradle Sync tamamlandıktan sonra Terminal'de proje kökünde `gradle assembleRelease` çalıştırın veya Android Studio'da **Build > Generate App Bundles or APKs > Build APKs** seçin.
4. APK yolu: `app/build/outputs/apk/release/`.

> Not: İmzalama anahtarınız varsa `keystore.properties` dosyasını proje köküne ekleyin. Dosya yoksa mevcut yapılandırma debug anahtarıyla imzalı release APK üretir; bunu Play Store'a yüklemek için kullanmayın.
