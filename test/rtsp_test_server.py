"""Finto NVR RTSP per i test di KioskKNX.

Serve un file H.264 Annex-B in loop (TCP interleaved) imitando un NVR Hikvision:
- autenticazione Digest
- SDP con packetization-mode=0 e SENZA sprop-parameter-sets (opzione --sprop per includerli)
- SPS/PPS dentro il flusso

Uso: python -I test/rtsp_test_server.py video.h264 [--port 18554] [--fps 15] [--sprop] [--user admin --password test123]
URL: rtsp://admin:test123@<IP_PC>:18554/Streaming/Channels/202
"""
import argparse
import base64
import hashlib
import os
import random
import socket
import struct
import threading
import time

ap = argparse.ArgumentParser()
ap.add_argument("file")
ap.add_argument("--port", type=int, default=18554)
ap.add_argument("--fps", type=float, default=15)
ap.add_argument("--sprop", action="store_true")
ap.add_argument("--user", default="admin")
ap.add_argument("--password", default="test123")
args = ap.parse_args()

REALM = "IP Camera(TEST)"
data = open(args.file, "rb").read()


def split_nals(buf):
    out, i, n = [], 0, len(buf)
    starts = []
    while i < n - 3:
        if buf[i] == 0 and buf[i + 1] == 0 and (buf[i + 2] == 1 or (buf[i + 2] == 0 and i + 3 < n and buf[i + 3] == 1)):
            sc = 3 if buf[i + 2] == 1 else 4
            starts.append((i, i + sc))
            i += sc
        else:
            i += 1
    for k, (s, d) in enumerate(starts):
        end = starts[k + 1][0] if k + 1 < len(starts) else n
        out.append(buf[d:end].rstrip(b"\x00") if k + 1 < len(starts) else buf[d:end])
    return out


NALS = split_nals(data)
SPS = next(n for n in NALS if n[0] & 0x1F == 7)
PPS = next(n for n in NALS if n[0] & 0x1F == 8)
print(f"{len(NALS)} NAL letti, SPS {SPS.hex()[:12]}..., sprop={'si' if args.sprop else 'NO'}")
stats = {"sessions": 0, "active": 0}


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


