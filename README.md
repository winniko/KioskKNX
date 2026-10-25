# KioskKNX

App Android **open source e gratuita** per pannelli a muro e tablet usati con la domotica (KNX, Home Assistant, …).

## Funzioni
- **Kiosk browser** a schermo intero per il supervisore KNX o Home Assistant, con **motore Gecko (Firefox) integrato**: funziona anche sui pannelli con una WebView di sistema vecchia. In alternativa usa la WebView di sistema. Pagina offline con ritentativi automatici, watchdog, zoom pagina.
- **Videocitofono SIP** basato su [liblinphone](https://www.linphone.org/):
  - chiamate dirette IP o tramite centralino;
  - **video prima di rispondere** (early media SIP oppure RTSP/MJPEG di riserva);
  - apriporta via DTMF o HTTP (Shelly, Hikvision ISAPI, 2N);
  - pulsanti HTTP personalizzati;
  - storico chiamate con foto.
- **Dialer:** chiamate e videochiamate in uscita, numeri rapidi programmabili con codice installatore, tastiera in chiamata per **toni DTMF** e **inoltro/trasferimento**.
- **Popup telecamera RTSP/MJPEG** comandabile dalla domotica, compatibile con gli NVR Hikvision (lettore H.264 integrato per i flussi senza `sprop-parameter-sets`).
- **API REST locale:** schermo, pagina web, sintesi vocale in italiano, suoni, popup telecamera, chiamate, stato, configurazione, aggiornamento remoto.
- **Kiosk blindato** con Device Owner e Lock Task (facoltativo), avvio al boot, riavvio automatico dopo un crash.
- **Doppia rete LAN + Wi-Fi:** citofono SIP su una scheda e pagina web sull'altra (es. 2N sulla rete condominiale, Home Assistant sulla rete di casa), senza root.
- **Sensore di movimento** con la fotocamera frontale, salvaschermo anti burn-in, modalità notte, webhook in uscita.

Pensato per pannelli Hikvision DS-KH9570-WTE1/S e tablet Rockchip RK3566. Android 7.0 o superiore, ARM 32/64 bit.

## Per chi vuole solo usarla
1. Scarica l'APK dalla sezione **[Releases](../../releases)**:
   - `-64bit`: consigliato;
   - `-32bit`: per tablet a 32 bit;
   - `-universale`: va ovunque, ma è più grande.
2. Segui **[ISTRUZIONI_USO_E_API.md](ISTRUZIONI_USO_E_API.md)**: installazione, configurazione, citofoni 2N/Hikvision, manuale delle API.

Gli script `1_installa_apk_semplice.bat` e `2_attiva_kiosk_blindato.bat` (Windows) o `.sh` (Mac/Linux) installano l'APK e attivano il kiosk via ADB.

## Compilare da sorgente
Requisiti: **JDK 17** e **Android SDK** con platform 35 e build-tools 34. Android Studio non serve.

```bash
export JAVA_HOME=/percorso/jdk17
echo "sdk.dir=/percorso/android-sdk" > local.properties
./gradlew assembleRelease                      # universale (arm64 + armv7)
./gradlew assembleRelease -Pabi=arm64-v8a      # solo 64 bit
./gradlew assembleRelease -Pabi=armeabi-v7a    # solo 32 bit
./gradlew assembleDebug -Pemu                  # versione di test con x86_64 per l'emulatore
```

**Firma:** crea `keystore/keystore.properties` con `storeFile`, `storePassword`, `keyAlias` e `keyPassword`. Senza questo file l'APK release esce non firmato. La chiave non è nel repository: per pubblicare aggiornamenti installabili sopra le versioni esistenti va usata sempre la stessa.

**Link al sorgente:** l'indirizzo mostrato nell'app (obbligatorio per l'AGPL) si imposta in `gradle.properties` → `kioskknx.sourceUrl`.

## Test
La cartella `test/` contiene un finto citofono SIP, un finto telefono che risponde, un finto NVR RTSP Hikvision e lo script dei cicli di prova. Vedi [test/README_TEST.md](test/README_TEST.md).

## Struttura del codice
| Percorso (`app/src/main/java/it/kioskknx/app/`) | Contenuto |
|---|---|
| `sip/` | Stack SIP (liblinphone): chiamate in entrata/uscita, DTMF, inoltro |
| `web/` | Motori web (Gecko / WebView), pagina offline, watchdog |
| `video/` | Popup telecamera: Media3 RTSP, lettore H.264 integrato, MJPEG |
| `api/` | Server REST (NanoHTTPD) |
| `kiosk/` | Device Owner, Lock Task, avvio al boot, watchdog |
| `ui/` | Activity, overlay chiamata, dialer, impostazioni |
| `display/`, `motion/`, `media/`, `history/`, `update/`, `util/` | Moduli di supporto |

## Licenza
**GNU AGPL v3** (vedi [LICENSE](LICENSE)). Componenti di terze parti: vedi [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Il software è fornito "così com'è", **senza alcuna garanzia**. L'autore non risponde di malfunzionamenti, problemi di sicurezza o danni derivanti dall'uso sull'impianto domotico o sulla rete.
