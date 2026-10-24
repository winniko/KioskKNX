# Funzioni comuni agli script .sh di KioskKNX (Mac / Linux)
PKG="it.kioskknx.app"
BASE="$(pwd)"

fine() {
  echo
  read -r -p "Premi Invio per chiudere..." _
  exit "${1:-0}"
}

trova_file() {
  APK=""
  for f in "$BASE/KioskKNX.apk" "$BASE/APK_PRONTO/KioskKNX.apk" "$BASE"/KioskKNX-*-universale.apk "$BASE"/APK_PRONTO/KioskKNX-*-universale.apk; do [ -f "$f" ] && APK="$f" && break; done
  [ -z "$APK" ] && { echo "ERRORE: non trovo KioskKNX.apk accanto allo script."; return 1; }

  ADB=""
  if command -v adb >/dev/null 2>&1; then ADB="$(command -v adb)"; fi
  for d in "$BASE/strumenti" "$BASE/APK_PRONTO/strumenti"; do
    [ -z "$ADB" ] && [ -x "$d/platform-tools/adb" ] && ADB="$d/platform-tools/adb"
  done
  if [ -z "$ADB" ]; then
    echo "adb non trovato: lo scarico da Google (circa 10 MB)..."
    case "$(uname -s)" in
      Darwin) URL="https://dl.google.com/android/repository/platform-tools-latest-darwin.zip" ;;
      *) URL="https://dl.google.com/android/repository/platform-tools-latest-linux.zip" ;;
    esac
    DEST="$BASE/strumenti"; mkdir -p "$DEST"
    curl -L -o "$DEST/pt.zip" "$URL" && unzip -q -o "$DEST/pt.zip" -d "$DEST" && rm -f "$DEST/pt.zip"
    ADB="$DEST/platform-tools/adb"
    [ -x "$ADB" ] || { echo "ERRORE: download di adb non riuscito."; return 1; }
  fi
  echo "[1/5] File trovati: $APK"
}

collega() {
  echo
  echo "[2/5] Come e' collegato il tablet?"
  echo "    1 = cavo USB (Debug USB attivo)"
  echo "    2 = rete LAN/Wi-Fi (Debug ADB via rete, porta 5555)"
  read -r -p "Scelta [1/2]: " MODO
  "$ADB" start-server >/dev/null 2>&1
  if [ "$MODO" = "2" ]; then
    read -r -p "Indirizzo IP del tablet (es. 192.168.1.60): " IP
    case "$IP" in *:*) ;; *) IP="$IP:5555" ;; esac
    "$ADB" connect "$IP"
    DEV="$IP"
  else
    echo "Attendo il tablet via USB... (sul tablet tocca 'Consenti' se richiesto)"
    "$ADB" wait-for-usb-device
    DEV=$("$ADB" devices | awk '$2=="device"{print $1; exit}')
  fi
  sleep 2
  if [ "$("$ADB" -s "$DEV" get-state 2>/dev/null)" != "device" ]; then
    echo "ERRORE: tablet non raggiungibile o non autorizzato."; "$ADB" devices; return 1
  fi
  echo "   Collegato: $("$ADB" -s "$DEV" shell getprop ro.product.model | tr -d '\r') ($DEV)"
}

concedi_permessi() {
  for p in CAMERA RECORD_AUDIO POST_NOTIFICATIONS; do
    "$ADB" -s "$DEV" shell pm grant "$PKG" "android.permission.$p" >/dev/null 2>&1
  done
  for op in SYSTEM_ALERT_WINDOW REQUEST_INSTALL_PACKAGES RUN_IN_BACKGROUND RUN_ANY_IN_BACKGROUND; do
    "$ADB" -s "$DEV" shell appops set "$PKG" "$op" allow >/dev/null 2>&1
  done
  "$ADB" -s "$DEV" shell dumpsys deviceidle whitelist "+$PKG" >/dev/null 2>&1
}
