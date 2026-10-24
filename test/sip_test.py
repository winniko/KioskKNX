"""Finto citofono SIP: invia INVITE (audio PCMU + video H264), stampa le risposte, poi CANCEL o BYE."""
import random
import socket
import sys
import time

target = ("127.0.0.1", int(sys.argv[1]) if len(sys.argv) > 1 else 15062)
mode = sys.argv[2] if len(sys.argv) > 2 else "cancel"  # cancel | wait
ring_secs = float(sys.argv[3]) if len(sys.argv) > 3 else 8

s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.bind(("0.0.0.0", 0))
lport = s.getsockname()[1]
s.settimeout(0.5)
call_id = "%x@citofono" % random.getrandbits(48)
tag = "%x" % random.getrandbits(32)
branch = "z9hG4bK%x" % random.getrandbits(40)
lip = "127.0.0.1"

sdp = (
    "v=0\r\no=2N 1 1 IN IP4 {ip}\r\ns=call\r\nc=IN IP4 {ip}\r\nt=0 0\r\n"
    "m=audio 40000 RTP/AVP 0 101\r\na=rtpmap:0 PCMU/8000\r\na=rtpmap:101 telephone-event/8000\r\na=sendrecv\r\n"
    "m=video 40002 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=fmtp:96 profile-level-id=42801F\r\na=sendrecv\r\n"
).format(ip=lip)


def req(method, cseq, body="", branch_=branch, to_tag=""):
    to = "<sip:pannello@127.0.0.1:5062>" + (";tag=" + to_tag if to_tag else "")
    h = [
        "%s sip:pannello@127.0.0.1:5062 SIP/2.0" % method,
        "Via: SIP/2.0/UDP %s:%d;branch=%s;rport" % (lip, lport, branch_),
        "Max-Forwards: 70",
        'From: "Citofono Ingresso" <sip:citofono@%s:%d>;tag=%s' % (lip, lport, tag),
        "To: " + to,
        "Call-ID: " + call_id,
        "CSeq: %d %s" % (cseq, method),
        "Contact: <sip:citofono@%s:%d>" % (lip, lport),
        "User-Agent: FintoCitofono/1.0",
    ]
    if body:
        h += ["Content-Type: application/sdp"]
    h += ["Content-Length: %d" % len(body), "", ""]
    return ("\r\n".join(h) + body).encode()


s.sendto(req("INVITE", 1, sdp), target)
print("INVITE inviato verso", target)
to_tag = ""
t0 = time.time()
answered = False
while time.time() - t0 < ring_secs:
    try:
        data, addr = s.recvfrom(65535)
    except socket.timeout:
        continue
    txt = data.decode(errors="replace")
    first = txt.split("\r\n")[0]
    print("<-", first)
    for line in txt.split("\r\n"):
        if line.lower().startswith("to:") and "tag=" in line:
            to_tag = line.split("tag=")[1].split(";")[0].strip()
    if "m=video" in txt:
        print("   (SDP con video ricevuto)")
    if first.startswith("SIP/2.0 200"):
        answered = True
        s.sendto(req("ACK", 1, branch_="z9hG4bKack%x" % random.getrandbits(20), to_tag=to_tag), target)
        print("-> ACK")
        break
    if first.startswith("SIP/2.0 4") or first.startswith("SIP/2.0 6"):
        s.sendto(req("ACK", 1, to_tag=to_tag), target)
        print("Chiamata rifiutata")
        sys.exit(0)

if answered:
    time.sleep(float(sys.argv[4]) if len(sys.argv) > 4 else 3)
    s.sendto(req("BYE", 2, branch_="z9hG4bKbye%x" % random.getrandbits(20), to_tag=to_tag), target)
    print("-> BYE")
else:
    s.sendto(req("CANCEL", 1), target)
    print("-> CANCEL")
end = time.time() + 3
while time.time() < end:
    try:
        data, _ = s.recvfrom(65535)
        print("<-", data.decode(errors="replace").split("\r\n")[0])
    except socket.timeout:
        pass
