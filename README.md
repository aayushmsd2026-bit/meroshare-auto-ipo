# MeroShare Auto IPO

Applies for **equity IPOs (ordinary shares, general public quota)** on MeroShare for you and your family, automatically, on the **last day** of each issue. It runs on GitHub Actions every day, so you don't need a computer on.

**Skipped automatically:** mutual funds, debentures and bonds, right shares, FPOs, reserved quotas (foreign employment, locals, employees), and anything you've already applied for.

## How many kitta it applies for

General-public IPOs in Nepal are allotted by lottery, 10 kitta per winner, once they're oversubscribed. Applying for 50 or 1000 kitta doesn't improve your odds; it only blocks more money in your account. So:

| Situation | Applies for |
|---|---|
| Subscription unknown (default) | minimum (10) |
| Oversubscribed (≥ 1x) | minimum (10) |
| Undersubscribed (< 1x) | as much as your `max_kitta` / `max_amount` allows (you get it all) |
| Few applicants (everyone gets 10 and there's a remainder) | as much as your limits allow |

To use the last two rows, add the issue's numbers to `subscription.json` on the last morning (e.g. `"ABCL": {"times": 0.7}`).

## Setup (about 5 minutes)

1. Create a **private** GitHub repo and push this folder to it.
2. Repo → **Settings → Secrets and variables → Actions → New repository secret**:
   - Name: `ACCOUNTS_JSON`
   - Value: your accounts list, in the format of `accounts.example.json`.
3. Repo → **Actions** → enable workflows → **Auto apply IPO → Run workflow** with *dry run* ticked. Check the log: every account should log in and list the open issues.

That's it. From then on it runs every day at 10:15 AM and 1:00 PM Nepal time and applies only on closing days.

### Account fields

| Field | Required | Meaning |
|---|---|---|
| `name` | | Label for logs, e.g. "Mom" |
| `dp` | ✔ | DP code (e.g. `13700`) or part of the DP name |
| `username` | ✔ | MeroShare username |
| `password` | ✔ | MeroShare password |
| `pin` | ✔ | 4-digit transaction PIN |
| `crn` | ✔ | CRN number from the bank |
| `bank` | | Part of the bank name, if more than one bank is linked |
| `max_kitta` / `max_amount` | | Upper limits for undersubscribed issues |
| `enabled` | | `false` to pause one person |

### Adding or removing a family member

Edit the `ACCOUNTS_JSON` secret (Settings → Secrets → ACCOUNTS_JSON → Update) and add or remove one `{ ... }` block. Nothing else needs to change. Passwords never go in the repo.

### Notifications (optional)

GitHub emails you when a run fails (for example a wrong password or expired account). For a Telegram message after every application, add the secrets `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID`.

## Things to know

- **Keep enough balance** in each linked bank account. If the bank rejects the block, the application fails at the bank's end.
- **MeroShare passwords expire** every few months. When that happens the run fails and you get an email: change the password on MeroShare, then update the secret.
- If CDSC changes its website API, this will break until the code is updated. Check the Actions tab now and then.
- Some users report CDSC blocking traffic from outside Nepal. If logins time out from GitHub, run it on a computer in Nepal instead (`python apply_ipo.py` with an `accounts.json` file next to it, scheduled with Task Scheduler or cron).

## Running locally

```bash
pip install -r requirements.txt
cp accounts.example.json accounts.json   # fill in real details; this file is git-ignored
DRY_RUN=true python apply_ipo.py
python tests/test_flow.py                # offline test against a fake MeroShare
```
