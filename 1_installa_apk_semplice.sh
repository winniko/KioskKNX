#!/usr/bin/env bash
# KioskKNX - Installazione semplice (Mac / Linux). Doppio clic o: bash 1_installa_apk_semplice.sh
cd "$(dirname "$0")" || exit 1
source "./strumenti/comune.sh" 2>/dev/null || source "./APK_PRONTO/strumenti/comune.sh" || { echo "Manca strumenti/comune.sh"; exit 1; }

echo "=============================================================="
echo "  KioskKNX - Installazione APK sul tablet"
echo "=============================================================="
trova_file || fine 1
collega || fine 1

echo
echo "[3/5] Installazione di KioskKNX (puo' richiedere 1 minuto)..."
OUT=$("$ADB" -s "$DEV" install -r -g "$APK" 2>&1); echo "$OUT"
if ! echo "$OUT" | grep -q Success; then
  if echo "$OUT" | grep -q INSTALL_FAILED_UPDATE_INCOMPATIBLE; then
    read -r -p "Versione con firma diversa: disinstallarla e reinstallare? Le impostazioni andranno perse [s/N]: " R
    [ "$R" = "s" ] || fine 1
    "$ADB" -s "$DEV" uninstall "$PKG"; "$ADB" -s "$DEV" install -r -g "$APK" || fine 1
  else
    echo "ERRORE: installazione non riuscita."; fine 1
  fi
fi

echo
echo "[4/5] Concessione automatica dei permessi..."
concedi_permessi
echo "   Fotocamera, Microfono, Notifiche, Sovrapposizione, Batteria: OK"

read -r -p "Impostare KioskKNX come schermata Home del tablet? [S/n]: " H
if [ "$H" != "n" ]; then
  "$ADB" -s "$DEV" shell cmd package set-home-activity "$PKG/.ui.MainActivity" >/dev/null 2>&1
  echo "   KioskKNX impostata come Home."
fi

echo
echo "[5/5] Avvio dell'app sul tablet..."
"$ADB" -s "$DEV" shell am start -n "$PKG/.ui.MainActivity" >/dev/null
echo
echo "FATTO! Sul tablet accetta l'informativa e configura l'app."
echo "Impostazioni: tieni premuto 10 secondi l'angolo in basso a destra."
echo "Per il blocco kiosk totale usa 2_attiva_kiosk_blindato.sh"
fine 0
