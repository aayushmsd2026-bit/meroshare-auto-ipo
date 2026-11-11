"""Automatically apply for equity IPOs on MeroShare for several accounts.

Accounts come from the ACCOUNTS_JSON environment variable (a GitHub secret),
or from accounts.json when running locally. See README.md.
"""

from __future__ import annotations

import json
import os
import sys
import time
from datetime import date, datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import requests

from kitta import choose_kitta

API = "https://webbackend.cdsc.com.np/api"
NPT = ZoneInfo("Asia/Kathmandu")
ROOT = Path(__file__).parent

HEADERS = {
    "Accept": "application/json, text/plain, */*",
    "Content-Type": "application/json",
    "Origin": "https://meroshare.cdsc.com.np",
    "Referer": "https://meroshare.cdsc.com.np/",
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/130.0 Safari/537.36"
    ),
}

DRY_RUN = os.getenv("DRY_RUN", "false").lower() in ("1", "true", "yes")
# "last_day" (default) applies only on the closing day. "any_day" applies as soon as an issue is open.
APPLY_ON = os.getenv("APPLY_ON", "last_day")

LOG_LINES: list[str] = []


def log(msg: str) -> None:
    print(msg, flush=True)
    LOG_LINES.append(msg)


# ---------------------------------------------------------------- filtering

def is_wanted_issue(issue: dict) -> tuple[bool, str]:
    """Only ordinary-share IPOs for the general public. No mutual funds, debentures, FPOs, rights, or reserved quotas."""
    share_type = (issue.get("shareTypeName") or "").upper()
    group = (issue.get("shareGroupName") or "").lower()
    sub_group = (issue.get("subGroup") or "").lower()
    name = (issue.get("companyName") or "").lower()

    if share_type != "IPO":
        return False, f"share type is {share_type or 'unknown'}, not IPO"
    if "mutual" in group or "fund" in group or "scheme" in name:
        return False, "mutual fund"
    if "debenture" in group or "debenture" in name or "bond" in group:
        return False, "debenture/bond"
    if "ordinary" not in group:
        return False, f"share group is {issue.get('shareGroupName')}"
    if sub_group and "general public" not in sub_group:
        return False, f"reserved quota ({issue.get('subGroup')})"
    return True, "equity IPO for general public"


def parse_date(value: str | None) -> date | None:
    if not value:
        return None
    for fmt in ("%b %d, %Y %I:%M:%S %p", "%b %d, %Y", "%Y-%m-%d %H:%M:%S", "%Y-%m-%d", "%Y-%m-%dT%H:%M:%S"):
        try:
            return datetime.strptime(value.strip(), fmt).date()
        except ValueError:
            continue
    try:
        return datetime.fromisoformat(value.strip()).date()
    except ValueError:
        return None


def is_apply_day(issue: dict, today: date) -> tuple[bool, str]:
    close = parse_date(issue.get("issueCloseDate"))
    if APPLY_ON == "any_day":
        return True, "APPLY_ON=any_day"
    if close is None:
        return False, f"could not read close date {issue.get('issueCloseDate')!r}"
    if close == today:
        return True, "closes today"
    return False, f"closes {close.isoformat()}, waiting for the last day"


# ---------------------------------------------------------------- MeroShare client

