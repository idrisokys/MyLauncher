# MyL 1.14 — temizlik (kullanılmayan kod)

- Hiçbir yerden çağrılmayan kod silindi: `MainActivity.infoIcon()`, `MainActivity.openStorageFolder()`, `INFO_EMAIL`, `INFO_GITHUB`, `Store.documentUri()`, `FilesActivity.openList()`, `FilesActivity.zipSelected()`, `AUD`, `DOC`, `VID` (Dosyalarım'da).
- Kullanılmayan 34 `import` temizlendi (`NotesActivity`, `QuickShare`, `QsPermActivity`, `FilesActivity`, `GalleryActivity`, `MainActivity`).
- Kullanılmayan kaynaklar silindi: `drawable-nodpi/releases_icon.png` (81 KB), `drawable/ic_menu_info.xml`.
- Davranış değişmedi; yalnızca statik analiz yapıldı, Gradle derlemesi bu ortamda denenmedi.

# MyL 1.14 (hareketler, Galerim, Resmi Editle)

- Ana ekran: kalem = dokun Notlarım / basılı tut düzenleme modu (mod açıkken dokunmak modu kapatır); kamera = dokun Galerim / basılı tut kamera; ⚙ menüsünden "Link klasörü" satırı ve `openLinkFolder()` silindi.
- Dosyalarım: basılı tutmak artık (resim/video silmek yerine) ad değiştirir; `removeMedia` kaldırıldı. Resimler `GalleryActivity.EXTRA_FILE` ile Galerim'in tam ekran gezgininde açılır (aynı klasördeki resim/videolar kaydırılabilir; gezgin kapanınca Galerim kapanır, Dosyalarım'a dönülür).
- Galerim: basılı tutma (ızgara ve tam ekran) artık `moveToOnemsiz()` ile resmi `<depolama>/.onemsiz` klasörüne taşır (`.nomedia` eklenir, aynı ad varsa `ad_1`…). Seçim modundaki çöp kutusu da onay sormadan seçilenleri aynı şekilde `.onemsiz`'e taşır; Galerim artık hiçbir yerde kalıcı silmez.
- Yeni `EditShareActivity` ("Resmi Editle"): Paylaş menüsünden gelen `image/*` (jpg/png/webp) `EditActivity` ile açılır; okuma izni düzenleyiciye aktarılır.

# MyL 1.13 (Hızlı Paylaş entegrasyonu)

- QuickShare, `github.idrisokys.myl.qs` paketine taşındı; ana ekranı, bilgi kutusu, "Klasör Aç/Kapat" düğmeleri, TV banner'ı ve harici dosya yöneticisi (`com.alphainventor.filemanager`) bağımlılığı kaldırıldı. Geriye yalnızca ana ekrandaki Hızlı Paylaş düğmesi (`MainActivity.qsBtn`: dokun = aç, basılı tut = kapat), Paylaş menüsü girişi (`QsShareActivity`, açık tüm cihazlara gönderir), alıcı servisi (`ReceiverService`), açılışta yeniden başlatma (`BootReceiver`) ve düğme için arayüzsüz izin etkinliği (`QsPermActivity`) kaldı. Hızlı Ayarlar kutucuğu (`QsTile`) ve Paylaş menüsündeki cihaz adlı doğrudan hedefler (share-target / dinamik kısayollar) kaldırıldı.
- **`INTERNET` izni geri eklendi** (önceki `tools:node="remove"` satırı silindi): yerel ağ TCP soketleri bu izin olmadan açılamaz. MyL'nin başka hiçbir bölümü ağ kullanmaz.
- Alıcı varsayılan olarak **kapalı** (`qs_cfg/on = false`); yalnızca kutucuk açıkken dinler. Önceki QuickShare'de varsayılan açıktı.
- Bildirim izni artık zorunlu değil (reddedilirse servis yine çalışır, bildirim görünmez); gerekli olan tek izin "Tüm dosyalara erişim" (Notlarım/Dosyalarım ile ortak).
- Alınan dosyalar `<depolama>/Link` klasörüne yazılır; bildirime dokunmak Dosyalarım'da bu klasörü açar.
- Paylaş menüsündeki cihaz kısayolları için `MainActivity`'ye `shortcuts.xml` (share-target) bağlandı.
- Not: alıcı, aynı ağdaki herhangi bir cihazdan onaysız dosya kabul eder (özgün QuickShare tasarımı); kutucuğu yalnızca güvendiğiniz ağlarda açın.

