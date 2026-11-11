"""Automatically apply for IPOs, FPOs and right shares on MeroShare for several accounts.

Accounts are read from GitHub secrets (managed with the app in docs/), one numbered slot per person:
  MS_ACCOUNT_<N>   JSON with name, dp, username, pin, crn, ... (everything except the password)
  MS_PASSWORD_<N>  the MeroShare password
The workflow passes each slot to this script as an environment variable of the same name.
(Locally, ALL_SECRETS='{"MS_ACCOUNT_1": ...}', ACCOUNTS_JSON, or an accounts.json file also work.)
"""

from __future__ import annotations

import json
import os
import smtplib
import sys
import time
from datetime import date, datetime
from email.message import EmailMessage
from pathlib import Path
from zoneinfo import ZoneInfo

import requests

from kitta import choose_kitta
import status as app_status

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
# Warn about expiring passwords/accounts when this many days or fewer are left.
WARN_DAYS = int(os.getenv("WARN_DAYS", "10"))
PAGE_URL = os.getenv("PAGE_URL", "")

LOG_LINES: list[str] = []


def log(msg: str) -> None:
    """Logs are public on a public repo, so they never contain names, usernames or numbers."""
    print(msg, flush=True)
    LOG_LINES.append(msg)


# ---------------------------------------------------------------- filtering

def classify_issue(issue: dict) -> tuple[str | None, str]:
    """Return (kind, reason). kind is "IPO", "FPO", "RIGHT" or None (skip).

    Applied: ordinary-share IPOs and FPOs for the general public, and right shares.
    Skipped: mutual funds, debentures/bonds, reserved quotas (foreign employment, locals, employees), others.
    """
    share_type = (issue.get("shareTypeName") or "").upper()
    group = (issue.get("shareGroupName") or "").lower()
    sub_group = (issue.get("subGroup") or "").lower()
    name = (issue.get("companyName") or "").lower()

    if "mutual" in group or "fund" in group or "scheme" in name:
        return None, "mutual fund"
    if "debenture" in group or "debenture" in name or "bond" in group:
        return None, "debenture/bond"
    if "RIGHT" in share_type or "right" in sub_group:
        return "RIGHT", "right share"
    if share_type not in ("IPO", "FPO"):
        return None, f"share type is {share_type or 'unknown'}"
    if group and "ordinary" not in group:
        return None, f"share group is {issue.get('shareGroupName')}"
    if sub_group and "general public" not in sub_group:
        return None, f"reserved quota ({issue.get('subGroup')})"
    return share_type, f"equity {share_type} for general public"


def parse_date(value) -> date | None:
    if value in (None, ""):
        return None
    if isinstance(value, (int, float)):  # epoch millis
        return datetime.fromtimestamp(value / 1000, NPT).date()
    value = str(value).strip()
    for fmt in ("%b %d, %Y %I:%M:%S %p", "%b %d, %Y", "%Y-%m-%d %H:%M:%S", "%Y-%m-%d", "%Y-%m-%dT%H:%M:%S"):
        try:
            return datetime.strptime(value, fmt).date()
        except ValueError:
            continue
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00")).date()
    except ValueError:
        return None


def is_apply_day(issue: dict, today: date) -> tuple[bool, str]:
    close = parse_date(issue.get("issueCloseDate"))
    if APPLY_ON == "any_day":
        return True, "APPLY_ON=any_day"
    if close is None:
        return False, f"could not read close date {issue.get('issueCloseDate')!r}"
    if close <= today:
        return True, "closes today"
    return False, f"closes {close.isoformat()}, waiting for the last day"


def expiry_warnings(*sources: dict, today: date) -> list[str]:
    """Find password / account / demat expiry dates in MeroShare responses."""
    labels = {"password": "MeroShare password", "account": "MeroShare account", "demat": "DEMAT account"}
    found: dict[str, date] = {}
    for src in sources:
        for key, val in (src or {}).items():
            k = key.lower()
            if "expir" not in k or isinstance(val, bool):
                continue
            d = parse_date(val)
            if not d:
                continue
            what = next((v for p, v in labels.items() if p in k), "MeroShare " + key)
            found[what] = min(d, found.get(what, d))
    out = []
    for what, d in sorted(found.items()):
        days = (d - today).days
        if days < 0:
            out.append(f"{what} expired on {d.isoformat()}")
        elif days <= WARN_DAYS:
            out.append(f"{what} expires in {days} day(s) on {d.isoformat()}")
    return out


# ---------------------------------------------------------------- MeroShare client

