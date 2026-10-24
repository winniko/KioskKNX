#!/usr/bin/env bash
# KioskKNX - Attiva kiosk blindato / Device Owner (Mac / Linux)
cd "$(dirname "$0")" || exit 1
source "./strumenti/comune.sh" 2>/dev/null || source "./APK_PRONTO/strumenti/comune.sh" || { echo "Manca strumenti/comune.sh"; exit 1; }
ADMIN="$PKG/.kiosk.KioskDeviceAdminReceiver"

echo "=============================================================="
echo "  KioskKNX - Attivazione KIOSK BLINDATO (Device Owner)"
echo "=============================================================="
echo " Il tablet restera' bloccato su KioskKNX. Per sbloccare:"
echo " menu segreto > 'Disattiva Device Owner'."
echo " REQUISITO: nessun account (Google, ecc.) sul tablet."
echo
trova_file || fine 1
collega || fine 1

echo
echo "[3/5] Controllo che KioskKNX sia installata..."
if ! "$ADB" -s "$DEV" shell pm list packages "$PKG" | grep -q "$PKG"; then
  echo "   Non installata: la installo ora..."
  "$ADB" -s "$DEV" install -r -g "$APK" || fine 1
fi
concedi_permessi

echo
echo "[4/5] Controllo account presenti..."
ACC=$("$ADB" -s "$DEV" shell dumpsys account | grep -o 'Accounts: [0-9]*' | awk '{print $2}' | sort -n | tail -1)
if [ "${ACC:-0}" != "0" ]; then
  echo " ATTENZIONE: sul tablet ci sono $ACC account configurati."
  echo " Rimuovili da Impostazioni > Account e riprova."
  "$ADB" -s "$DEV" shell dumpsys account | grep 'Account {'
  fine 1
fi
echo "   Nessun account: OK"

echo
echo "[5/5] Attivazione Device Owner..."
OUT=$("$ADB" -s "$DEV" shell dpm set-device-owner "$ADMIN" 2>&1); echo "$OUT"
if echo "$OUT" | grep -qi success; then
  "$ADB" -s "$DEV" shell am force-stop "$PKG"
  "$ADB" -s "$DEV" shell am start -n "$PKG/.ui.MainActivity" >/dev/null
  echo
  echo "KIOSK BLINDATO ATTIVO!"
elif echo "$OUT" | grep -qi already; then
  echo "Il tablet ha gia' un Device Owner (se e' KioskKNX e' tutto OK)."
else
  echo "ERRORE: attivazione non riuscita (account nascosti, piu' utenti o firmware che lo blocca)."
  echo "L'app funziona comunque in fullscreen normale."
fi
fine 0
