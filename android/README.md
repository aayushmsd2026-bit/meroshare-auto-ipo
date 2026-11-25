# IPO Helper for Android

Applies for IPOs, FPOs and right shares on MeroShare for your family, from your phone.

- `core/`: the logic (MeroShare client, which issues to apply for, kitta, when to apply, notifications). Plain Kotlin with tests: `./gradlew -p core test`.
- `app/`: the Android app: encrypted storage on the phone, an hourly background job, notifications, and the screens (`app/src/main/assets/index.html`).

Built and published by `.github/workflows/android-apk.yml` to
https://github.com/aayushmsd2026-bit/meroshare-auto-ipo/releases/latest/download/ipo-helper.apk
