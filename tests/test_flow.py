import json, os, sys
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo
sys.path.insert(0, str(Path(__file__).parent.parent))
import apply_ipo
from kitta import choose_kitta

today = datetime.now(ZoneInfo("Asia/Kathmandu"))
close_today = today.strftime("%b %d, %Y") + " 5:00:00 PM"

ISSUES = [
    {"companyShareId": 1, "scrip": "GOOD", "companyName": "Good Hydro Ltd", "shareTypeName": "IPO", "shareGroupName": "Ordinary Shares", "subGroup": "For General Public", "statusName": "CREATE_APPROVE", "action": None, "issueCloseDate": close_today},
    {"companyShareId": 2, "scrip": "LATER", "companyName": "Later Ltd", "shareTypeName": "IPO", "shareGroupName": "Ordinary Shares", "subGroup": "For General Public", "statusName": "CREATE_APPROVE", "issueCloseDate": "Dec 30, 2099 5:00:00 PM"},
    {"companyShareId": 3, "scrip": "MF1", "companyName": "Some Mutual Fund Scheme", "shareTypeName": "IPO", "shareGroupName": "Mutual Fund", "subGroup": "For General Public", "issueCloseDate": close_today},
    {"companyShareId": 4, "scrip": "DEB", "companyName": "Bank Debenture 2090", "shareTypeName": "IPO", "shareGroupName": "Corporate Debenture", "subGroup": "For General Public", "issueCloseDate": close_today},
    {"companyShareId": 5, "scrip": "FE", "companyName": "Good Hydro Ltd", "shareTypeName": "IPO", "shareGroupName": "Ordinary Shares", "subGroup": "For Nepalese Citizens Working Abroad", "issueCloseDate": close_today},
    {"companyShareId": 6, "scrip": "RGT", "companyName": "Rights Co", "shareTypeName": "RESERVED (RIGHT SHARE)", "shareGroupName": "Ordinary Shares", "issueCloseDate": close_today},
    {"companyShareId": 7, "scrip": "DONE", "companyName": "Done Ltd", "shareTypeName": "IPO", "shareGroupName": "Ordinary Shares", "subGroup": "For General Public", "action": "edit", "issueCloseDate": close_today},
    {"companyShareId": 8, "scrip": "UNDER", "companyName": "Under Ltd", "shareTypeName": "IPO", "shareGroupName": "Ordinary Shares", "subGroup": "For General Public", "issueCloseDate": close_today},
]
applied = []

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
        return Resp({}, headers={"Authorization": "tok"}) if kw["json"]["password"] == "ok" else Resp({"message": "Invalid credentials"}, 401)
    if path == "/meroShare/ownDetail/": return Resp({"demat": "130", "boid": "1301"})
    if path == "/meroShare/companyShare/applicableIssue/": return Resp({"object": ISSUES})
    if path.startswith("/meroShare/active/"): return Resp({"minUnit": 10, "maxUnit": 50000, "multipleOf": 10, "sharePerUnit": 100})
    if path == "/meroShare/bank/": return Resp([{"id": 44, "code": "1", "name": "Nabil Bank"}])
    if path == "/meroShare/bank/44": return Resp([{"id": 9, "accountNumber": "000", "accountBranchId": 3, "accountTypeId": 1}])
    if path == "/meroShare/applicantForm/share/apply":
        applied.append(kw["json"]); return Resp({"message": "Share has been applied successfully."}, 201)
    raise AssertionError(path)

apply_ipo.MeroShare._req = fake
os.environ["ACCOUNTS_JSON"] = json.dumps([
    {"name": "Me", "dp": "13700", "username": "1", "password": "ok", "pin": "1234", "crn": "C1", "max_amount": 5000},
    {"name": "Dad", "dp": "13700", "username": "2", "password": "bad", "pin": "1", "crn": "C2"},
    {"name": "Off", "dp": "13700", "username": "3", "password": "ok", "pin": "1", "crn": "C3", "enabled": False},
])
apply_ipo.load_subscription_data = lambda: {"UNDER": {"times": 0.6}}
rc = apply_ipo.main()

got = {(p["companyShareId"], p["appliedKitta"]) for p in applied}
assert got == {("1", "10"), ("8", "50")}, got   # GOOD min; UNDER capped by max_amount 5000/100
assert rc == 1  # Dad's bad password reported as error, but Me still applied
# kitta unit checks
assert choose_kitta(10, 50000, subscription_times=25).kitta == 10
assert choose_kitta(10, 50000, subscription_times=0.9, account_max_kitta=200).kitta == 200
assert choose_kitta(10, 50000, applicants=1000, units_offered=50000, account_max_kitta=100).kitta == 100
assert choose_kitta(10, 50000, applicants=100000, units_offered=50000).kitta == 10
assert choose_kitta(10, 50000).kitta == 10
print("\nALL TESTS PASSED")
