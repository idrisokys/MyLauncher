# Nuvio arama yaması (MyL'den "Nuvio'da ara" için)

Bu klasör MyL derlemesine dahil değildir (Gradle modülü değil). Nuvio'nun kendi reposuna (NuvioMedia/NuvioMobile, cmp-rewrite) uygulanır.

| Dosya | Nuvio reposunda yer |
|---|---|
| SearchDeepLinkRequests.kt | composeApp/src/commonMain/kotlin/com/nuvio/app/features/search/ (YENİ) |
| SearchScreen.kt | features/search/ içindeki aynı adlı dosyanın yerine |
| AppUrlBridge.kt | core/deeplink/ içindeki aynı adlı dosyanın yerine |
| MainAppContent.kt | com/nuvio/app/ içindeki aynı adlı dosyanın yerine |

Derleme: `./gradlew :androidApp:assembleFullDebug`
MyL, `nuvio://search?query=...` bağlantısını com.nuvio.app veya com.nuviodebug.com paketine gönderir.