# MyL 1.11 (internet erişimi kaldırıldı)

- `Store.fetchTitle` (Notlarım'a link kaydederken sayfa başlığını çeken tek ağ kodu) ve ilgili `HttpURLConnection` kodu silindi. Başlık artık paylaşılan konudan, metinden ya da sitenin adresinden alınır.
- Manifestten `INTERNET` izni `tools:node="remove"` ile kaldırıldı; kütüphaneler eklese bile birleşik manifestte yer almaz. Uygulama internete erişemez.

# MyL 1.11 (sahte sürüm uyarısı)

- Düzenleyici açılırken çıkan "You are using a fake version of the app…" uyarısı commons'ın `BaseSimpleActivity.onCreate` denetiminden geliyordu (paket adı `org.fossify.` ile başlamıyor). `EditorBaseActivity`, yalnızca `super.onCreate` sürecinde `getPackageName()` için `org.fossify.editor` bildirir; sonrasında gerçek paket adı döner.

# MyL 1.11 (Android 10+ sağlamlaştırma)

- Düzenleyici izni: Android 11+ "tüm dosyalara erişim", Android 10'da `WRITE_EXTERNAL_STORAGE` varsa ek izin istemeden açılır; yoksa commons izin akışı çalışır.
- Filtre kütüphanesinin (`NativeImageProcessor`) yüklenememesi artık çökme yapmaz; editör açılır.
- Düzenleyiciden OK dönünce liste her durumda yeniden taranır.
- R8: editör, filtre ve kırpma kütüphaneleri için `proguard-rules.pro` kuralları eklendi.

# MyL 1.11 (düzenleyici izni)

- Galerim'de sihirli değnek "depolama izni gerekiyor" diyordu: düzenleyici (commons) Android 13+'ta ayrıca `READ_MEDIA_IMAGES` istiyordu. `EditActivity` artık "tüm dosyalara erişim" izni (`isExternalStorageManager`) varsa ek izin istemeden açılıyor; yoksa eski istek akışı çalışır. İzin reddedilince `initEditActivity()` artık çağrılmıyor.

# MyL 1.11 (derleme düzeltmesi)

- Manifest birleştirme hatası: `androidphotofilters` kendi `<application>` öğesinde `allowBackup=true` ve `label=@string/app_name` tanımlıyordu. Uygulamanın `<application>` öğesine `tools:replace` eklendi; uygulamanın değerleri (allowBackup=false, label=MyL) geçerli.

- Manifest birleştirme hatası: `basic-editor` `WRITE_EXTERNAL_STORAGE` iznini `maxSdkVersion=28`, uygulama `29` ile tanımlıyordu. Uygulamadaki iki depolama izninde `tools:replace="android:maxSdkVersion"` kullanıldı; uygulamanın değeri (29) geçerli.

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

## Notlarım: eşitleme ve (i) düğmesi
- Notlarım'da sağ üstteki bilgi (i) simgesine basılı tutunca Link klasörünü `.onemsiz`'e taşıyan eylem ve `moveAllToOnemsiz()` kaldırıldı; simge artık yalnızca dokununca bilgi menüsünü açar.
- Eşitleme her cihazın ana dizinindeki `Link` klasörü üzerinden yürür: başlamadan önce `Store.root()` ile klasör hazırlanır (yoksa oluşturulur, eski `QuickShare/Link` taşınır). Eşitleme düğmesi Notlarım ekranında kalır.
- Karşı cihaz (ör. Mac) için sağlamlaştırma: dosya adları NFC'ye çevrilerek karşılaştırılır (macOS NFD adları Türkçe harflerde kopya/eksik üretirdi), saniye cinsinden gelen zamanlar milisaniyeye çevrilir, karşı cihaz `/m` uç noktasını desteklemiyorsa anlaşılır hata gösterilir.
- Eşitleme düğmesi ve penceresi Notlarım'dan kaldırıldı; Hızlı Paylaş penceresine (`SendPopup`, cihaz listesi) sağ üstte ⇄ simgesi eklendi. Simge aynı pencerede cihaz kutucuklarını ve üç yöntemi (Bu cihaza göre / Karşıdaki cihaza göre / İki cihazda aynı olsun) gösterir; eşitleme `NoteSync.run` ile yürür.