class LoginError(RuntimeError):
    pass


class MeroShare:
    def __init__(self) -> None:
        self.s = requests.Session()
        self.s.headers.update(HEADERS)
        self.auth_data: dict = {}

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

    @staticmethod
    def _message(r: requests.Response) -> str:
        try:
            return r.json().get("message") or r.text[:200]
        except ValueError:
            return r.text[:200]

    def dp_id(self, dp: str) -> int:
        r = self._req("GET", "/meroShare/capital/")
        r.raise_for_status()
        dp = str(dp).strip().lower()
        for item in r.json():
            if str(item.get("code")) == dp or str(item.get("id")) == dp or dp in (item.get("name") or "").lower():
                return int(item["id"])
        raise LoginError(f"Depository participant '{dp}' not found. Use the DP code from the MeroShare login page, e.g. 13700.")

    def login(self, dp: str, username: str, password: str) -> dict:
        body = {"clientId": self.dp_id(dp), "username": str(username), "password": password}
        r = self._req("POST", "/meroShare/auth/", json=body)
        if r.status_code != 200 or not r.headers.get("Authorization"):
            raise LoginError(f"login failed: {self._message(r)}")
        data = r.json() if r.content else {}
        self.auth_data = data
        if data.get("passwordExpired") or data.get("changePassword"):
            raise LoginError("MeroShare password has expired")
        if data.get("accountExpired"):
            raise LoginError("MeroShare account has expired - renew it with your DP")
        if data.get("dematExpired"):
            raise LoginError("DEMAT account has expired - renew it with your DP")
        self.s.headers["Authorization"] = r.headers["Authorization"]
        return data

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
                raise RuntimeError(f"bank '{bank_hint}' is not linked; linked banks: {[b.get('name') for b in banks]}")
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
        return r.status_code in (200, 201), self._message(r)


# ---------------------------------------------------------------- accounts

def _secret_accounts(secrets: dict) -> list[dict]:
    accounts = []
    for key, value in secrets.items():
        if not key.startswith("MS_ACCOUNT_"):
            continue
        k = key[len("MS_ACCOUNT_"):]
        try:
            acc = json.loads(value)
        except json.JSONDecodeError:
            log(f"!! secret {key} is not valid JSON - skipped")
            continue
        acc.setdefault("name", k.title())
        acc["password"] = secrets.get(f"MS_PASSWORD_{k}", acc.get("password"))
        acc["_key"] = k
        accounts.append(acc)
    return accounts


SECRET_PREFIXES = ("MS_ACCOUNT_", "MS_PASSWORD_", "DEVICE_")


def secret_values() -> dict:
    """Secrets from environment variables (as the workflow passes them), plus ALL_SECRETS for local runs."""
    out = json.loads(os.getenv("ALL_SECRETS") or "{}")
    out.update({k: v for k, v in os.environ.items() if k.startswith(SECRET_PREFIXES) and v})
    return out


def load_accounts() -> list[dict]:
    accounts: list[dict] = []
    secrets = secret_values()
    accounts += _secret_accounts(secrets)

    raw = os.getenv("ACCOUNTS_JSON") or secrets.get("ACCOUNTS_JSON")
    if not raw and not accounts and (ROOT / "accounts.json").exists():
        raw = (ROOT / "accounts.json").read_text()
    if raw:
        extra = json.loads(raw)
        accounts += extra.get("accounts", []) if isinstance(extra, dict) else extra

    if not accounts:
        log("No accounts found. Add people in the app (Family → Add a person).")
    return [a for a in accounts if a.get("enabled", True) is not False]


def load_subscription_data() -> dict:
    """Optional: subscription.json tells the bot how subscribed an issue is, keyed by scrip."""
    path = ROOT / "subscription.json"
    if not path.exists():
        return {}
    data = json.loads(path.read_text())
    return {k.upper(): v for k, v in data.items() if not k.startswith("_")}


def right_share_kitta(issue: dict, detail: dict, acc: dict) -> int:
    """Right shares: apply for the full entitlement (the highest amount MeroShare allows)."""
    for src in (detail, issue):
        for key in ("eligibleKitta", "rightShareEligibleKitta", "eligibleUnit", "maxUnit"):
            if src.get(key):
                kitta = int(float(src[key]))
                if acc.get("right_max_kitta"):
                    kitta = min(kitta, int(acc["right_max_kitta"]))
                return kitta
    raise RuntimeError("could not find the entitled kitta for this right share")


# ---------------------------------------------------------------- main logic

