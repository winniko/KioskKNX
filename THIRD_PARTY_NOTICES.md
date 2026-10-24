# Componenti di terze parti

KioskKNX è distribuito con licenza **GNU AGPL v3** (vedi [LICENSE](LICENSE)) e include le seguenti librerie open source.
L'APK contiene queste librerie in forma compilata. I loro sorgenti sono disponibili agli indirizzi indicati.

| Libreria | Versione | Licenza | Sorgente |
|---|---|---|---|
| liblinphone / linphone-sdk-android (Belledonne Communications) | 5.4.128 | GNU AGPL v3 | https://gitlab.linphone.org/BC/public/linphone-sdk |
| GeckoView (Mozilla) | 140.0 | Mozilla Public License 2.0 | https://hg.mozilla.org/mozilla-central (cartella `mobile/android/geckoview`) |
| AndroidX Media3 (ExoPlayer, RTSP) | 1.4.1 | Apache 2.0 | https://github.com/androidx/media |
| AndroidX Core, AppCompat, Lifecycle, CameraX | vedi `app/build.gradle.kts` | Apache 2.0 | https://android.googlesource.com/platform/frameworks/support |
| Material Components for Android | 1.12.0 | Apache 2.0 | https://github.com/material-components/material-components-android |
| NanoHTTPD | 2.3.1 | BSD 3-Clause | https://github.com/NanoHttpd/nanohttpd |
| OkHttp (Square) | 4.12.0 | Apache 2.0 | https://github.com/square/okhttp |
| okhttp-digest | 3.1.0 | Apache 2.0 | https://github.com/rburgst/okhttp-digest |
| Kotlin, kotlinx.coroutines, kotlinx.serialization (JetBrains) | 2.0.21 / 1.8.1 / 1.6.3 | Apache 2.0 | https://github.com/JetBrains/kotlin |

Note:
- **liblinphone** è AGPL v3: chi distribuisce l'APK (anche gratis) deve rendere disponibile il codice sorgente completo dell'app. Questo repository svolge quella funzione. L'app mostra il link al sorgente nell'informativa iniziale, nel menu "Stato del pannello" e nella pagina `http://IP:8080/` dell'API locale.
- **GeckoView** (MPL 2.0) è usato senza modifiche così come distribuito da Mozilla.
- Gli strumenti usati solo per i test (ffmpeg, emulatore Android) non fanno parte dell'APK.
