# KioskKNX — Istruzioni d'uso e manuale API

KioskKNX trasforma un tablet o un pannello Android a muro in:
- **un pannello per il supervisore KNX** (mostra a schermo intero la pagina web del tuo server domotico),
- **un videocitofono** (risponde alle chiamate SIP di citofoni 2N, Hikvision e simili, con video e apriporta),
- **un dispositivo comandabile dalla domotica** (accendere lo schermo, far parlare il tablet, far comparire una telecamera, ecc.).

> Software libero e gratuito (licenza GNU AGPL v3), fornito "così com'è", senza garanzie.

---

## 1. Dove trovo l'app e come la installo

### 1.1 La cartella da usare
Tutto ciò che serve è nella cartella **`APK_PRONTO`**:

| File | A cosa serve |
|---|---|
| `KioskKNX-<versione>-universale.apk` | L'app da installare sul tablet (va bene per tutti) |
| `KioskKNX-<versione>-64bit.apk` / `-32bit.apk` | Versioni più leggere per un solo tipo di processore (utili se il tablet ha poco spazio) |

> **Aggiornare l'app:** installa il nuovo file **sopra** quello vecchio, senza disinstallare: le impostazioni restano. Prima cancella dal tablet (cartella Download o chiavetta) i vecchi file `KioskKNX*.apk`, per non aprire per errore una versione vecchia. Controlla poi la versione nel menu segreto → *Stato del pannello*.
| `1_installa_apk_semplice.bat` (Windows) / `.sh` (Mac/Linux) | Installa l'app e concede da solo tutti i permessi |
| `2_attiva_kiosk_blindato.bat` / `.sh` | Blocca il tablet su KioskKNX (modalità kiosk totale) |
| `strumenti\` | Programma `adb` già pronto per Windows (gli script lo trovano da soli) |
| `ISTRUZIONI_USO_E_API.md` | Questo manuale |

Puoi copiare l'intera cartella `APK_PRONTO` su una chiavetta o su un altro PC.

### 1.2 Metodo A — Senza PC (chiavetta USB, rete, e-mail)
1. Copia `KioskKNX.apk` sul tablet (chiavetta USB, cartella di rete, download dal browser...).
2. Aprilo con il file manager del tablet.
3. Se Android lo chiede, consenti **"Installa app sconosciute"** per il file manager.
4. Tocca **Installa**, poi **Apri**.
5. Al primo avvio l'app ti guida nel concedere i permessi (vedi capitolo 2).

**Pannello Hikvision DS-KH9570-WTE1/S:** Hikvision indica che i pannelli della serie 9 permettono di installare APK di terze parti dall'**interfaccia web del pannello** (apri nel browser del PC l'indirizzo IP del pannello, accedi con la password di attivazione e cerca la sezione dedicata alle applicazioni / APK). La posizione esatta del menu cambia con il firmware.

### 1.3 Metodo B — Dal PC con gli script (consigliato)
Gli script installano l'app e **concedono in automatico** Fotocamera, Microfono, Notifiche, "Mostra sopra le altre app" ed esclusione dal risparmio batteria.

**Preparazione del tablet (una sola volta):**
1. Sul tablet apri **Impostazioni → Informazioni sul tablet** e tocca **7 volte "Numero build"**: compare "Ora sei uno sviluppatore".
2. Vai in **Impostazioni → Sistema → Opzioni sviluppatore** e attiva **Debug USB**.
   - Molti pannelli RK3566 hanno anche **"Debug ADB via rete"** (porta 5555): così puoi usare il cavo di rete invece dell'USB.
3. Collega il tablet al PC con il cavo USB (oppure tieni a portata di mano il suo indirizzo IP).

**Installazione:**
1. Fai doppio clic su **`1_installa_apk_semplice.bat`** (su Mac/Linux: `1_installa_apk_semplice.sh`).
2. Scegli `1` (USB) oppure `2` (rete) e, se richiesto, scrivi l'IP del tablet.
3. Sul tablet compare **"Consentire il debug USB?"**: spunta *"Consenti sempre"* e tocca **Consenti**.
4. Attendi il messaggio **FATTO!**. L'app si apre da sola sul tablet.

### 1.4 Kiosk blindato (facoltativo)
Il kiosk blindato toglie barra delle notifiche, tasti Home/Indietro e impedisce di uscire dall'app. L'app si avvia da sola all'accensione.

1. **Requisito:** sul tablet **non devono esserci account** (Google, Samsung, ecc.). Rimuovili da *Impostazioni → Account*, oppure fai un ripristino di fabbrica e **salta** l'aggiunta dell'account Google.
2. Installa l'app (paragrafo 1.3).
3. Fai doppio clic su **`2_attiva_kiosk_blindato.bat`** e segui le istruzioni.

Se lo script segnala un errore, l'app continua a funzionare in normale modalità schermo intero.
Su alcuni pannelli del produttore (es. pannelli citofonici con app di sistema proprie) il blocco totale potrebbe non essere consentito dal firmware.

---

## 2. Primo avvio e configurazione

### 2.1 Informativa
Al primissimo avvio compare l'informativa legale. Spunta **"Ho letto e accetto integralmente le condizioni"** e premi **Conferma**.

### 2.2 Permessi
Compare la schermata **"Configurazione iniziale"**. Per ogni voce con ⬜ tocca **Concedi**:
- **Fotocamera**: sensore di movimento.
- **Microfono**: per parlare al citofono.
- **Mostra sopra le altre app**: fa comparire la chiamata e permette il riavvio automatico.
- **Nessuna ottimizzazione batteria**: impedisce ad Android di chiudere l'app.
- *(facoltativi)* Installazione aggiornamenti, App Home predefinita.

Premi **Continua**. Se hai usato gli script, quasi tutto risulta già ✅.

### 2.3 Aprire il menu impostazioni segreto
**Tieni il dito premuto per 10 secondi nell'angolo in basso a destra** dello schermo (zona invisibile, circa 1,5 cm × 1,5 cm). Negli ultimi 5 secondi compare al centro il conto alla rovescia *"Impostazioni tra 5… 4…"*; poi il tablet vibra e si apre il menu (o la richiesta del PIN, se l'hai impostato).

Il menu è diviso in **sezioni a tendina**: tocca un titolo per aprirlo (le altre si chiudono).

Nel menu, dopo ogni modifica, premi **Salva** in alto a destra.

### 2.4 Impostazioni principali (in ordine)
1. **Pagina web (supervisore KNX)** → *Motore web*: lascia **Automatico**. KioskKNX contiene un proprio browser moderno (**Gecko**, il motore di Firefox) e lo usa da solo quando il browser di sistema del tablet è vecchio: così Home Assistant e le dashboard moderne funzionano anche sui pannelli datati. Con Gecko usa indirizzi `http://` per i server in rete locale (i certificati HTTPS self-signed non sono accettati).
   *Indirizzo della pagina*: per esempio `http://192.168.1.10/visu` oppure `https://192.168.1.10:8443/`.
   Se la pagina chiede utente e password del browser, compilali qui.
   **Zoom pagina (%)**: ingrandisce o rimpicciolisce l'intera pagina (es. 125). Le pagine "responsive" come Home Assistant si riadattano con colonne più grandi. Nel menu "Stato" vedi quanto spazio vede la pagina (es. 1280x800), utile per progettare le dashboard.