class MeroShare:
    def __init__(self) -> None:
        self.s = requests.Session()
        self.s.headers.update(HEADERS)

    def _req(self, method: str, path: str, **kw) -> requests.Response:
        for attempt in range(4):
            try:
                r = self.s.request(method, API + path, timeout=30, **kw)
                if r.status_code >= 500:
                    raise requests.HTTPError(f"{r.status_code} from server")
                return r
            except (requests.ConnectionError, requests.Timeout, requests.HTTPError) as e:
                if attempt == 3:
                    raise
                wait = 2 ** (attempt + 1)
                log(f"   network problem ({e}); retrying in {wait}s")
                time.sleep(wait)
        raise RuntimeError("unreachable")

    def dp_id(self, dp: str) -> int:
        r = self._req("GET", "/meroShare/capital/")
        r.raise_for_status()
        dp = str(dp).strip().lower()
        for item in r.json():
            if str(item.get("code")) == dp or str(item.get("id")) == dp or dp in (item.get("name") or "").lower():
                return int(item["id"])
        raise ValueError(f"Depository participant '{dp}' not found. Use the DP code shown on MeroShare login, e.g. 13700.")

    def login(self, dp: str, username: str, password: str) -> None:
        body = {"clientId": self.dp_id(dp), "username": str(username), "password": password}
        r = self._req("POST", "/meroShare/auth/", json=body)
        if r.status_code != 200 or not r.headers.get("Authorization"):
            msg = r.json().get("message") if r.headers.get("content-type", "").startswith("application/json") else r.text[:200]
            raise RuntimeError(f"login failed: {msg}")
        data = r.json() if r.content else {}
        if data.get("passwordExpired") or data.get("changePassword"):
            raise RuntimeError("MeroShare password expired - log in on the website and change it, then update the secret")
        if data.get("accountExpired") or data.get("dematExpired"):
            raise RuntimeError("MeroShare/DEMAT account expired - renew it with your DP")
        self.s.headers["Authorization"] = r.headers["Authorization"]

    def own_detail(self) -> dict:
        r = self._req("GET", "/meroShare/ownDetail/")
        r.raise_for_status()
        return r.json()

    def applicable_issues(self) -> list[dict]:
        body = {
            "filterFieldParams": [
                {"key": "companyIssue.companyISIN.script", "alias": "Scrip"},
                {"key": "companyIssue.companyISIN.company.name", "alias": "Company Name"},
                {"key": "companyIssue.assignedToClient.name", "value": "", "alias": "Issue Manager"},
            ],
            "page": 1,
            "size": 200,
            "searchRoleViewConstants": "VIEW_APPLICABLE_SHARE",
            "filterDateParams": [
                {"key": "minIssueOpenDate", "condition": "", "alias": "", "value": ""},
                {"key": "maxIssueCloseDate", "condition": "", "alias": "", "value": ""},
            ],
        }
        r = self._req("POST", "/meroShare/companyShare/applicableIssue/", json=body)
        r.raise_for_status()
        return r.json().get("object", [])

    def issue_detail(self, company_share_id: int) -> dict:
        r = self._req("GET", f"/meroShare/active/{company_share_id}")
        r.raise_for_status()
        return r.json()

    def bank_account(self, bank_hint: str | None) -> tuple[int, dict]:
        r = self._req("GET", "/meroShare/bank/")
        r.raise_for_status()
        banks = r.json()
        if not banks:
            raise RuntimeError("no bank linked to this MeroShare account")
        bank = banks[0]
        if bank_hint:
            hint = bank_hint.lower()
            matches = [b for b in banks if hint in (b.get("name") or "").lower() or hint == str(b.get("code"))]
            if not matches:
                raise RuntimeError(f"bank '{bank_hint}' not linked; linked: {[b.get('name') for b in banks]}")
            bank = matches[0]
        r = self._req("GET", f"/meroShare/bank/{bank['id']}")
        r.raise_for_status()
        detail = r.json()
        if isinstance(detail, list):
            if not detail:
                raise RuntimeError("bank account details empty")
            detail = detail[0]
        return int(bank["id"]), detail

    def apply(self, payload: dict) -> tuple[bool, str]:
        r = self._req("POST", "/meroShare/applicantForm/share/apply", json=payload)
        try:
            msg = r.json().get("message", r.text[:200])
        except ValueError:
            msg = r.text[:200]
        return r.status_code in (200, 201), msg


# ---------------------------------------------------------------- main logic

def load_accounts() -> list[dict]:
    raw = os.getenv("ACCOUNTS_JSON")
    if not raw:
        path = ROOT / "accounts.json"
        if not path.exists():
            sys.exit("No accounts: set the ACCOUNTS_JSON secret (or create accounts.json locally).")
        raw = path.read_text()
    accounts = json.loads(raw)
    if isinstance(accounts, dict):
        accounts = accounts.get("accounts", [])
    return [a for a in accounts if a.get("enabled", True)]


def load_subscription_data() -> dict:
    """Optional: subscription.json lets you tell the bot how subscribed an issue is, keyed by scrip."""
    path = ROOT / "subscription.json"
    if not path.exists():
        return {}
    data = json.loads(path.read_text())
    return {k.upper(): v for k, v in data.items() if not k.startswith("_")}


