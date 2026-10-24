@echo off
setlocal EnableDelayedExpansion
title KioskKNX - Attiva kiosk blindato (Device Owner)
cd /d "%~dp0"
set PKG=it.kioskknx.app
set ADMIN=%PKG%/.kiosk.KioskDeviceAdminReceiver

echo ==============================================================
echo   KioskKNX - Attivazione KIOSK BLINDATO (Device Owner)
echo ==============================================================
echo  Dopo questa operazione il tablet restera' bloccato su KioskKNX:
echo  niente barra notifiche, niente tasti Home/Indietro, avvio
echo  automatico. Per sbloccare: menu segreto ^> "Disattiva Device Owner".
echo.
echo  REQUISITO: sul tablet NON devono esserci account (Google, ecc.).
echo  Se necessario rimuovili da Impostazioni ^> Account, oppure fai un
echo  ripristino di fabbrica e salta l'aggiunta dell'account Google.
echo ==============================================================
echo.

call :trova_file || goto :fine
call :collega || goto :fine

echo.
echo [3/5] Controllo che KioskKNX sia installata...
"%ADB%" -s %DEV% shell pm list packages %PKG% | findstr /c:"%PKG%" >nul
if errorlevel 1 (
    echo    Non installata: la installo ora...
    "%ADB%" -s %DEV% install -r -g "%APK%"
)
"%ADB%" -s %DEV% shell appops set %PKG% SYSTEM_ALERT_WINDOW allow 2>nul
"%ADB%" -s %DEV% shell dumpsys deviceidle whitelist +%PKG% >nul 2>nul

echo.
echo [4/5] Controllo account presenti sul tablet...
set ACC=0
for /f "tokens=2 delims=: " %%a in ('call "%ADB%" -s %DEV% shell dumpsys account ^| findstr /c:"Accounts:"') do (
    if %%a GTR !ACC! set ACC=%%a
)
if not "!ACC!"=="0" (
    echo.
    echo  ATTENZIONE: sul tablet ci sono !ACC! account configurati.
    echo  Android non permette di attivare il Device Owner finche' esistono.
    echo  Rimuovili da Impostazioni ^> Account ^(o Utenti e account^) e riprova.
    "%ADB%" -s %DEV% shell dumpsys account | findstr /c:"Account {"
    goto :fine
)
echo    Nessun account: OK

echo.
echo [5/5] Attivazione Device Owner...
"%ADB%" -s %DEV% shell dpm set-device-owner %ADMIN% > "%TEMP%\kioskknx_dpm.txt" 2>&1
type "%TEMP%\kioskknx_dpm.txt"
findstr /i /c:"Success" "%TEMP%\kioskknx_dpm.txt" >nul
if errorlevel 1 (
    findstr /i /c:"already" "%TEMP%\kioskknx_dpm.txt" >nul
    if not errorlevel 1 (
        echo.
        echo  Il tablet ha gia' un Device Owner ^(forse KioskKNX stessa: in tal caso e' tutto OK^).
    ) else (
        echo.
        echo  ERRORE: attivazione non riuscita. Cause tipiche:
        echo   - account ancora presenti ^(anche nascosti, es. account del produttore^)
        echo   - piu' utenti configurati sul tablet ^(Impostazioni ^> Utenti^)
        echo   - firmware del produttore che blocca il Device Owner
        echo  L'app funziona comunque in modalita' fullscreen normale.
    )
    goto :fine
)

"%ADB%" -s %DEV% shell am force-stop %PKG%
"%ADB%" -s %DEV% shell am start -n %PKG%/.ui.MainActivity >nul
echo.
echo ==============================================================
echo   KIOSK BLINDATO ATTIVO.
echo   Per le impostazioni: tieni premuto 10 secondi l'angolo in
echo   basso a destra. Da li' puoi anche sbloccare o disinstallare.
echo ==============================================================
goto :fine

REM ------------------------------------------------------------------
:trova_file
set APK=
if exist "%~dp0KioskKNX.apk" set "APK=%~dp0KioskKNX.apk"
if not defined APK if exist "%~dp0APK_PRONTO\KioskKNX.apk" set "APK=%~dp0APK_PRONTO\KioskKNX.apk"
if not defined APK for %%f in ("%~dp0KioskKNX-*-universale.apk") do set "APK=%%~ff"
if not defined APK for %%f in ("%~dp0APK_PRONTO\KioskKNX-*-universale.apk") do set "APK=%%~ff"
set ADB=
if exist "%~dp0strumenti\adb-windows\adb.exe" set "ADB=%~dp0strumenti\adb-windows\adb.exe"
if not defined ADB if exist "%~dp0APK_PRONTO\strumenti\adb-windows\adb.exe" set "ADB=%~dp0APK_PRONTO\strumenti\adb-windows\adb.exe"
if not defined ADB if exist "%~dp0tools\android-sdk\platform-tools\adb.exe" set "ADB=%~dp0tools\android-sdk\platform-tools\adb.exe"
if not defined ADB (
    where adb >nul 2>nul && set ADB=adb
)
if not defined ADB (
    echo ERRORE: non trovo adb.exe ^(cartella strumenti\adb-windows^).
    exit /b 1
)
echo [1/5] Strumenti trovati.
exit /b 0

REM ------------------------------------------------------------------
:collega
echo.
echo [2/5] Come e' collegato il tablet al PC?
echo     1 = cavo USB  (sul tablet: Opzioni sviluppatore ^> Debug USB attivo)
echo     2 = rete LAN/Wi-Fi  (sul tablet: Debug ADB via rete / porta 5555)
set /p MODO=Scelta [1/2]:
"%ADB%" start-server >nul 2>nul
if "%MODO%"=="2" (
    set /p IP=Indirizzo IP del tablet ^(es. 192.168.1.60 oppure 192.168.1.60:5555^):
    echo !IP! | find ":" >nul || set IP=!IP!:5555
    "%ADB%" connect !IP!
    set DEV=!IP!
) else (
    echo Attendo il tablet via USB... ^(se compare un avviso sul tablet, tocca "Consenti"^)
    "%ADB%" wait-for-usb-device
    for /f "tokens=1" %%d in ('call "%ADB%" devices ^| findstr /r /c:"device$"') do set DEV=%%d
)
ping -n 3 127.0.0.1 >nul
"%ADB%" -s %DEV% get-state 2>nul | findstr "device" >nul
if errorlevel 1 (
    echo.
    echo ERRORE: tablet non raggiungibile o non autorizzato.
    "%ADB%" devices
    exit /b 1
)
for /f "delims=" %%m in ('call "%ADB%" -s %DEV% shell getprop ro.product.model') do set MODELLO=%%m
echo    Collegato: %MODELLO% ^(%DEV%^)
exit /b 0

:fine
echo.
pause
endlocal
