# MeroShare Auto IPO

Applies on MeroShare for you and your family automatically, on the **last day** of each issue. It runs on GitHub Actions twice a day (10:15 AM and 1:00 PM Nepal time), so no computer needs to be on.

| Issue | What the bot does |
|---|---|
| IPO, ordinary shares, general public | applies (kitta chosen as below) |
| FPO, ordinary shares, general public | applies (kitta chosen as below) |
| Right shares | applies for the **full entitlement** (cap it per person with "Max right share kitta") |
| Mutual funds, debentures, bonds | skipped |
| Reserved quotas (foreign employment, locals, employees) | skipped |
| Already applied | skipped |

## How many kitta for IPOs and FPOs

General-public issues are allotted by lottery, 10 kitta per winner, once they're oversubscribed. Applying for more doesn't improve your odds; it only blocks more money. So:

| Situation | Applies for |
|---|---|
| Subscription unknown (default) or oversubscribed | minimum (10) |
| Undersubscribed (< 1x) | as much as the person's "Max Rs per IPO" allows (you get it all) |
| Few applicants (everyone gets 10 and there's a remainder) | as much as the limit allows |

To use the last two rows, add the issue to `subscription.json` on its last morning, for example `"ABCL": {"times": 0.7}`.

## Settings page (no GitHub needed day to day)

**https://aayushmsd2026-bit.github.io/meroshare-auto-ipo/**

You can do all of this from your phone:
- **Change password:** pick the person and enter the new password.
- **Add / edit person:** a new family member. Search for the DP by name, or enter the 16-digit BOID and the DP is filled in. To edit someone, re-enter all their details under the same name.
- **People:** see who's included and remove someone.
- **Email:** set up email alerts.
- **Test run:** checks every login without applying, then emails you the result.

The page encrypts everything in your browser with GitHub's public key before sending it. Saved values are write-only: nobody, including the page, can read them back. The page itself holds no secrets, which is why it's safe for it to be public.

### One-time: make the access token

1. On GitHub: profile picture → **Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token**.
2. Set **Expiration** to 1 year, and set **Repository access** to *Only select repositories* → `meroshare-auto-ipo`.
3. Under **Repository permissions**, set **Secrets** to *Read and write* and **Actions** to *Read and write*. Leave everything else at *No access*.
4. Generate it, then paste it into the settings page. Each device remembers it.

This token can only overwrite this repo's secrets and start runs. It can't read your passwords. If you lose your phone, delete the token on GitHub.

### One-time: email alerts

Use a Gmail account to send the alerts:
1. Turn on 2-Step Verification for that Google account.
2. Go to <https://myaccount.google.com/apppasswords>, create an app password, and copy the 16 letters.
3. In the settings page, open **Email** and enter the Gmail address, the app password, and the address(es) to send alerts to.

You'll get an email when:
- something is applied, with the scrip, kitta, and amount for each person;
- an application or login fails;
- a **MeroShare password, MeroShare account, or DEMAT account is expiring within 10 days, or has expired**. These warnings come at most once a day, from the morning run, until it's fixed. If a family member has their own email saved, they get their own warning too.

When a password expires: log in at meroshare.cdsc.com.np, change it there, then save the same new password on the settings page.

## Results in the app and phone notifications

Open the app's **Updates** tab to see what happened in each run: who logged in, what was applied, open issues and their closing dates, and anything that needs attention. Tap **Turn on** under *Phone notifications* to get a notification after a run that applied something, had a problem, or found a password about to expire.

How it works without a server: each phone that opens the app gets its own key (saved as a `DEVICE_…` secret). After every run, the bot encrypts the results with each phone's key and saves them to `docs/status.json`. The repo is public, but the file is unreadable without the key that stays on your phone. Notifications are sent by the bot directly through Chrome's push service.

## Running locally (optional)

```bash
pip install -r requirements.txt
cp accounts.example.json accounts.json   # fill in real details; this file is git-ignored
DRY_RUN=true python apply_ipo.py
python tests/test_flow.py                # offline test against a fake MeroShare
pip install http_ece cryptography && python tests/test_status.py   # app results + notifications
```

## Things to know

- **Keep enough balance** in each linked bank account, especially for right shares, which apply for the full entitlement.
- The repo is public, so Actions logs are public. The bot therefore never logs names, usernames, or account numbers; it shows "Account #1", "Account #2", and so on. Details only go out by email.
- If CDSC changes its website API, the bot breaks until the code is updated. Failure emails will tell you.
- Some users report that CDSC blocks traffic from outside Nepal. If logins time out from GitHub, run it on a computer in Nepal instead (see Running locally).
- A small monthly "keepalive" workflow stops GitHub from turning off the schedule after 60 days with no repo activity.