def process_account(acc: dict, subs: dict, today: date) -> list[str]:
    label = acc.get("name") or acc.get("username")
    results: list[str] = []
    log(f"\n=== {label} ===")
    ms = MeroShare()
    ms.login(acc["dp"], acc["username"], acc["password"])
    me = ms.own_detail()
    issues = ms.applicable_issues()
    log(f"   {len(issues)} open issue(s) visible")

    bank_cache: tuple[int, dict] | None = None
    for issue in issues:
        scrip = issue.get("scrip", "?")
        title = f"{scrip} ({issue.get('companyName')}, {issue.get('subGroup') or issue.get('shareGroupName')})"

        ok, why = is_wanted_issue(issue)
        if not ok:
            log(f" - skip {title}: {why}")
            continue
        if issue.get("action") in ("edit", "inProcess", "reapply") or issue.get("statusName") not in (None, "", "CREATE_APPROVE"):
            log(f" - skip {title}: already applied ({issue.get('action') or issue.get('statusName')})")
            continue
        ok, why = is_apply_day(issue, today)
        if not ok:
            log(f" - wait {title}: {why}")
            continue

        detail = ms.issue_detail(issue["companyShareId"])
        sub = subs.get(scrip.upper(), {})
        decision = choose_kitta(
            min_unit=detail.get("minUnit", 10),
            max_unit=detail.get("maxUnit", 10),
            multiple_of=detail.get("multipleOf", 10),
            price_per_unit=detail.get("sharePerUnit", 100) or 100,
            subscription_times=sub.get("times"),
            applicants=sub.get("applicants"),
            units_offered=sub.get("units_offered"),
            account_max_kitta=acc.get("max_kitta"),
            account_max_amount=acc.get("max_amount"),
        )
        log(f" + {title}: applying {decision.kitta} kitta - {decision.reason}")

        if bank_cache is None:
            bank_cache = ms.bank_account(acc.get("bank"))
        bank_id, bank = bank_cache

        payload = {
            "demat": me["demat"],
            "boid": me["boid"],
            "accountNumber": bank["accountNumber"],
            "customerId": bank["id"],
            "accountBranchId": bank["accountBranchId"],
            "accountTypeId": bank.get("accountTypeId"),
            "appliedKitta": str(decision.kitta),
            "crnNumber": acc["crn"],
            "transactionPIN": str(acc["pin"]),
            "companyShareId": str(issue["companyShareId"]),
            "bankId": bank_id,
        }
        if DRY_RUN:
            log("   DRY_RUN: not submitting")
            results.append(f"{label}: {scrip} {decision.kitta} kitta (dry run)")
            continue
        success, msg = ms.apply(payload)
        log(f"   {'APPLIED' if success else 'FAILED'}: {msg}")
        results.append(f"{label}: {scrip} {decision.kitta} kitta - {'applied' if success else 'FAILED: ' + msg}")
        if not success:
            raise RuntimeError(f"apply failed for {scrip}: {msg}")
    return results


def notify(text: str) -> None:
    summary = os.getenv("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as f:
            f.write("```\n" + "\n".join(LOG_LINES) + "\n```\n")
    token, chat = os.getenv("TELEGRAM_BOT_TOKEN"), os.getenv("TELEGRAM_CHAT_ID")
    if token and chat and text:
        try:
            requests.post(f"https://api.telegram.org/bot{token}/sendMessage",
                          json={"chat_id": chat, "text": text}, timeout=20)
        except requests.RequestException as e:
            print(f"telegram notify failed: {e}")


def main() -> int:
    today = datetime.now(NPT).date()
    log(f"MeroShare auto-IPO - {today.isoformat()} (Nepal time), mode={APPLY_ON}, dry_run={DRY_RUN}")
    accounts = load_accounts()
    subs = load_subscription_data()
    log(f"{len(accounts)} account(s) enabled")

    applied: list[str] = []
    errors: list[str] = []
    for acc in accounts:
        try:
            applied += process_account(acc, subs, today)
        except Exception as e:  # keep going for the other family members
            label = acc.get("name") or acc.get("username")
            log(f"   ERROR for {label}: {e}")
            errors.append(f"{label}: {e}")

    lines = []
    if applied:
        lines.append("IPO applications:\n" + "\n".join(applied))
    if errors:
        lines.append("Errors:\n" + "\n".join(errors))
    notify("\n\n".join(lines))
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
