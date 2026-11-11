"""Offline end-to-end test against a fake MeroShare. Run: python tests/test_flow.py"""
import json, os, sys
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo
sys.path.insert(0, str(Path(__file__).parent.parent))
import apply_ipo
from kitta import choose_kitta

now = datetime.now(ZoneInfo("Asia/Kathmandu"))
close_today = now.strftime("%b %d, %Y") + " 5:00:00 PM"
soon = (now + timedelta(days=5)).strftime("%Y-%m-%d")

def issue(i, scrip, type_="IPO", group="Ordinary Shares", sub="For General Public", close=close_today, **kw):
    return {"companyShareId": i, "scrip": scrip, "companyName": scrip + " Ltd", "shareTypeName": type_,
            "shareGroupName": group, "subGroup": sub, "statusName": "CREATE_APPROVE", "issueCloseDate": close, **kw}

ISSUES = [
    issue(1, "GOOD"),
    issue(2, "LATER", close="Dec 30, 2099 5:00:00 PM"),
    issue(3, "MF1", group="Mutual Fund"),
    issue(4, "DEB", group="Corporate Debenture"),
    issue(5, "FE", sub="For Nepalese Citizens Working Abroad"),
    issue(6, "RGT", type_="RESERVED (RIGHT SHARE)", sub="RIGHT SHARE"),
    issue(7, "DONE", action="edit"),
    issue(8, "UNDER"),
    issue(9, "FPOX", type_="FPO"),
]
DETAILS = {6: {"minUnit": 1, "maxUnit": 345, "sharePerUnit": 100}}
applied, emails = [], []

class Resp:
    def __init__(self, data, status=200, headers=None):
        self._d, self.status_code = data, status
        self.headers = {"content-type": "application/json", **(headers or {})}
        self.content, self.text = b"x", json.dumps(data)
    def json(self): return self._d
    def raise_for_status(self):
        if self.status_code >= 400: raise Exception(self.status_code)

def fake(self, method, path, **kw):
    if path == "/meroShare/capital/": return Resp([{"id": 128, "code": "13700", "name": "NIC ASIA CAPITAL"}])
    if path == "/meroShare/auth/":
        pw = kw["json"]["password"]
        if pw == "expired": return Resp({"passwordExpired": True}, headers={"Authorization": "t"})
        if pw != "ok": return Resp({"message": "Invalid credentials"}, 401)
        return Resp({"passwordExpiryDate": soon, "accountExpiryDate": "2099-01-01"}, headers={"Authorization": "tok"})
    if path == "/meroShare/ownDetail/": return Resp({"demat": "130", "boid": "1301"})
    if path == "/meroShare/companyShare/applicableIssue/": return Resp({"object": ISSUES})
    if path.startswith("/meroShare/active/"):
        return Resp(DETAILS.get(int(path.rsplit("/", 1)[1]), {"minUnit": 10, "maxUnit": 50000, "multipleOf": 10, "sharePerUnit": 100}))
    if path == "/meroShare/bank/": return Resp([{"id": 44, "code": "1", "name": "Nabil Bank"}])
    if path == "/meroShare/bank/44": return Resp([{"id": 9, "accountNumber": "000", "accountBranchId": 3, "accountTypeId": 1}])
    if path == "/meroShare/applicantForm/share/apply":
        applied.append(kw["json"]); return Resp({"message": "Share has been applied successfully."}, 201)
    raise AssertionError(path)

apply_ipo.MeroShare._req = fake
apply_ipo.send_email = lambda to, subject, body: emails.append((sorted(set(t for t in to if t)), subject, body))
apply_ipo.load_subscription_data = lambda: {"UNDER": {"times": 0.6}}
acc = lambda **kw: json.dumps({"dp": "13700", "username": "1", "pin": "1234", "crn": "C1", **kw})
os.environ["ALL_SECRETS"] = json.dumps({
    "MS_ACCOUNT_ME": acc(name="Me", max_amount=5000), "MS_PASSWORD_ME": "ok",
    "MS_ACCOUNT_DAD": acc(name="Dad", email="dad@example.com"), "MS_PASSWORD_DAD": "expired",
    "MS_ACCOUNT_OFF": acc(enabled=False), "MS_PASSWORD_OFF": "ok",
    "GITHUB_TOKEN": "x",
})
os.environ["EMAIL_TO"] = "me@example.com"
class Morning(datetime):
    @classmethod
    def now(cls, tz=None): return now.replace(hour=10, minute=15)
apply_ipo.datetime = Morning  # pretend it is the 10:15 AM run
rc = apply_ipo.main()

got = {(p["companyShareId"], p["appliedKitta"]) for p in applied}
# GOOD: min 10; UNDER: undersubscribed, capped by max_amount 5000/100 = 50; RGT: full 345; FPO: min 10
assert got == {("1", "10"), ("8", "50"), ("6", "345"), ("9", "10")}, got
assert rc == 1  # Dad's expired password is reported, Me still applied

subjects = [e[1] for e in emails]
warn = next(e for e in emails if "password" in e[1])
assert "dad@example.com" in warn[0] and "me@example.com" in warn[0]
assert "Dad" in warn[2] and "expired" in warn[2]
assert "Me" in warn[2] and "expires in 5 day" in warn[2], warn[2]
print("\nEMAILS:", subjects)

# Logs must not contain personal names (public repo)
assert not any("Dad" in l or "Me:" in l for l in apply_ipo.LOG_LINES)

# kitta unit checks
assert choose_kitta(10, 50000, subscription_times=25).kitta == 10
assert choose_kitta(10, 50000, subscription_times=0.9, account_max_kitta=200).kitta == 200
assert choose_kitta(10, 50000, applicants=1000, units_offered=50000, account_max_kitta=100).kitta == 100
assert choose_kitta(10, 50000, applicants=100000, units_offered=50000).kitta == 10
assert choose_kitta(10, 50000).kitta == 10
print("\nALL TESTS PASSED")
