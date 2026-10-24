# Strumenti di test (emulatore Android)

Tutti gli script richiedono Python 3 e un emulatore Android con KioskKNX installato (`./gradlew assembleDebug -Pemu`).

```bash
adb forward tcp:18080 tcp:8080          # API dell'app su http://127.0.0.1:18080
adb emu redir add udp:15062:5062        # porta SIP dell'app raggiungibile dal PC
```

| Script | Cosa fa |
|---|---|
| `sip_test.py 15062 cancel\|wait <sec squillo> <sec conversazione>` | Finto citofono: chiama il pannello (INVITE con audio+video) |
| `sip_uas_test.py 5070 <sec squillo> <sec conversazione>` | Finto telefono che risponde: per provare il dialer (dall'emulatore chiamare `10.0.2.2:5070`) |
| `rtsp_test_server.py video.h264 --port 18554` | Finto NVR Hikvision: RTSP con Digest, `packetization-mode=0`, **senza** `sprop-parameter-sets` (`--sprop` per includerli) |
| `popup_cycle_test.py <percorso adb> 10 50` | Cicli chiamata SIP → popup telecamera e raffica di popup con misura di thread e memoria |

Attenzione: la porta 8554 è usata dall'emulatore stesso (gRPC), quindi il finto NVR usa la **18554**.

Video di prova per il finto NVR (richiede ffmpeg):
```bash
ffmpeg -f lavfi -i "testsrc2=size=352x288:rate=15" -t 20 -c:v libx264 -profile:v baseline -pix_fmt yuv420p \
  -x264-params "keyint=30:min-keyint=30:scenecut=0:repeat-headers=1" -bsf:v h264_mp4toannexb -f h264 test/nvr_test_352x288.h264
```