class Report:
    def __init__(self) -> None:
        self.applied: list[str] = []
        self.errors: list[str] = []
        self.warnings: dict[str, list[str]] = {}  # person name -> warnings
        self.people: list[dict] = []  # structured per-person results for the app
        self.email_error: str | None = None
        self.email_sent = False


def process_account(idx: int, acc: dict, subs: dict, today: date, report: Report) -> None:
    person = acc.get("name") or f"Account {idx}"
    entry = {"name": person, "slot": acc.get("_key"), "ok": False, "error": None, "warnings": [], "applied": [], "issues": []}
    report.people.append(entry)
    log(f"\n=== Account #{idx} ===")
    for field in ("dp", "username", "password", "pin", "crn"):
        if not acc.get(field):
            raise RuntimeError(f"'{field}' is missing - fix it on the settings page")

    ms = MeroShare()
    try:
        auth = ms.login(acc["dp"], acc["username"], acc["password"])
    except LoginError as e:
        msg = str(e)
        if "expired" in msg.lower() or "credential" in msg.lower() or "password" in msg.lower():
            w = f"{msg}. Log in at https://meroshare.cdsc.com.np, set a new password, then save it in the app."
            report.warnings.setdefault(person, []).append(w)
            entry["warnings"].append(w)
        raise
    entry["ok"] = True
    me = ms.own_detail()
    for w in expiry_warnings(auth, me, today=today):
        log(f"   warning: {w}")
        report.warnings.setdefault(person, []).append(w)
        entry["warnings"].append(w)

    issues = ms.applicable_issues()
    log(f"   {len(issues)} open issue(s) visible")

    bank_cache: tuple[int, dict] | None = None
    for issue in issues:
        scrip = issue.get("scrip", "?")
        title = f"{scrip} ({issue.get('companyName')}, {issue.get('shareTypeName')}, {issue.get('subGroup') or issue.get('shareGroupName')})"
        close = parse_date(issue.get("issueCloseDate"))
        item = {"scrip": scrip, "company": issue.get("companyName"), "type": issue.get("shareTypeName"),
                "group": issue.get("subGroup") or issue.get("shareGroupName"),
                "close": close.isoformat() if close else None, "state": "", "note": ""}
        entry["issues"].append(item)

        kind, why = classify_issue(issue)
        if not kind:
            log(f" - skip {title}: {why}")
            item.update(state="skip", note=why)
            continue
        if issue.get("action") in ("edit", "inProcess", "reapply") or issue.get("statusName") not in (None, "", "CREATE_APPROVE"):
            log(f" - skip {title}: already applied ({issue.get('action') or issue.get('statusName')})")
            item.update(state="done", note="already applied")
            continue
        ok, why = is_apply_day(issue, today)
        if not ok:
            log(f" - wait {title}: {why}")
            item.update(state="wait", note="will apply on the last day")
            continue

        detail = ms.issue_detail(issue["companyShareId"])
        if kind == "RIGHT":
            kitta, reason = right_share_kitta(issue, detail, acc), "right share - full entitlement"
        else:
            sub = subs.get(scrip.upper(), {})
            d = choose_kitta(
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
            kitta, reason = d.kitta, d.reason
        price = float(detail.get("sharePerUnit") or 100)
        log(f" + {title}: applying {kitta} kitta (Rs {kitta * price:,.0f}) - {reason}")

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
            "appliedKitta": str(kitta),
            "crnNumber": acc["crn"],
            "transactionPIN": str(acc["pin"]),
            "companyShareId": str(issue["companyShareId"]),
            "bankId": bank_id,
        }
        line = f"{person}: {scrip} ({kind}) - {kitta} kitta, Rs {kitta * price:,.0f}"
        item.update(kitta=kitta, amount=round(kitta * price), reason=reason)
        if DRY_RUN:
            log("   DRY_RUN: not submitting")
            report.applied.append(line + " [test run, not submitted]")
            item.update(state="would", note=f"would apply {kitta} kitta (test)")
            continue
        success, msg = ms.apply(payload)
        log(f"   {'APPLIED' if success else 'FAILED'}: {msg}")
        if success:
            report.applied.append(line)
            entry["applied"].append({"scrip": scrip, "kind": kind, "kitta": kitta, "amount": round(kitta * price)})
            item.update(state="applied", note=f"applied {kitta} kitta")
        else:
            report.errors.append(f"{person}: {scrip} failed - {msg}")
            item.update(state="failed", note=msg)