class Client(threading.Thread):
    def __init__(self, conn, addr):
        super().__init__(daemon=True)
        self.c, self.addr = conn, addr
        self.nonce = "%032x" % random.getrandbits(128)
        self.session = "%08X" % random.getrandbits(32)
        self.playing = False
        self.lock = threading.Lock()

    def send(self, b):
        with self.lock:
            self.c.sendall(b)

    def reply(self, cseq, code="200 OK", headers=None, body=b""):
        h = [f"RTSP/1.0 {code}", f"CSeq: {cseq}", "Server: FintoNVR"]
        for k, v in (headers or {}).items():
            h.append(f"{k}: {v}")
        if body:
            h.append(f"Content-Length: {len(body)}")
        self.send(("\r\n".join(h) + "\r\n\r\n").encode() + body)

    def auth_ok(self, method, hdr):
        if not hdr or not hdr.startswith("Digest"):
            return False
        p = {}
        for part in hdr[6:].split(","):
            if "=" in part:
                k, v = part.strip().split("=", 1)
                p[k.strip()] = v.strip().strip('"')
        if p.get("username") != args.user:
            return False
        ha1 = hashlib.md5(f"{args.user}:{REALM}:{args.password}".encode()).hexdigest()
        ha2 = hashlib.md5(f"{method}:{p.get('uri','')}".encode()).hexdigest()
        exp = hashlib.md5(f"{ha1}:{p.get('nonce','')}:{ha2}".encode()).hexdigest()
        return exp == p.get("response")

    def sdp(self, host):
        fmtp = "a=fmtp:96 profile-level-id=420029; packetization-mode=0"
        if args.sprop:
            fmtp += "; sprop-parameter-sets=%s,%s" % (base64.b64encode(SPS).decode(), base64.b64encode(PPS).decode())
        return (
            "v=0\r\no=- 1 1 IN IP4 %s\r\ns=Media Presentation\r\nc=IN IP4 0.0.0.0\r\nt=0 0\r\n"
            "a=control:*\r\nm=video 0 RTP/AVP 96\r\nb=AS:512\r\na=control:trackID=1\r\n"
            "a=rtpmap:96 H264/90000\r\n%s\r\na=recvonly\r\n" % (host, fmtp)
        ).encode()

    def stream(self):
        seq, ts, ssrc = random.randint(0, 65535), random.randint(0, 2**31), random.getrandbits(32)
        frame_dt = 1.0 / args.fps
        nxt = time.time()
        while self.playing:
            for nal in NALS:
                if not self.playing:
                    return
                t = nal[0] & 0x1F
                slice_ = t in (1, 5)
                hdr = struct.pack("!BBHII", 0x80, (0x80 if slice_ else 0) | 96, seq & 0xFFFF, ts & 0xFFFFFFFF, ssrc)
                pkt = hdr + nal  # packetization-mode=0: un NAL per pacchetto (TCP: niente limite MTU)
                try:
                    self.send(b"$" + bytes([0]) + struct.pack("!H", len(pkt)) + pkt)
                except OSError:
                    self.playing = False
                    return
                seq += 1
                if slice_:
                    ts += int(90000 / args.fps)
                    nxt += frame_dt
                    d = nxt - time.time()
                    if d > 0:
                        time.sleep(d)

    def run(self):
        stats["sessions"] += 1
        stats["active"] += 1
        log(f"connessione da {self.addr} (attive {stats['active']})")
        buf = b""
        try:
            while True:
                chunk = self.c.recv(4096)
                if not chunk:
                    break
                buf += chunk
                while b"\r\n\r\n" in buf:
                    if buf[:1] == b"$":  # RTCP dal client
                        if len(buf) < 4:
                            break
                        ln = struct.unpack("!H", buf[2:4])[0]
                        if len(buf) < 4 + ln:
                            break
                        buf = buf[4 + ln:]
                        continue
                    head, buf = buf.split(b"\r\n\r\n", 1)
                    lines = head.decode(errors="replace").split("\r\n")
                    method, uri = lines[0].split(" ")[:2]
                    h = {l.split(":", 1)[0].strip().lower(): l.split(":", 1)[1].strip() for l in lines[1:] if ":" in l}
                    cseq = h.get("cseq", "0")
                    log(f"{self.addr[1]} -> {method} {uri}")
                    if method == "OPTIONS":
                        self.reply(cseq, headers={"Public": "OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER"})
                        continue
                    if not self.auth_ok(method, h.get("authorization")):
                        self.reply(cseq, "401 Unauthorized", {"WWW-Authenticate": f'Digest realm="{REALM}", nonce="{self.nonce}", stale="FALSE"'})
                        continue
                    if method == "DESCRIBE":
                        self.reply(cseq, headers={"Content-Type": "application/sdp", "Content-Base": uri.rstrip("/") + "/"},
                                   body=self.sdp(self.c.getsockname()[0]))
                    elif method == "SETUP":
                        tr = h.get("transport", "")
                        if "TCP" not in tr.upper():
                            self.reply(cseq, "461 Unsupported Transport")
                            continue
                        self.reply(cseq, headers={"Transport": "RTP/AVP/TCP;unicast;interleaved=0-1", "Session": f"{self.session};timeout=60"})
                    elif method == "PLAY":
                        self.reply(cseq, headers={"Session": self.session, "Range": "npt=0.000-"})
                        if not self.playing:
                            self.playing = True
                            threading.Thread(target=self.stream, daemon=True).start()
                    elif method == "TEARDOWN":
                        self.playing = False
                        self.reply(cseq, headers={"Session": self.session})
                    else:
                        self.reply(cseq, headers={"Session": self.session})
        except OSError:
            pass
        finally:
            self.playing = False
            stats["active"] -= 1
            log(f"chiusa {self.addr} (attive {stats['active']}, totali {stats['sessions']})")
            self.c.close()


srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("0.0.0.0", args.port))
srv.listen(16)
log(f"Finto NVR su porta {args.port}")
while True:
    c, a = srv.accept()
    Client(c, a).start()
