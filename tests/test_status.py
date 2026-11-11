"""Tests the app results file and phone notifications. Run: python tests/test_status.py
Needs: pip install -r requirements.txt http_ece cryptography"""
import base64, json, os, sys, threading
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent))

import http_ece
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from nacl.public import PrivateKey, SealedBox

import status

b64u = lambda b: base64.urlsafe_b64encode(b).rstrip(b"=").decode()

# --- a fake phone: box key for results, push keys like a browser subscription
box = PrivateKey.generate()
recv = ec.generate_private_key(ec.SECP256R1())
recv_pub = recv.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)
auth = os.urandom(16)
# VAPID key exported like the browser does (JWK "d" = raw 32-byte private scalar, base64url)
vapid = ec.generate_private_key(ec.SECP256R1())
vapid_d = b64u(vapid.private_numbers().private_value.to_bytes(32, "big"))

received = []
class H(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers["Content-Length"]))
        received.append((dict(self.headers), body)); self.send_response(201); self.end_headers()
    def log_message(self, *a): pass
srv = ThreadingHTTPServer(("127.0.0.1", 8799), H); threading.Thread(target=srv.serve_forever, daemon=True).start()

sub = {"endpoint": "http://127.0.0.1:8799/push/abc", "keys": {"p256dh": b64u(recv_pub), "auth": b64u(auth)}}
os.environ.update({
    "DEVICE_1": json.dumps({"name": "Phone", "box_pub": base64.b64encode(bytes(box.public_key)).decode(),
                                 "push": {"subscription": sub, "vapid_private": vapid_d}}),
    "DEVICE_2": json.dumps({"name": "Laptop", "box_pub": base64.b64encode(bytes(PrivateKey.generate().public_key)).decode(), "push": None}),
})
os.environ["PAGE_URL"] = "https://example.github.io/repo/"
status.STATUS_FILE = Path(os.environ.get("TMPDIR", "/tmp")) / "status_test.json"
status.STATUS_FILE.unlink(missing_ok=True)

class R:  # minimal report
    errors = ["Dad: login failed: Invalid credentials"]
    people = [
        {"name": "Me", "ok": True, "error": None, "warnings": ["MeroShare password expires in 5 day(s) on 2026-11-16"],
         "applied": [{"scrip": "ABC", "kind": "IPO", "kitta": 10, "amount": 1000}],
         "issues": [{"scrip": "ABC", "state": "applied", "note": "applied 10 kitta", "close": "2026-11-11"}]},
        {"name": "Dad", "ok": False, "error": "login failed: Invalid credentials", "warnings": [], "applied": [], "issues": []},
    ]

for _ in range(2):  # two runs -> history of 2
    status.publish(R, now=datetime(2026, 11, 11, 10, 15), dry_run=False, morning=True,
                   email={"configured": True, "sent": 0, "error": "535 bad credentials"}, log=print)

data = json.loads(status.STATUS_FILE.read_text())
assert set(data["devices"]) == {"1", "2"} and len(data["devices"]["1"]) == 2
res = json.loads(SealedBox(box).decrypt(base64.b64decode(data["devices"]["1"][0])))
assert res["ok"] is False and res["applied"] == ["Me: ABC 10 kitta"]
assert any("Dad" in p for p in res["problems"]) and any("Email could not be sent" in p for p in res["problems"])
assert res["warnings"] == ["Me: MeroShare password expires in 5 day(s) on 2026-11-16"]
assert "Invalid credentials" not in json.dumps(data)  # file holds ciphertext only

# push: one per run (only PHONE1 has push)
assert len(received) == 2, len(received)
headers, body = received[0]
h = {k.lower(): v for k, v in headers.items()}
assert h["authorization"].startswith("vapid t=") and h["content-encoding"] == "aes128gcm", h
msg = json.loads(http_ece.decrypt(body, private_key=recv, auth_secret=auth, version="aes128gcm"))
assert msg["title"] == "IPO Helper: problem" and "Dad" in msg["body"] and msg["url"] == "./#updates"
print("decrypted notification:", msg)

# notification rules
s = {"problems": [], "applied": [], "warnings": [], "people": [{}]}
assert status.notification_for(s, dry_run=False, morning=True) is None              # quiet day: no ping
assert status.notification_for({**s, "applied": ["Me: X 10 kitta"]}, False, False)["title"] == "Applied: X"
assert status.notification_for({**s, "warnings": ["w"]}, False, False) is None       # warnings only in the morning
assert status.notification_for({**s, "warnings": ["w"]}, False, True)["title"] == "IPO Helper: action needed"
assert status.notification_for(s, True, False)["title"] == "Check finished ✓"
srv.shutdown()
print("STATUS TESTS PASSED")
