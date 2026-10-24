"""Test di accettazione camera-popup su emulatore.

Prerequisiti: emulatore con KioskKNX, `adb forward tcp:18080 tcp:8080`, `adb emu redir add udp:15062:5062`,
finto NVR attivo (test/rtsp_test_server.py) raggiungibile dall'emulatore come 10.0.2.2:18554.

Uso: python -I test/popup_cycle_test.py ADB_PATH [cicli=10] [popup_rapidi=50]
"""
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request

ADB = sys.argv[1]
CYCLES = int(sys.argv[2]) if len(sys.argv) > 2 else 10
BURST = int(sys.argv[3]) if len(sys.argv) > 3 else 50
API = "http://127.0.0.1:18080"
RTSP = "rtsp://admin:test123@10.0.2.2:18554/Streaming/Channels/202"
HERE = __file__.rsplit("\\", 1)[0].rsplit("/", 1)[0]


def api(path, **params):
    q = urllib.parse.urlencode(params)
    with urllib.request.urlopen(f"{API}{path}?{q}", timeout=15) as r:
        return r.read().decode()


def new_lines_since(marker):
    """Righe di registro successive a [marker] (il registro dell'app tiene solo le ultime 500 righe)."""
    lines = api("/api/logs").splitlines()
    if marker in lines:
        return lines[len(lines) - 1 - lines[::-1].index(marker) + 1:]
    return lines


def popup_once(timeout=8.0):
    """Apre il popup e misura il tempo dalla richiesta API al primo fotogramma (ms), None se non arriva."""
    marker = (api("/api/logs").splitlines() or [""])[-1]
    t0 = time.time()
    api("/api/camera-popup", url=RTSP, seconds=30, title="Test")
    while time.time() - t0 < timeout:
        time.sleep(0.2)
        if any("Primo fotogramma" in l for l in new_lines_since(marker)):
            ms = int((time.time() - t0) * 1000)
            api("/api/camera-popup", action="close")
            return ms
    api("/api/camera-popup", action="close")
    return None


def sip_call(answer):
    mode = "wait" if answer else "cancel"
    p = subprocess.Popen([sys.executable, "-I", f"{HERE}/sip_test.py", "15062", mode, "10", "3"],
                         stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    time.sleep(2.5)
    if answer:
        api("/api/call", action="answer")
    out = p.communicate(timeout=30)[0]
    ok = "183" in out and (("200 Ok" in out) if answer else ("487" in out or "200 Ok" in out))
    return ok


def app_stats():
    pid = subprocess.run([ADB, "shell", "pidof", "it.kioskknx.app"], capture_output=True, text=True).stdout.split()
    if not pid:
        return None
    st = subprocess.run([ADB, "shell", "cat", f"/proc/{pid[0]}/status"], capture_output=True, text=True).stdout
    thr = int(re.search(r"Threads:\s+(\d+)", st).group(1))
    mem = subprocess.run([ADB, "shell", "dumpsys", "meminfo", "it.kioskknx.app"], capture_output=True, text=True).stdout
    m = re.search(r"TOTAL PSS:\s+(\d+)", mem) or re.search(r"TOTAL\s+(\d+)", mem)
    return thr, int(m.group(1)) // 1024 if m else -1


print("== Cicli chiamata SIP -> camera-popup")
times, fails = [], 0
t = popup_once()
print(f"popup iniziale: {t} ms")
for i in range(CYCLES):
    answered = i % 2 == 1
    sip_ok = sip_call(answered)
    time.sleep(1.5)
    ms = popup_once()
    times.append(ms)
    if ms is None:
        fails += 1
    print(f"ciclo {i + 1:2d}: chiamata {'con risposta' if answered else 'senza risposta (early media)'} sip_ok={sip_ok} -> popup {ms} ms")
ok_times = [x for x in times if x is not None]
print(f"RISULTATO cicli: {CYCLES - fails}/{CYCLES} con video, max {max(ok_times) if ok_times else '-'} ms, medio {sum(ok_times) // max(1, len(ok_times))} ms")

print(f"== {BURST} popup consecutivi (perdite di thread/memoria)")
s0 = app_stats()
burst_fail = 0
for i in range(BURST):
    if popup_once() is None:
        burst_fail += 1
time.sleep(5)
s1 = app_stats()
print(f"RISULTATO raffica: {BURST - burst_fail}/{BURST} con video; thread {s0[0]} -> {s1[0]}, memoria PSS {s0[1]} MB -> {s1[1]} MB")
