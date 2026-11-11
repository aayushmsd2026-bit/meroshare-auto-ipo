"""Send each run's results to the IPO Helper app.

Each phone or computer that opens the app registers itself as a GitHub secret
named DEVICE_<ID>:
  {"name": "...", "box_pub": "<base64 X25519 public key>",
   "push": {"subscription": {...}, "vapid_private": "<base64url P-256 key>"} or null}

(The workflow passes DEVICE_1 ... DEVICE_6 as environment variables.)
For every device this module:
  1. encrypts the run's results with that device's public key (libsodium sealed
     box) and stores them in docs/status.json. The repo is public, but only the
     device holding the private key can read them.
  2. sends a phone notification (Web Push) when something worth knowing happened.
"""

from __future__ import annotations

import base64
import json
import os
from datetime import datetime
from pathlib import Path

STATUS_FILE = Path(__file__).parent / "docs" / "status.json"
HISTORY = 15  # runs kept per device


def _devices() -> dict[str, dict]:
    from apply_ipo import secret_values
    secrets = secret_values()
    out = {}
    for key, value in secrets.items():
        if key.startswith("DEVICE_"):
            try:
                out[key[len("DEVICE_"):]] = json.loads(value)
            except json.JSONDecodeError:
                continue
    return out


def build_status(report, now: datetime, dry_run: bool, email: dict) -> dict:
    server, repo, run_id = os.getenv("GITHUB_SERVER_URL"), os.getenv("GITHUB_REPOSITORY"), os.getenv("GITHUB_RUN_ID")
    applied = [f"{p['name']}: {a['scrip']} {a['kitta']} kitta" for p in report.people for a in p["applied"]]
    problems = [f"{p['name']}: {p['error']}" for p in report.people if p.get("error")]
    problems += [e for e in report.errors if not any(e.startswith(p["name"] + ":") and p.get("error") for p in report.people)]
    warnings = [f"{p['name']}: {w}" for p in report.people for w in p["warnings"] if not p.get("error")]
    if email.get("error"):
        problems.append(f"Email could not be sent: {email['error']}")
    return {
        "time": now.isoformat(timespec="minutes"),
        "kind": "check" if dry_run else ("manual" if os.getenv("GITHUB_EVENT_NAME") == "workflow_dispatch" else "daily"),
        "ok": not problems,
        "applied": applied,
        "problems": problems,
        "warnings": warnings,
        "people": report.people,
        "email": {"configured": email.get("configured"), "sent": email.get("sent"), "error": email.get("error")},
        "run_url": f"{server}/{repo}/actions/runs/{run_id}" if server and repo and run_id else None,
    }


def notification_for(status: dict, dry_run: bool, morning: bool) -> dict | None:
    if status["problems"]:
        title = "IPO Helper: problem"
        lines = status["problems"]
    elif status["applied"] and not dry_run:
        title = "Applied: " + ", ".join(sorted({a.split(": ")[1].split(" ")[0] for a in status["applied"]}))
        lines = status["applied"]
    elif status["warnings"] and (morning or dry_run):
        title = "IPO Helper: action needed"
        lines = status["warnings"]
    elif dry_run:
        n = len(status["people"])
        title = "Check finished ✓"
        lines = [f"All {n} account{'s' if n != 1 else ''} logged in."] + (
            [f"Would apply: {a}" for a in status["applied"]] or ["Nothing to apply today."])
    else:
        return None
    body = "\n".join(lines[:4]) + (f"\n+{len(lines) - 4} more" if len(lines) > 4 else "")
    return {"title": title, "body": body, "url": "./#updates"}


def _seal(pub_b64: str, data: bytes) -> str:
    from nacl.public import PublicKey, SealedBox
    return base64.b64encode(SealedBox(PublicKey(base64.b64decode(pub_b64))).encrypt(data)).decode()


def _push(push: dict, message: dict, log) -> bool:
    from pywebpush import WebPushException, webpush
    try:
        webpush(
            subscription_info=push["subscription"],
            data=json.dumps(message),
            vapid_private_key=push["vapid_private"],
            # Push services want a contact address here; a generic no-reply one keeps your email private.
            vapid_claims={"sub": "mailto:ipo-helper@users.noreply.github.com"},
            ttl=24 * 3600,
        )
        return True
    except WebPushException as e:
        code = getattr(e.response, "status_code", None)
        log(f"!! notification failed ({code})" + (" - the phone unsubscribed; turn notifications on again in the app" if code in (404, 410) else ""))
    except Exception as e:
        log(f"!! notification failed: {type(e).__name__}: {e}")
    return False


def publish(report, now: datetime, dry_run: bool, morning: bool, email: dict, log=print) -> None:
    devices = _devices()
    if not devices:
        log("(no app devices registered - open the app once to see results there)")
        return
    status = build_status(report, now, dry_run, email)
    payload = json.dumps(status, ensure_ascii=False).encode()

    try:
        current = json.loads(STATUS_FILE.read_text())
    except (FileNotFoundError, json.JSONDecodeError):
        current = {}
    old = current.get("devices", {})
    new = {}
    for dev_id, dev in devices.items():
        if not dev.get("box_pub"):
            continue
        new[dev_id] = ([_seal(dev["box_pub"], payload)] + old.get(dev_id, []))[:HISTORY]
    STATUS_FILE.parent.mkdir(exist_ok=True)
    STATUS_FILE.write_text(json.dumps({"updated": status["time"], "devices": new}, separators=(",", ":")))
    log(f"app updated for {len(new)} device(s)")

    message = notification_for(status, dry_run, morning)
    if message:
        sent = sum(_push(dev["push"], message, log) for dev in devices.values() if dev.get("push"))
        if sent:
            log(f"phone notification sent to {sent} device(s)")