# ---------------------------------------------------------------- notifications

EMAIL_RESULT: dict = {"sent": 0, "error": None, "configured": bool(os.getenv("EMAIL_USER") and os.getenv("EMAIL_APP_PASSWORD"))}


def send_email(to: list[str], subject: str, body: str) -> None:
    user, pw = os.getenv("EMAIL_USER"), os.getenv("EMAIL_APP_PASSWORD")
    to = [t.strip() for t in to if t and t.strip()]
    if not (user and pw and to):
        log("(email not configured - skipping email)")
        return
    msg = EmailMessage()
    msg["From"], msg["To"], msg["Subject"] = user, ", ".join(sorted(set(to))), subject
    msg.set_content(body)
    host = os.getenv("EMAIL_SMTP_HOST", "smtp.gmail.com")
    try:
        with smtplib.SMTP_SSL(host, int(os.getenv("EMAIL_SMTP_PORT", "465")), timeout=30) as s:
            s.login(user, pw)
            s.send_message(msg)
        log(f"email sent: {subject}")
        EMAIL_RESULT["sent"] += 1
    except Exception as e:
        log(f"!! email failed: {e}")
        EMAIL_RESULT["error"] = str(e)[:300]


def notify(report: Report, accounts: list[dict], morning: bool) -> None:
    summary = os.getenv("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as f:
            f.write("```\n" + "\n".join(LOG_LINES) + "\n```\n")

    owner = (os.getenv("EMAIL_TO") or "").split(",")
    footer = f"\n\nSettings page: {PAGE_URL}" if PAGE_URL else ""

    # Warnings: only on the morning run (or a manual run), so you get at most one email a day.
    if report.warnings and (morning or DRY_RUN):
        by_person = {a.get("name"): a.get("email") for a in accounts}
        text = "\n\n".join(f"{p}:\n  - " + "\n  - ".join(ws) for p, ws in report.warnings.items())
        family = [by_person.get(p) for p in report.warnings]
        send_email(owner + family, "MeroShare: password / account needs attention",
                   "Action needed:\n\n" + text + footer)

    parts = []
    if report.applied:
        parts.append(("Test run - would apply" if DRY_RUN else "Applied") + ":\n  - " + "\n  - ".join(report.applied))
    if report.errors:
        parts.append("Problems:\n  - " + "\n  - ".join(report.errors))
    if parts or DRY_RUN:
        subject = "MeroShare test run" if DRY_RUN else (
            "MeroShare: application problem" if report.errors else "MeroShare: applied " + ", ".join(
                sorted({a.split(": ")[1].split(" ")[0] for a in report.applied})))
        body = "\n\n".join(parts) or "Test run finished. Every account logged in and nothing is due today."
        send_email(owner, subject, body + footer)

    token, chat = os.getenv("TELEGRAM_BOT_TOKEN"), os.getenv("TELEGRAM_CHAT_ID")
    if token and chat and parts:
        try:
            requests.post(f"https://api.telegram.org/bot{token}/sendMessage",
                          json={"chat_id": chat, "text": "\n\n".join(parts)}, timeout=20)
        except requests.RequestException as e:
            print(f"telegram notify failed: {e}")


def main() -> int:
    now = datetime.now(NPT)
    today = now.date()
    morning = now.hour < 12
    log(f"MeroShare auto-apply - {now:%Y-%m-%d %H:%M} Nepal time, mode={APPLY_ON}, dry_run={DRY_RUN}")
    accounts = load_accounts()
    subs = load_subscription_data()
    log(f"{len(accounts)} account(s) enabled")

    report = Report()
    if not accounts:
        report.errors.append("Nobody is added yet. Open the app and add your family (Family → Add a person).")
    for i, acc in enumerate(accounts, 1):
        try:
            process_account(i, acc, subs, today, report)
        except Exception as e:  # keep going for the other family members
            log(f"   ERROR for account #{i}: {type(e).__name__}")
            report.errors.append(f"{acc.get('name') or f'Account {i}'}: {e}")
            if report.people and report.people[-1]["name"] == (acc.get("name") or f"Account {i}"):
                report.people[-1]["error"] = str(e)

    notify(report, accounts, morning)
    try:
        app_status.publish(report, now=now, dry_run=DRY_RUN, morning=morning, email=EMAIL_RESULT, log=log)
    except Exception as e:  # never let the app update break the run
        log(f"!! could not update the app: {type(e).__name__}: {e}")
    return 1 if report.errors else 0


if __name__ == "__main__":
    sys.exit(main())