2. **Videocitofono SIP** → vedi capitolo 3.
3. **Video pre-risposta** → URL RTSP o MJPEG del citofono (vedi capitolo 3). Usa **"Prova video"** per verificarlo.
4. **Serrature** → sequenza DTMF (es. `#1`) oppure comando HTTP.
5. **Schermo e salvaschermo** → dopo quanti secondi oscurare lo schermo.
6. **Sensore di movimento** → attivalo per riaccendere lo schermo quando qualcuno si avvicina.
7. **Server API locale** → porta (8080) ed eventuale **API key**.
8. **Kiosk e sicurezza** → imposta un **PIN** per il menu.

### 2.5 Sblocco e manutenzione (sezione "Sistema" del menu)
| Pulsante | Cosa fa |
|---|---|
| **Impostazioni Wi-Fi / Rete** / **Impostazioni Android** | Sospende il blocco kiosk e apre le impostazioni di Android. Premi **Home** per tornare a KioskKNX: il blocco si riattiva da solo. |
| **Esci dall'App** | Ferma tutti i servizi e chiude l'app. Se KioskKNX è la Home, compare la schermata "in pausa" con i pulsanti per riavviarla o scegliere un altro launcher. |
| **Disattiva Device Owner** | Rimuove il kiosk blindato e propone di disinstallare l'app. Non serve il PC. |
| **Riavvia app**, **Log**, **Permessi**, **Storico chiamate**, **Aggiorna app da URL** | Strumenti di manutenzione. |

**PIN dimenticato?** Se le API sono attive puoi cambiarlo dal PC (paragrafo 4.13, `/api/config`). In caso estremo serve un ripristino di fabbrica del tablet.

### 2.6 Backup della configurazione
Nella sezione **Backup configurazione**:
- **Esporta su file** / **Importa da file**: salva o carica `KioskKNX_config.json`, per esempio su una chiavetta.
- **Mostra / incolla JSON**: copia e incolla la configurazione fra due pannelli.
- In alternativa, via rete: `GET /api/config` per esportare e `POST /api/config` per importare (capitolo 4).

