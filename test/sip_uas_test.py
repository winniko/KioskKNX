"""Finto telefono SIP che RISPONDE (per provare le chiamate in uscita del dialer).

Riceve INVITE -> 100, 180, poi dopo N secondi 200 OK con SDP (audio PCMU + video H264).
Dopo la risposta chiude con BYE dopo M secondi.
Uso: python -I test/sip_uas_test.py [porta=5070] [secondi_squillo=2] [secondi_conversazione=5]
Dall'emulatore: chiamare 10.0.2.2:5070
"""
import socket
import sys
import time

port = int(sys.argv[1]) if len(sys.argv) > 1 else 5070
ring = float(sys.argv[2]) if len(sys.argv) > 2 else 2
talk = float(sys.argv[3]) if len(sys.argv) > 3 else 5
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.bind(("0.0.0.0", port))
print(f"Finto telefono in ascolto UDP {port}", flush=True)


def hdrs(msg):
    lines = msg.split("\r\n")
    return lines[0], {l.split(":", 1)[0].strip().lower(): l.split(":", 1)[1].strip() for l in lines[1:] if ":" in l}


def resp(code, h, addr, to_tag="uas1", body=""):
    out = [f"SIP/2.0 {code}", f"Via: {h['via']}", f"From: {h['from']}", f"To: {h['to']}" + ("" if "tag=" in h["to"] else f";tag={to_tag}"),
           f"Call-ID: {h['call-id']}", f"CSeq: {h['cseq']}", f"Contact: <sip:telefono@10.0.2.2:{port}>"]
    if body:
        out.append("Content-Type: application/sdp")
    out += [f"Content-Length: {len(body)}", "", ""]
    s.sendto(("\r\n".join(out) + body).encode(), addr)


sdp = ("v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=-\r\nc=IN IP4 127.0.0.1\r\nt=0 0\r\n"
       "m=audio 41000 RTP/AVP 0\r\na=rtpmap:0 PCMU/8000\r\na=sendrecv\r\n"
       "m=video 41002 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=sendrecv\r\n")
while True:
    data, addr = s.recvfrom(65535)
    msg = data.decode(errors="replace")
    first, h = hdrs(msg)
    print(time.strftime("%H:%M:%S"), addr, "->", first, "| video nell'offerta:", "m=video" in msg, flush=True)
    if first.startswith("INVITE"):
        resp("100 Trying", h, addr)
        resp("180 Ringing", h, addr)
        time.sleep(ring)
        resp("200 OK", h, addr, body=sdp)
        inv = h
    elif first.startswith("ACK"):
        time.sleep(talk)
        contact = inv.get("contact", f"<sip:pannello@{addr[0]}:{addr[1]}>").strip("<>").split(">")[0]
        bye = [f"BYE {contact} SIP/2.0", f"Via: SIP/2.0/UDP 10.0.2.2:{port};branch=z9hG4bKbye{int(time.time())};rport",
               "Max-Forwards: 70",
               f"From: {inv['to']}" + ("" if "tag=" in inv["to"] else ";tag=uas1"), f"To: {inv['from']}", f"Call-ID: {inv['call-id']}",
               "CSeq: 1 BYE", "Content-Length: 0", "", ""]
        s.sendto("\r\n".join(bye).encode(), addr)
        print("-> BYE inviato", flush=True)
    elif first.startswith("BYE") or first.startswith("CANCEL"):
        resp("200 OK", h, addr)
