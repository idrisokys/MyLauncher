# basic-editor

Gallery (Fossify) uygulamasındaki **Temel Düzenleyici (Basic Editor)**'ün bağımsız Android kütüphane modülü hâli.

Özellikler: filtreler, kırpma (serbest / 1:1 / 4:3 / 16:9 / özel oran), döndürme, aynalama,
yeniden boyutlandırma, serbest çizim (renk, fırça boyutu, geri al), farklı kaydet / üzerine yaz, paylaş.

## Kaynak -> hedef eşlemesi

| Gallery'de                                   | Burada                                              |
|----------------------------------------------|-----------------------------------------------------|
| `activities/EditActivity.kt`                 | `activities/EditActivity.kt` (open class)           |
| `activities/BaseCropActivity.kt`             | aynı + `EditorBaseActivity` (SimpleActivity yerine) |
| `adapters/FiltersAdapter.kt`                 | aynı                                                |
| `dialogs/{OtherAspectRatio,CustomAspectRatio,Resize,SaveAs}Dialog.kt` | aynı                   |
| `views/EditorDrawCanvas.kt`, `models/{FilterItem,PaintOptions}.kt`    | aynı                   |
| `helpers/{ColorModeHelper,FilterThumbnailsManager}.kt`                | aynı                   |
| `extensions/ExifUtils.kt`                    | aynı                                                |
| `Config` (lastEditor*, ultraHdr)             | `helpers/EditorConfig.kt`                           |
| `extensions/Activity.kt` içindeki yardımcılar | `extensions/EditorExtensions.kt`                   |
| `fixDateTaken(...)` (Gallery DB'sine bağlıydı) | `onImageSaved(path)` kancası                      |

Paket adı `org.fossify.gallery` -> `org.fossify.editor` oldu. Layout, menü, drawable ve
string'ler (55 dil dahil) `res/` altında.

## Kullanım

1. `settings-snippet.gradle.kts` içindeki adımları uygula (modülü ekle, JitPack deposunu ekle).
2. Host uygulamanın teması Fossify commons temasından türemeli (`AppTheme` -> `AppTheme.Base`),
   çünkü editör `BaseSimpleActivity` ve commons view'larını (MyAppBarLayout, MySeekBar...) kullanır.
3. Paylaş özelliği için commons'ın beklediği FileProvider host manifest'te tanımlı olmalı:
   authority `${applicationId}.provider`, paths dosyasında cache içinde `images/` klasörü.
4. Aç:

```kotlin
startActivity(EditActivity.newIntent(context, imageUri))                  // tam editör
startActivity(EditActivity.newIntent(context, imageUri, cropOnly = true)) // sadece kırp/döndür
```

5. Kayıttan sonra kendi işini yapmak için alt sınıf yaz ve manifest'e ekle:

```kotlin
class MyEditActivity : EditActivity() {
    override fun onImageSaved(path: String) { /* DB güncelle, vs. */ }
}
```

## Sistem düzenleyicisi olarak kaydetmek istersen

Orijinal manifest'teki iki intent-filter'ı (`android.intent.action.EDIT` ve
`com.android.camera.action.CROP`, `image/*`) kendi manifest'ine, activity'nin altına kopyala
ve `android:exported="true"` yap.

## Notlar

- Bu çıktı burada **derlenmedi** (ortamda Android SDK / ağ erişimi yok); kaynaklar orijinal
  koddan mekanik olarak taşındı ve tüm `R` referansları statik olarak doğrulandı.
  İlk derlemede küçük import düzeltmeleri gerekebilir.
- Orijinal proje GPL-3.0 lisanslı (bkz. `LICENSE`); bu modülü kullanan proje de GPL-3.0 uyumlu olmalı.
- Sürümler orijinal `libs.versions.toml` ile aynı: commons 7.1.3, image-cropper 4.7.0, minSdk 26, compileSdk 36.