Comodo per configurare 10 pannelli uguali: ne configuri uno, esporti, importi sugli altri.

---

## 3. Configurazione del citofono (2N / Hikvision)

### 3.1 Due modi di collegarsi
| Modo | Come funziona | Quando sceglierlo |
|---|---|---|
| **Chiamata diretta IP** (P2P) | Il citofono chiama direttamente l'indirizzo IP del tablet | Impianti semplici, 1 citofono e pochi pannelli, nessun centralino |
| **Centralino (PBX)** | Citofono e tablet si registrano su un centralino (Asterisk/FreePBX, 3CX, Fritz!Box, Yeastar, ...) | Più pannelli che squillano insieme, telefoni, inoltro su smartphone |

**Lato tablet (menu → Videocitofono SIP):**
- *Chiamata diretta*: lascia attivo **"Accetta chiamate dirette IP"**. Per sicurezza scrivi in **"IP dei citofoni autorizzati"** l'IP del citofono, così il tablet rifiuta chiamate da altri indirizzi.
- *Centralino*: attiva **"Registra su centralino"** e compila server, porta (5060), utente/interno e password. Lo stato "registrato" compare in alto nel menu.
- **Porta SIP locale**: predefinita **5062**. Si usa una porta diversa da 5060 perché pannelli come l'Hikvision DS-KH9570 hanno già un'app citofono che usa la 5060.

**Indirizzo da chiamare per la chiamata diretta:**
```
sip:pannello@IP_DEL_TABLET:5062
esempio:  sip:pannello@192.168.1.60:5062
```
(la parte prima di `@` può essere qualunque nome)

### 3.2 Video prima di rispondere (fondamentale)
Il tablet mostra il video **appena il citofono suona**, prima di rispondere, in due modi:
1. **Early media SIP**: il tablet chiede al citofono il video già durante lo squillo (opzione *"Video prima di rispondere"*, attiva di default). Con i citofoni **2N** di solito funziona.
2. **Video RTSP/MJPEG di riserva**: se il video SIP non arriva entro 2,5 secondi (modalità *Automatico*), il tablet apre direttamente lo stream della telecamera del citofono. Con **Hikvision** è il metodo consigliato: imposta *"Quando usarlo" = Sempre*.

**Formati URL tipici (sostituisci IP, utente e password):**
| Citofono | URL |
|---|---|
| Hikvision (flusso principale) | `rtsp://admin:password@192.168.1.50:554/Streaming/Channels/101` |
| Hikvision (formato alternativo) | `rtsp://admin:password@192.168.1.50:554/ch1/main/av_stream` |
| Hikvision (flusso secondario, più leggero) | `rtsp://admin:password@192.168.1.50:554/Streaming/Channels/102` |
| 2N RTSP | `rtsp://192.168.1.51:554/h264_stream` |
| 2N MJPEG (API HTTP) | `http://utente:password@192.168.1.51/api/camera/snapshot?width=640&height=480&fps=10` |
| Telecamera generica MJPEG | `http://utente:password@IP/video.mjpg` |

Prova sempre l'URL con **VLC** sul PC (*Media → Apri flusso di rete*) e poi con il pulsante **"Prova video"** del menu. Il formato esatto dipende dal modello e dal firmware.
Le autenticazioni **Basic** e **Digest** sono supportate (Hikvision usa Digest).

### 3.3 Video in uscita disattivato
Di default il tablet **non invia** il video della propria fotocamera al citofono: riceve solo il video e scambia l'audio. Puoi cambiarlo con *"Invia il video della fotocamera del tablet"*.

### 3.4 Apriporta (Serratura 1 e 2)
Ogni serratura può funzionare in due modi:
- **DTMF** (in chiamata): il tablet invia una sequenza di toni.
  - Esempi: `#1`, `*1`, `00`, `1234#`. Usa il codice impostato nel citofono (2N: *Hardware → Interruttori → Codici*; Hikvision: codice di apertura/DTMF del posto esterno, se previsto dal modello).
  - **Modalità DTMF**: RFC 2833 (consigliata, va bene per quasi tutti), SIP INFO, in banda. Deve coincidere con quella del citofono o del centralino.
