@echo off
setlocal EnableDelayedExpansion
title KioskKNX - Installazione semplice
cd /d "%~dp0"
set PKG=it.kioskknx.app

echo ==============================================================
echo   KioskKNX - Installazione APK sul tablet
echo ==============================================================
echo.

call :trova_file || goto :fine
call :collega || goto :fine

echo.
echo [3/5] Installazione di KioskKNX in corso (puo' richiedere 1 minuto)...
"%ADB%" -s %DEV% install -r -g "%APK%" > "%TEMP%\kioskknx_install.txt" 2>&1
type "%TEMP%\kioskknx_install.txt"
findstr /c:"Success" "%TEMP%\kioskknx_install.txt" >nul
if errorlevel 1 (
    findstr /c:"INSTALL_FAILED_UPDATE_INCOMPATIBLE" "%TEMP%\kioskknx_install.txt" >nul
    if not errorlevel 1 (
        echo.
        echo E' installata una versione firmata con una chiave diversa.
        set /p RISP=Vuoi disinstallarla e reinstallare? Le impostazioni andranno perse. [s/N]:
        if /i "!RISP!"=="s" (
            "%ADB%" -s %DEV% uninstall %PKG%
            "%ADB%" -s %DEV% install -r -g "%APK%"
        ) else goto :fine
    ) else (
        echo.
        echo ERRORE: installazione non riuscita. Leggi il messaggio qui sopra.
        goto :fine
    )
)

echo.
echo [4/5] Concessione automatica dei permessi...
"%ADB%" -s %DEV% shell pm grant %PKG% android.permission.CAMERA 2>nul
"%ADB%" -s %DEV% shell pm grant %PKG% android.permission.RECORD_AUDIO 2>nul
"%ADB%" -s %DEV% shell pm grant %PKG% android.permission.POST_NOTIFICATIONS 2>nul
"%ADB%" -s %DEV% shell appops set %PKG% SYSTEM_ALERT_WINDOW allow 2>nul
"%ADB%" -s %DEV% shell appops set %PKG% REQUEST_INSTALL_PACKAGES allow 2>nul
"%ADB%" -s %DEV% shell appops set %PKG% RUN_IN_BACKGROUND allow 2>nul
"%ADB%" -s %DEV% shell appops set %PKG% RUN_ANY_IN_BACKGROUND allow 2>nul
"%ADB%" -s %DEV% shell dumpsys deviceidle whitelist +%PKG% >nul 2>nul
echo    Fotocamera, Microfono, Notifiche, Sovrapposizione, Batteria: OK

echo.
set /p SETHOME=Vuoi impostare KioskKNX come schermata Home del tablet? [S/n]:
if /i not "!SETHOME!"=="n" (
    "%ADB%" -s %DEV% shell cmd package set-home-activity %PKG%/.ui.MainActivity >nul 2>nul
    echo    KioskKNX impostata come Home.
)

echo.
echo [5/5] Avvio dell'app sul tablet...
"%ADB%" -s %DEV% shell am start -n %PKG%/.ui.MainActivity >nul
echo.
echo ==============================================================
echo   FATTO. Sul tablet accetta l'informativa e configura l'app.
echo   Per le impostazioni: tieni premuto 10 secondi l'angolo
echo   in basso a destra dello schermo.
echo   Per il blocco kiosk totale usa 2_attiva_kiosk_blindato.bat
echo ==============================================================
goto :fine

REM ------------------------------------------------------------------
:trova_file
set APK=
if exist "%~dp0KioskKNX.apk" set "APK=%~dp0KioskKNX.apk"
if not defined APK if exist "%~dp0APK_PRONTO\KioskKNX.apk" set "APK=%~dp0APK_PRONTO\KioskKNX.apk"
if not defined APK for %%f in ("%~dp0KioskKNX-*-universale.apk") do set "APK=%%~ff"
if not defined APK for %%f in ("%~dp0APK_PRONTO\KioskKNX-*-universale.apk") do set "APK=%%~ff"
if not defined APK (
    echo ERRORE: non trovo KioskKNX.apk accanto a questo script.
    exit /b 1
)
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
echo [1/5] File trovati: %APK%
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
    echo  - controlla che il Debug USB / ADB sia attivo
    echo  - sul tablet accetta la richiesta "Consentire debug USB?"
    "%ADB%" devices
    exit /b 1
)
for /f "delims=" %%m in ('call "%ADB%" -s %DEV% shell getprop ro.product.model') do set MODELLO=%%m
for /f "delims=" %%v in ('call "%ADB%" -s %DEV% shell getprop ro.build.version.release') do set VER=%%v
echo    Collegato: %MODELLO% - Android %VER% ^(%DEV%^)
exit /b 0

:fine
echo.
pause
endlocal
