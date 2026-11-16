package github.idrisokys.myl

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * ColorOS 13 (Android 13) ayarları için yerel arama dizini.
 *
 * Her kayıt: Türkçe ad, İngilizce ad, ek anahtar sözcükler ve sırayla denenecek hedefler.
 * Hedef biçimi:
 *   "a:<intent action>"      -> standart Android Ayarlar eylemi (ColorOS'ta da çalışır)
 *   "c:<paket>/<sınıf>"      -> ColorOS'a özel etkinlik (cihazda yoksa sıradaki denenir)
 * Hiçbir hedef açılamazsa ana Ayarlar ekranı açılır.
 */
object SettingsIndex {

    class Item(val tr: String, val en: String, val keys: String, val targets: List<String>)

    private fun s(tr: String, en: String, keys: String, vararg t: String) = Item(tr, en, keys, t.toList())

    val items: List<Item> = listOf(
        // --- Ağ ve bağlantılar ---
        s("Wi-Fi", "Wi-Fi", "wifi kablosuz ag internet sifre", "a:android.settings.WIFI_SETTINGS"),
        s("Bluetooth", "Bluetooth", "mavi dis eslestir kulaklik", "a:android.settings.BLUETOOTH_SETTINGS"),
        s("SIM ve mobil veri", "SIM & mobile data", "sim hat mobil veri operator roaming dolasim", "a:android.settings.NETWORK_OPERATOR_SETTINGS", "a:android.settings.DATA_ROAMING_SETTINGS", "a:android.settings.WIRELESS_SETTINGS"),
        s("Veri kullanımı", "Data usage", "kota trafik mobil veri kullanim", "a:android.settings.DATA_USAGE_SETTINGS", "a:android.settings.WIRELESS_SETTINGS"),
        s("Uçak modu", "Airplane mode", "ucak modu ucus flight", "a:android.settings.AIRPLANE_MODE_SETTINGS"),
        s("Kişisel hotspot", "Personal hotspot", "hotspot internet paylas tethering baglanti noktasi", "c:com.android.settings/com.android.settings.Settings\$TetherSettingsActivity", "c:com.android.settings/com.android.settings.TetherSettings", "a:android.settings.WIRELESS_SETTINGS"),
        s("VPN", "VPN", "vpn sanal ozel ag", "a:android.settings.VPN_SETTINGS", "a:android.settings.WIRELESS_SETTINGS"),
        s("Özel DNS", "Private DNS", "dns ozel alan adi", "a:android.settings.WIRELESS_SETTINGS"),
        s("NFC", "NFC", "nfc temassiz odeme", "a:android.settings.NFC_SETTINGS"),
        s("Ekran yansıtma", "Cast", "cast yansit ekran aktar", "a:android.settings.CAST_SETTINGS"),
        s("Yazdırma", "Printing", "yazici yazdir print", "a:android.settings.ACTION_PRINT_SETTINGS"),

        // --- Ekran ve kişiselleştirme ---
        s("Ekran ve parlaklık", "Display & brightness", "ekran parlaklik karanlik mod koyu tema dark yenileme hizi yazi tipi boyutu zaman asimi uyku", "a:android.settings.DISPLAY_SETTINGS"),
        s("Karanlık mod", "Dark mode", "karanlik koyu tema dark gece", "a:android.settings.DISPLAY_SETTINGS"),
        s("Ekran zaman aşımı", "Screen timeout", "ekran zaman asimi uyku kapanma sure", "a:android.settings.DISPLAY_SETTINGS"),
        s("Yazı tipi boyutu", "Font size", "yazi tipi font boyut buyuk kucuk", "a:android.settings.DISPLAY_SETTINGS", "a:android.settings.ACCESSIBILITY_SETTINGS"),
        s("Duvar kağıdı", "Wallpaper", "duvar kagidi arka plan wallpaper", "a:android.intent.action.SET_WALLPAPER", "a:android.settings.DISPLAY_SETTINGS"),
        s("Varsayılan ana ekran", "Default home app", "ana ekran baslatici launcher varsayilan", "a:android.settings.HOME_SETTINGS"),

        // --- Ses ve bildirimler ---
        s("Ses ve titreşim", "Sound & vibration", "ses titresim zil sesi medya sesi alarm sessiz", "a:android.settings.SOUND_SETTINGS"),
        s("Rahatsız etmeyin", "Do Not Disturb", "rahatsiz etme dnd sessiz mod odak", "a:android.settings.ZEN_MODE_SETTINGS", "a:android.settings.SOUND_SETTINGS"),
        s("Bildirimler", "Notifications", "bildirim durum cubugu uygulama bildirimleri", "a:android.settings.ALL_APPS_NOTIFICATION_SETTINGS", "a:android.settings.SOUND_SETTINGS"),
        s("Bildirim erişimi", "Notification access", "bildirim erisimi dinleyici", "a:android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"),

        // --- Pil ---
        s("Pil", "Battery", "pil sarj batarya enerji tasarruf kullanim", "a:android.intent.action.POWER_USAGE_SUMMARY", "a:android.settings.BATTERY_SAVER_SETTINGS"),
        s("Pil tasarrufu", "Battery saver", "pil tasarruf guc tasarrufu", "a:android.settings.BATTERY_SAVER_SETTINGS"),
        s("Pil optimizasyonu", "Battery optimization", "pil optimizasyon arka plan kisitlama", "a:android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"),
        s("Otomatik başlatma", "Auto-start", "otomatik baslat autostart arka plan acilista calistir",
            "c:com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "c:com.coloros.safecenter/com.coloros.safecenter.startupapp.StartupAppListActivity",
            "c:com.oppo.safe/com.oppo.safe.permission.startup.StartupAppListActivity",
            "a:android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"),

        // --- Depolama ve uygulamalar ---
        s("Depolama", "Storage", "depolama hafiza alan bellek temizle", "a:android.settings.INTERNAL_STORAGE_SETTINGS", "a:android.settings.MEMORY_CARD_SETTINGS"),
        s("Uygulamalar", "Apps", "uygulama yonetimi app yukle kaldir", "a:android.settings.MANAGE_ALL_APPLICATIONS_SETTINGS", "a:android.settings.APPLICATION_SETTINGS"),
        s("Varsayılan uygulamalar", "Default apps", "varsayilan uygulama tarayici arama sms", "a:android.settings.MANAGE_DEFAULT_APPS_SETTINGS"),
        s("Diğer uygulamaların üzerinde göster", "Display over other apps", "ustunde goster overlay cizim", "a:android.settings.action.MANAGE_OVERLAY_PERMISSION"),
        s("Tüm dosyalara erişim", "All files access", "tum dosya erisim depolama izin", "a:android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION"),
        s("Kullanım erişimi", "Usage access", "kullanim erisimi uygulama kullanim", "a:android.settings.USAGE_ACCESS_SETTINGS"),

        // --- Gizlilik ve güvenlik ---
        s("Güvenlik", "Security", "guvenlik ekran kilidi sifre pin desen", "a:android.settings.SECURITY_SETTINGS"),
        s("Ekran kilidi", "Screen lock", "ekran kilidi sifre pin desen kilit", "a:android.app.action.SET_NEW_PASSWORD", "a:android.settings.SECURITY_SETTINGS"),
        s("Parmak izi", "Fingerprint", "parmak izi biyometrik", "a:android.settings.FINGERPRINT_ENROLL", "a:android.settings.BIOMETRIC_ENROLL", "a:android.settings.SECURITY_SETTINGS"),
        s("Yüz tanıma", "Face unlock", "yuz tanima yuz kilidi biyometrik", "a:android.settings.BIOMETRIC_ENROLL", "a:android.settings.SECURITY_SETTINGS"),
        s("Gizlilik", "Privacy", "gizlilik izin reklam kimligi", "a:android.settings.PRIVACY_SETTINGS", "a:android.settings.SECURITY_SETTINGS"),
        s("İzin yöneticisi", "Permission manager", "izin yoneticisi uygulama izinleri kamera mikrofon konum", "a:android.intent.action.MANAGE_PERMISSIONS", "a:android.settings.PRIVACY_SETTINGS"),
        s("Konum", "Location", "konum gps yer", "a:android.settings.LOCATION_SOURCE_SETTINGS"),

        // --- Sistem ---
        s("Hesaplar ve senkronizasyon", "Accounts & sync", "hesap senkron google eposta yedek", "a:android.settings.SYNC_SETTINGS"),
        s("Dil", "Language", "dil bolge locale", "a:android.settings.LOCALE_SETTINGS"),
        s("Klavye", "Keyboard", "klavye giris yontemi yazi", "a:android.settings.INPUT_METHOD_SETTINGS"),
        s("Tarih ve saat", "Date & time", "tarih saat saat dilimi otomatik", "a:android.settings.DATE_SETTINGS"),
        s("Erişilebilirlik", "Accessibility", "erisilebilirlik kolaylik talkback buyutec", "a:android.settings.ACCESSIBILITY_SETTINGS"),
        s("Telefon hakkında", "About phone", "telefon hakkinda surum model imei coloros android", "a:android.settings.DEVICE_INFO_SETTINGS"),
        s("Geliştirici seçenekleri", "Developer options", "gelistirici usb hata ayiklama adb", "a:android.settings.APPLICATION_DEVELOPMENT_SETTINGS", "a:android.settings.DEVICE_INFO_SETTINGS"),
        s("Yazılım güncellemesi", "Software update", "guncelleme yazilim ota sistem surumu",
            "c:com.oplus.ota/com.oplus.ota.ui.OtaUpdateActivity",
            "c:com.coloros.sau/com.coloros.sau.ui.SauActivity",
            "a:android.settings.DEVICE_INFO_SETTINGS")
    )

    private fun fold(x: String): String {
        val sb = StringBuilder(x.length)
        for (ch in x.lowercase(java.util.Locale.forLanguageTag("tr-TR"))) {
            sb.append(when (ch) { 'ı' -> 'i'; 'ö' -> 'o'; 'ü' -> 'u'; 'ş' -> 's'; 'ç' -> 'c'; 'ğ' -> 'g'; else -> ch })
        }
        return sb.toString()
    }

    private class Entry(val item: Item, val titleTr: String, val titleEn: String, val hay: String)

    private val entries: List<Entry> by lazy {
        items.map { Entry(it, fold(it.tr), fold(it.en), fold(it.tr + " " + it.en + " " + it.keys)) }
    }

    /** [q] ile eşleşen ayarları en iyi eşleşme başta olacak şekilde döndürür. */
    fun search(q: String, limit: Int): List<Item> {
        val fq = fold(q.trim())
        if (fq.length < 2) return emptyList()
        val tokens = fq.split(' ').filter { it.isNotEmpty() }
        return entries.mapNotNull { e ->
            if (!tokens.all { e.hay.contains(it) }) return@mapNotNull null
            val score = when {
                e.titleTr.startsWith(fq) || e.titleEn.startsWith(fq) -> 0
                e.titleTr.contains(fq) || e.titleEn.contains(fq) -> 1
                else -> 2
            }
            score to e.item
        }.sortedBy { it.first }.take(limit).map { it.second }
    }

    /** Hedefleri sırayla dener; hiçbiri açılmazsa ana Ayarlar'ı açar. */
    fun open(ctx: Context, item: Item): Boolean {
        for (t in item.targets) {
            val i = build(t) ?: continue
            try {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: Exception) { /* sıradakini dene */ }
        }
        return try {
            ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) { false }
    }

    private fun build(t: String): Intent? = when {
        t.startsWith("a:") -> Intent(t.substring(2))
        t.startsWith("c:") -> t.substring(2).split('/').takeIf { it.size == 2 }
            ?.let { Intent().setComponent(ComponentName(it[0], it[1])) }
        else -> null
    }
}