- **Comando HTTP**: il tablet chiama un indirizzo web. Funziona anche **senza rispondere** e dal popup telecamera.
  - **Shelly** (relè): metodo `GET`, URL `http://192.168.1.70/relay/0?turn=on&timer=2`
  - **Hikvision** (ISAPI): metodo `PUT`, URL `http://192.168.1.50/ISAPI/AccessControl/RemoteControl/door/1`, Content-Type `application/xml`, Body `<RemoteControlDoor><cmd>open</cmd></RemoteControlDoor>`, utente e password dell'amministratore del citofono (autenticazione Digest automatica).
  - **2N** (API HTTP): `GET http://utente:password@192.168.1.51/api/switch/ctrl?switch=1&action=trigger`

**Opzioni utili:**
- *Apertura rapida senza rispondere*: se c'è un comando HTTP lo usa; altrimenti il tablet risponde da solo, invia il DTMF e riaggancia.
- *Riaggancia dopo l'apertura*: chiude la chiamata qualche secondo dopo aver aperto.

### 3.5 Durante la chiamata
- **Cornetta verde**: risponde (audio bidirezionale con cancellazione dell'eco).
- **Cornetta rossa**: rifiuta o chiude.
- **Serratura 1 / 2**: aprono la porta.
- **Pulsanti personalizzati** (riga sopra): luci, cancello, scenari, configurabili nel menu.
- **Tieni per parlare**: compare solo se hai attivato *"Premi per parlare"* (utile se c'è molta eco).

A fine chiamata l'overlay si chiude e la pagina KNX viene ricaricata (se l'opzione è attiva). Nello **Storico chiamate** trovi data, esito e foto del visitatore.

### 3.6 Problemi audio: eco e volume
1. Lascia attiva la **cancellazione dell'eco** e premi **"Calibra eco"** in una stanza silenziosa.
2. Se senti ancora eco, attiva il **Limitatore d'eco** oppure abbassa il *Guadagno altoparlante* (es. `-6`).
3. Se l'eco resta forte (succede su alcuni pannelli con altoparlante vicino al microfono), attiva **"Premi per parlare"**.

---

### 3.7 Chiamate in uscita (dialer)
Dal pannello puoi anche **chiamare**: altri pannelli, il citofono, interni del centralino.

**Attivazione:** Menu → *Chiamate in uscita (dialer)* → **Dialer attivo**. Scegli da quale bordo aprirlo (destra, sinistra, alto o basso).

**Uso:** striscia il dito **dal bordo scelto verso il centro** dello schermo. Si apre la tastiera:
- componi il numero e premi **Chiama** (cornetta verde) oppure **Video** (videochiamata, con la tua immagine in un riquadro);
- oppure tocca un **numero rapido** nella colonna di destra;
- il dialer si chiude da solo dopo 90 secondi senza tocchi.

**Cosa si può chiamare:**
| Scrivi | Chiama |
|---|---|
| `201` | l'interno 201 del centralino (solo se il pannello è registrato su un centralino) |
| `192.168.2.50` | direttamente il dispositivo SIP con quell'IP (altro pannello, citofono) |
| `sip:nome@192.168.2.50:5062` | un indirizzo SIP completo |

**Numeri rapidi (senza entrare nel menu generale):** nel dialer tocca **Modifica** e inserisci il **codice installatore**. Poi:
- **+ Aggiungi numero rapido**: nome, numero e se deve essere una videochiamata;
- tocca un numero esistente (✎) per modificarlo o eliminarlo;
- **Fine** per tornare normale.

**Codice installatore:** si imposta nel menu (sezione dialer). Se lo lasci vuoto vale il PIN delle impostazioni; se sono vuoti entrambi, la modifica non è protetta.
**Scorciatoia per l'installatore:** nel dialer componi `*#CODICE#` (es. `*#1234#`) e premi Chiama: si aprono direttamente le impostazioni, senza tenere premuto l'angolo.

**Tastiera durante una chiamata** (pulsante **Tastiera** nella schermata di chiamata). In alto scegli la modalità:
- **Toni DTMF**: ogni tasto premuto viene inviato subito all'interlocutore (es. codici del citofono o di un risponditore automatico). È disponibile dopo aver risposto.
- **Inoltro**: componi un numero, oppure tocca un numero rapido, e premi **Inoltra**. Se non hai ancora risposto, il pannello prova a deviare la chiamata (302). Se la deviazione non è possibile, per esempio perché il video pre-risposta è già attivo, il pannello risponde e trasferisce subito la chiamata (REFER). Il trasferimento funziona solo se il citofono o il centralino lo supportano.

Durante una chiamata il sensore di movimento si ferma (la fotocamera serve alla videochiamata) e riparte da solo alla fine.

### 3.8 Doppia rete: LAN e Wi-Fi insieme
Serve quando il citofono 2N e la pagina domotica stanno su **due reti diverse**. Esempio: il 2N sulla rete condominiale `192.168.10.x` raggiunta via Wi-Fi, Home Assistant sulla rete di casa `192.168.1.x` raggiunta via cavo. Se tutto è sulla stessa rete lascia la **modalità normale**.

1. **IP fissi** (una volta sola, dalle impostazioni di Android: Android non permette alle app di impostarli). Nel menu di KioskKNX, sezione *Rete (LAN / Wi-Fi)*, ci sono i pulsanti per aprirle.
   - **LAN:** *Impostazioni → Rete → Ethernet → IP statico* (es. `192.168.1.50`, gateway `192.168.1.1`).
   - **Wi-Fi:** tieni premuto sulla rete → *Modifica* → *Opzioni avanzate* → *Impostazioni IP: Statico* (es. `192.168.10.101`). Il **gateway** lascialo vuoto o metti quello della rete condominiale.
   - In alternativa chiedi a chi gestisce le reti di **riservare** quegli indirizzi nel DHCP.
2. Collega il tablet sia al cavo sia al Wi-Fi.
3. Menu KioskKNX → *Rete (LAN / Wi-Fi)*:
   - **Doppia rete:** attiva;
   - **Rete del citofono:** Wi-Fi oppure LAN (dove sta il 2N);
   - **Rete della pagina web:** LAN oppure Wi-Fi (dove sta Home Assistant / il server KNX);
   - **Rete del popup telecamera:** come la pagina web oppure come il citofono (dove stanno le telecamere / l'NVR).
4. **Salva.** In *Stato del pannello* vedi gli IP delle due schede e quale funzione usa ciascuna.
5. Sul 2N il pulsante dell'appartamento deve chiamare l'IP del tablet **sulla rete del citofono** (es. `sip:pannello@192.168.10.101:5062`).

Puoi cambiare la scelta in qualsiasi momento, anche da remoto con `/api/config` (sezione `network`). Con la doppia rete la pagina web usa sempre il motore Gecko integrato.
Se una delle due schede non è collegata, le funzioni assegnate a quella scheda usano la rete disponibile.

## 4. Manuale delle API locali

### 4.1 Nozioni di base
- Indirizzo: `http://IP_DEL_TABLET:8080` (porta modificabile). Aprendolo nel browser compare l'elenco dei comandi.
- Tutti i comandi accettano **GET** con parametri nell'URL **oppure POST** (form o JSON).
- Le risposte sono in **JSON**, per esempio `{"ok":true,"message":"..."}`.
- **API key** (se impostata nel menu) in uno di questi tre modi:
  - parametro `?key=LA_CHIAVE`
  - header `X-Api-Key: LA_CHIAVE`
  - header `Authorization: Bearer LA_CHIAVE`

Negli esempi: tablet `192.168.1.60`, porta `8080`, chiave `segreta`.

> **Server KNX / Shelly / Home Assistant:** basta incollare l'URL GET nel campo "URL / azione HTTP" del dispositivo. Esempio di azione Shelly "Pulsante premuto": `http://192.168.1.60:8080/api/tts?key=segreta&text=Qualcuno%20al%20cancello`
> Gli spazi nell'URL si scrivono `%20` (molti sistemi lo fanno da soli).

### 4.2 `/api/screen` — Schermo
| Parametro | Valori | Descrizione |
|---|---|---|
| `action` | `on`, `off`, `screensaver`, `brightness`, `dim` | `on` = accende/sveglia; `screensaver` = schermo nero; `off` = spegne il display (serve Device Owner, altrimenti schermo nero) |
| `level` | `0`–`100` | Luminosità (con `action=brightness`, oppure da solo) |

```
GET http://192.168.1.60:8080/api/screen?key=segreta&action=on
GET http://192.168.1.60:8080/api/screen?key=segreta&action=screensaver
GET http://192.168.1.60:8080/api/screen?key=segreta&level=30
```
```bash
curl "http://192.168.1.60:8080/api/screen?key=segreta&action=on"
curl -X POST -H "X-Api-Key: segreta" -H "Content-Type: application/json" \
     -d '{"action":"brightness","level":80}' http://192.168.1.60:8080/api/screen
```

### 4.3 `/api/webview` — Pagina web
| Parametro | Valori | Descrizione |
|---|---|---|
| `action` | `reload`, `navigate`, `home` | ricarica la pagina, apre un URL, torna alla pagina KNX configurata |
| `url` | indirizzo | pagina da aprire (con `navigate`, oppure da solo) |

```
GET http://192.168.1.60:8080/api/webview?key=segreta&action=reload
GET http://192.168.1.60:8080/api/webview?key=segreta&url=http://192.168.1.10/visu/allarmi
GET http://192.168.1.60:8080/api/webview?key=segreta&action=home
```
```bash
curl "http://192.168.1.60:8080/api/webview?key=segreta&action=reload"
```

### 4.4 `/api/tts` — Annuncio vocale (italiano)
| Parametro | Valori | Descrizione |
|---|---|---|
| `text` | testo | frase da pronunciare (obbligatorio) |
| `volume` | `0`–`100` | volume (facoltativo) |
| `rate` | es. `0.8`, `1.0`, `1.3` | velocità (facoltativo) |
| `wake` | `true` | accende anche lo schermo |
| `force` | `true` | parla anche in modalità notte |

```
GET http://192.168.1.60:8080/api/tts?key=segreta&text=Attenzione,%20finestra%20aperta&volume=90
```
```bash
curl -G "http://192.168.1.60:8080/api/tts" --data-urlencode "text=Attenzione, finestra aperta" \
     --data-urlencode "volume=90" --data-urlencode "key=segreta"
curl -X POST -H "X-Api-Key: segreta" -H "Content-Type: application/json" \
     -d '{"text":"Lavatrice terminata","volume":70}' http://192.168.1.60:8080/api/tts
```
> Serve un **motore di sintesi vocale** installato sul tablet, con la voce italiana. Molti pannelli senza Google Play non ne hanno uno: installa "Speech Services by Google" oppure RHVoice. Il menu (riga "Stato") mostra se la voce italiana è disponibile.

### 4.5 `/api/sound` — Suoni e allarmi
| Parametro | Valori | Descrizione |
|---|---|---|
| `name` | `doorbell`, `chime`, `beep`, `alarm`, `ring`, `notification` | suono predefinito |
| `url` | indirizzo di un file audio | in alternativa a `name` (mp3/wav via http) |
| `loop` | `true` / `false` | ripete finché non viene fermato |
| `volume` | `0`–`100` | volume |
| `action` | `play` (predefinito), `stop` | `stop` ferma suoni, suoneria e voce |
| `force` | `true` / `false` | `true` = suona anche in modalità notte con suoneria silenziata (es. allarmi) |

**Impostazioni separate dal citofono** (menu → Audio e suoneria → "Suoni comandati dalla domotica"): volume, amplificazione (dB) e suono predefinito usato quando la richiesta non indica `name`. La suoneria del citofono SIP ha le sue impostazioni distinte.

**Modalità notte** (menu → Modalità notte): "Domotica: suoni silenziati di notte" → i suoni NON vengono riprodotti (risposta `ok` con messaggio "suono silenziato"); altrimenti usano al massimo "Domotica: volume suoni di notte", senza amplificazione. La suoneria del citofono SIP ha interruttore e volume notturni separati. Per un allarme che deve suonare sempre aggiungi `force=true`.

```
GET http://192.168.1.60:8080/api/sound?key=segreta&name=alarm&loop=true&volume=100
GET http://192.168.1.60:8080/api/sound?key=segreta&action=stop
GET http://192.168.1.60:8080/api/sound?key=segreta&name=chime
```
```bash
curl "http://192.168.1.60:8080/api/sound?key=segreta&name=doorbell&volume=80"
```

### 4.6 `/api/camera-popup` — Mostra una telecamera
Apre un riquadro a tutto schermo con lo stream video, anche senza chiamata (per esempio quando un sensore rileva movimento in giardino).

| Parametro | Valori | Descrizione |
|---|---|---|
| `url` | `rtsp://...` o `http://...` (MJPEG) | stream; se assente usa l'URL video del citofono |
| `seconds` | numero | chiusura automatica dopo N secondi (`0` = resta aperto) |
| `title` | testo | titolo mostrato |
| `action` | `open` (predefinito), `close` | `close` chiude il popup |
| `transport` | `tcp` (predefinito), `udp`, `auto` | *(facoltativo)* trasporto RTP per RTSP. `auto` prova UDP e al primo errore passa a TCP |

**Comportamento:**
- **NVR/telecamere Hikvision** che non inviano i parametri video nell'SDP (`sprop-parameter-sets`) vengono riconosciuti da soli: l'app passa al suo lettore H.264 integrato, senza bisogno di configurare nulla.
- In caso di errore l'app riprova **al massimo 5 volte**, con attese crescenti (1, 2, 4, 8 s). Poi mostra il motivo nel riquadro, che si chiude comunque allo scadere di `seconds`.
- Se l'NVR risponde **401/403 (utente o password errati)** l'app **non riprova**, per evitare che l'NVR blocchi l'IP del tablet per troppi accessi falliti.
- Se arriva durante una **chiamata del citofono**, il popup viene **messo in coda** e mostrato a fine chiamata, se il suo tempo non è ancora scaduto. La risposta API contiene `"queued": true`.
- Per vedere l'immagine in meno di 3 s, sull'NVR imposta per quel flusso un **intervallo I-frame** non superiore a 2 volte gli fps (es. 30 a 15 fps).

```
GET http://192.168.1.60:8080/api/camera-popup?key=segreta&seconds=30&title=Giardino&url=rtsp://admin:pwd@192.168.1.80:554/Streaming/Channels/102
GET http://192.168.1.60:8080/api/camera-popup?key=segreta&action=close
```
```bash
curl -G "http://192.168.1.60:8080/api/camera-popup" --data-urlencode "key=segreta" \
     --data-urlencode "url=rtsp://admin:pwd@192.168.1.80:554/Streaming/Channels/102" \
     --data-urlencode "seconds=30" --data-urlencode "title=Giardino"
```
Nel popup compaiono anche le serrature con comando HTTP e i pulsanti personalizzati abilitati.

### 4.7 `/api/status` — Stato del tablet
```bash
curl "http://192.168.1.60:8080/api/status?key=segreta"
```
Esempio di risposta:
```json
{
  "ok": true, "app": "KioskKNX", "version": "1.0.0", "uptimeSec": 86400,
  "deviceOwner": true, "lockTask": true,
  "sip": { "registration": "registrato", "call": "idle", "remote": "", "localPort": "5062" },
  "screen": { "state": "screensaver", "interactive": true, "brightness": -1 },
  "battery": { "level": 100, "charging": true, "power": "ac" },
  "network": { "type": "ethernet", "ip": "192.168.1.60", "apiPort": 8080 },
  "motion": { "enabled": true, "lastMotion": "2026-10-23T18:42:10", "lastMotionEpochMs": 1792780930000, "secondsAgo": 35 },
  "webview": { "url": "http://192.168.1.10/visu", "loaded": true },
  "night": false, "cameraPopup": false, "tts": true
}
```
- `screen.state`: `on`, `dim`, `screensaver`, `off`
- `sip.call`: `idle`, `ringing`, `active`

### 4.8 `/api/call` — Controllo chiamata
| `action` | Descrizione |
|---|---|
| `answer` | risponde |
| `hangup` / `decline` | chiude o rifiuta |
| `open` + `lock=1` o `lock=2` (oppure `open1`, `open2`) | apre la serratura |
| `dtmf` + `digits=#1` | invia toni DTMF |
| `dial` + `number=201` (o un IP) + `video=true` (facoltativo) | il pannello chiama quel numero |

```
GET http://192.168.1.60:8080/api/call?key=segreta&action=open&lock=1
```

### 4.9 `/api/night` — Modalità notte
`state=on` (forza notte), `off` (forza giorno), `auto` (segue l'orario impostato). Senza parametri restituisce lo stato.
```
GET http://192.168.1.60:8080/api/night?key=segreta&state=on
```
Di notte: suoneria più bassa o muta, luminosità limitata, annunci vocali e riaccensione da movimento solo se consentiti.

### 4.10 `/api/history` e `/api/snapshot` — Storico chiamate
```bash
curl "http://192.168.1.60:8080/api/history?key=segreta"
curl -o visitatore.jpg "http://192.168.1.60:8080/api/snapshot?key=segreta&id=latest"
```
`id` può essere `latest` oppure l'`id` di una chiamata dello storico.

### 4.11 `/api/update` — Aggiornamento dell'app da remoto
```
GET http://192.168.1.60:8080/api/update?key=segreta&url=http://192.168.1.5/KioskKNX.apk
```
Con il **Device Owner** l'installazione è silenziosa e l'app si riavvia da sola. Senza Device Owner il tablet chiede conferma a schermo.
Il nuovo APK deve essere firmato con la **stessa chiave** (conserva la cartella `keystore`).

### 4.12 `/api/logs`, `/api/restart`, `/api/reboot`
- `/api/logs`: ultime 500 righe di log (testo).
- `/api/restart`: riavvia l'app.
- `/api/reboot`: riavvia il tablet (solo con Device Owner).

### 4.13 `/api/config` — Configurazione completa
```bash
# esporta
curl -o config.json "http://192.168.1.60:8080/api/config?key=segreta"
# importa (dopo aver modificato il file)
curl -X POST -H "X-Api-Key: segreta" -H "Content-Type: application/json" \
     --data-binary @config.json http://192.168.1.60:8080/api/config
```
Si può disattivare dal menu (*"Consenti importazione configurazione via API"*).

---

## 5. Webhook in uscita (il tablet avvisa la domotica)
Nel menu **Webhook in uscita** puoi indicare un URL per ogni evento:

| Evento | Quando |
|---|---|
| Movimento davanti al pannello | la fotocamera rileva una persona (al massimo una volta ogni N secondi) |
| Chiamata in arrivo / risposta / persa / terminata | eventi del citofono |
| Porta aperta | è stata usata una serratura |

Segnaposto utilizzabili nell'URL: `{event}` `{remote}` `{lock}` `{time}` `{ip}`.
Esempio (accende una luce Shelly quando qualcuno si avvicina):
```
http://192.168.1.71/relay/0?turn=on&timer=60
```
Con metodo **POST** viene inviato un JSON: `{"event":"motion","remote":"","lock":"","time":"2026-10-23T18:42:10","ip":"192.168.1.60"}`

---

## 6. Affidabilità H24 (cosa fa l'app da sola)
- **Riconnessione automatica**: se cade la rete o il server KNX non risponde compare la pagina "Server KNX non raggiungibile" e l'app riprova da sola. SIP e API si ricollegano al ritorno della rete.
- **Watchdog pagina**: se la pagina si blocca o il motore web va in crash, la pagina viene ricreata.
- **Ricarica notturna**: ogni notte alle 4:00 (modificabile) per evitare rallentamenti dopo settimane di funzionamento.
- **Riavvio automatico**: dopo un crash, dopo un aggiornamento e all'accensione del tablet.
- **Salvaschermo nero con orologio che si sposta** (anti burn-in). Il primo tocco sveglia lo schermo senza premere nulla nella pagina.

---

## 7. Risoluzione problemi
| Problema | Soluzione |
|---|---|
| La pagina (es. Home Assistant) mostra "Errore di configurazione" o schede vuote, mentre sul PC funziona | Il componente **WebView** di Android è troppo vecchio (lo vedi in Menu → "Stato del pannello", riga WebView: serve la versione 110 o successiva). Soluzione: **Menu → Motore web = Gecko** (o Automatico); in alternativa aggiorna la WebView di sistema (paragrafo 7.1). |
| Il citofono non fa squillare il tablet | Controlla IP e porta (`5062`) nella destinazione del citofono e la lista "IP autorizzati". Con il centralino controlla che lo stato sia "registrato". |
| Squilla ma non vedo il video | Imposta l'URL RTSP del citofono e "Quando usarlo = Sempre". Provalo con VLC. |
| Video a scatti o in ritardo | Usa il flusso secondario (es. `.../Channels/102`) e lascia "RTSP su TCP" attivo. |
| Eco durante la conversazione | Capitolo 3.6. |
| La porta non si apre col DTMF | Verifica il codice e la modalità DTMF (RFC 2833 / SIP INFO) uguali a quelle del citofono, oppure usa il comando HTTP. |
| Gli annunci vocali non si sentono | Installa un motore di sintesi vocale con voce italiana (paragrafo 4.4). |
| Il sensore di movimento non funziona | Se nel menu "Stato del pannello" compare *Permesso FOTOCAMERA mancante*, tocca **Consenti** quando l'app lo chiede (oppure Sistema → Permessi). Su alcuni pannelli citofonici la fotocamera è già usata dall'app del produttore. Con il salvaschermo "Spegni display" il sensore è fermo: usa "Schermo nero". |
| L'API non risponde | Controlla porta e API key nel menu e che il PC sia nella stessa rete. Apri `http://IP:8080/` nel browser. |
| Voglio togliere l'app | Menu → **Disattiva Device Owner** → Disinstalla. Senza Device Owner si disinstalla come una normale app. |

Per una diagnosi avanzata usa **Menu → Log** oppure `/api/logs`.

### 7.1 (Facoltativo) Aggiornare la WebView di sistema
Non serve se usi il motore **Gecko** integrato (impostazione predefinita "Automatico").
Le dashboard moderne (Home Assistant con schede HACS come mushroom o button-card, visualizzazioni KNX in HTML5) richiedono un motore web recente.
1. Nel menu di KioskKNX leggi la riga **WebView** (per esempio `com.android.webview versione 83.0...`).
2. Scarica l'APK aggiornato di **Android System WebView** (`com.google.android.webview`) per l'architettura del tablet (arm64), da una fonte affidabile come APKMirror, e installalo come una normale app (chiavetta o `adb install`).
3. Su molti pannelli Rockchip vai poi in **Opzioni sviluppatore → Implementazione WebView** e scegli quella appena installata. In alternativa installa **Google Chrome** e sceglilo lì.
4. Riavvia KioskKNX (Menu → Riavvia app) e controlla che la riga WebView mostri la nuova versione.

Se il firmware non permette di cambiare la WebView (ROM bloccate), chiedi al produttore un aggiornamento firmware.
