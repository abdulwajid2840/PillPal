# Screenshots

<table align="center">
  <tr>
    <td width="33%" align="center"><b>Today</b></td>
    <td width="33%" align="center"><b>Medicines</b></td>
    <td width="33%" align="center"><b>Ask AI</b></td>
  </tr>
  <tr>
    <td valign="top"><img src="https://github.com/user-attachments/assets/8dc7e324-eaa5-4e64-b783-f6722f1d7016" alt="Today Screen" /></td>
    <td valign="top"><img src="https://github.com/user-attachments/assets/059b33a3-8e1a-4c4d-ba69-a7e8c57b05e9" alt="Medicines" /></td>
    <td valign="top"><img src="https://github.com/user-attachments/assets/49c80ad0-7b40-4dd0-a4fa-8043e103aec7" alt="Ask AI" /></td>
  </tr>
  <tr>
    <td width="33%" align="center"><b>History</b></td>
    <td width="33%" align="center"><b>Settings</b></td>
    <td width="33%" align="center"><b>Edit Medicine</b></td>
  </tr>
  <tr>
    <td valign="top"><img src="https://github.com/user-attachments/assets/a33a14bf-b38a-4aa9-9758-84997618c370" alt="History" /></td>
    <td valign="top"><img src="https://github.com/user-attachments/assets/22ca1b70-be72-4985-b84a-1c73d12c05fd" alt="Settings" /></td>
    <td valign="top"><img src="https://github.com/user-attachments/assets/6d263ae0-535f-4e9c-b941-aeeb497bf390" alt="Edit Medicine" /></td>
  </tr>
</table>


# PillPal

**Private, safe and lightweight medication reminders for Android.**

PillPal keeps your medicine schedule on your phone, reminds you at the right time, and keeps a clear record of what you took. There are no accounts, no ads and no analytics. An optional AI assistant can turn a plain sentence or a prescription into a ready-to-review schedule.

---

## Why PillPal

- **Private by design.** Medicines, schedules and history are stored on your device only. Nothing is uploaded to a PillPal server, because there isn't one.
- **Safe by default.** Your API key is encrypted with the Android Keystore. Backups can be encrypted with a password you choose.
- **Lightweight.** A single-purpose app with no third-party libraries, no sign-up and no background services beyond Android's own alarm system.
- **Flexible schedules.** Fixed times, repeating intervals, every N days, on/off cycles and as-needed medicines.
- **AI assistance you control.** The AI proposes, you review, edit and choose what gets added.

## Features

### Reminders
- Exact alarms through Android's `AlarmManager`, so reminders arrive when the app is closed and are restored after a reboot or update.
- Notification actions: **Taken**, **Skip** and **Snooze 10 min**.
- Times are shown in 12-hour format (AM/PM).

### Flexible scheduling
| Type | Example |
|---|---|
| Fixed times | 8:00 AM and 8:00 PM |
| Repeating | Every 8 hours, or every 30 minutes between 9:00 AM and 6:00 PM |
| As needed | Logged manually with one tap |
| Day patterns | Selected weekdays, every N days, or an on/off cycle such as 5 days on, 2 days off |
| Duration | Start and end dates with a calendar range picker, or quick choices of 3, 5, 7, 14 and 30 days |

### Today screen
- Progress for the day, a highlighted next dose and a weekly adherence overview with streak.
- Search by name, dose, time, notes or status.
- Stock tracking with low-stock warnings.

### History
- A compact, one-line log of every action with the exact time: taken, skipped, snoozed or undone.
- Shows the scheduled time and whether the dose was on time, early or late.
- Filter by status, search by medicine or day, and clear the log when you want.

### Ask AI
- Describe a medicine in plain language, or paste prescription text.
- Review every suggestion, **edit it with the pencil icon**, and add **only the ones you select** (or all at once).
- A reminder to double-check against your prescription is always shown before you add anything.
- Uses the Gemini API with a key you provide.

### Backup and restore
- Export and import all medicines and history as a file.
- Optional password protection using AES-256-GCM with a PBKDF2-HMAC-SHA256 derived key.
- Your API key is never included in a backup.

### Design
- Light and dark themes, following the system or set manually.
- Search, calendar range picker, colour labels and a floating navigation bar.

## Privacy and security

| Area | What PillPal does |
|---|---|
| Data location | Stored locally in the app's private storage |
| Accounts and tracking | None. No analytics, no advertising |
| AI requests | Only the text you submit in **Ask AI** is sent to Google's Gemini API, and only when you tap Create with AI |
| API key | Encrypted at rest with AES-256-GCM using a non-exportable Android Keystore key |
| Backups | Optional password encryption (AES-256-GCM, PBKDF2-HMAC-SHA256, 120,000 iterations) |
| Permissions | Internet (AI requests only), notifications, exact alarms and boot completed |

PillPal has not had a formal third-party security audit.

## Get started

1. Install the APK from the latest build (see **Build from source** below).
2. Allow notifications. On Android 12 or 12L, also allow exact alarms from **Settings**.
3. Add a medicine from **Medicines**, or describe it in **Ask AI**.

### Get a Gemini API key

The AI assistant is optional and needs your own API key.

1. Open [aistudio.google.com](https://aistudio.google.com) and sign in with a Google account.
2. Select **Get API key**, then **Create API key**.
3. In PillPal, open **Settings → Gemini API**, paste the key and tap **Save**.

A Gemini subscription that comes with a phone plan usually covers the Gemini app only, not the API. The key from Google AI Studio is separate and has its own free usage limits.

The default model is `gemini-3.5-flash-lite`. Model names change over time, so if you see a "model not available" message, update the model name in Settings. PillPal also retries and falls back to alternative models when one is busy.

## Medical disclaimer

PillPal is a reminder and record-keeping tool. It does not provide medical advice and does not check doses, interactions or allergies. AI suggestions can be wrong. Always verify names, doses and timings against your prescription or with your doctor or pharmacist. PillPal shows a warning for unusually high doses or very frequent reminders, but these warnings are not a substitute for professional guidance.

## How it works

- **Language and UI:** Kotlin with the plain Android framework (no AndroidX or third-party dependencies).
- **Storage:** `SharedPreferences` with JSON for medicines, the daily log and the history events.
- **Scheduling:** `AlarmManager` exact alarms. Repeating schedules chain the next alarm after each reminder.
- **Receivers:** an alarm receiver (shows reminders), an action receiver (Taken, Skip, Snooze) and a boot receiver (reschedules alarms).
- **AI:** a single REST call to the Gemini API that returns structured JSON, validated before it reaches the review screen.

### Repository layout

The repository is intentionally flat so it can be maintained from a phone browser. A GitHub Actions step arranges it into a standard Gradle project at build time.

```
MainActivity.kt        application code (UI, storage, alarms, notifications, encryption, AI)
AndroidManifest.xml    permissions, activity and receivers
app.gradle.kts         module build file (copied to app/ during the build)
build.gradle.kts       root build file
settings.gradle.kts    Gradle settings
debug.keystore         fixed signing key so updates install over previous builds
res.zip                icons and launcher assets (unpacked during the build)
.github/workflows/     CI workflow that builds the APK
```

## Build from source

No computer is required.

1. Fork or upload the files to a GitHub repository, including the workflow at `.github/workflows/build.yml`.
2. Open the **Actions** tab. The **Build APK** workflow runs on every push.
3. When the run shows a green tick, download the **PillPal-apk** artifact, extract it and install `app-debug.apk`.

Requirements used by the workflow: JDK 17, Gradle 8.9, Android Gradle Plugin 8.5.2, Kotlin 2.0.20, compileSdk 34, minSdk 26.

If the build fails, the compiler errors are shown in the **Annotations** section at the top of the run page.

## Known limitations

- Very short repeat intervals (for example every 5 minutes) can be delayed by Android's battery saving while the phone is idle, and they use more battery. Disable battery optimization for PillPal if reminders arrive late.
- History starts from the version that introduced it. Earlier activity is not backfilled.
- The signing key in this repository is a debug key. Treat the APK as a personal build, not a store release.

## Roadmap

- Full history calendar with adherence reports
- Refill reminders with a configurable threshold
- Multiple profiles, for example for family members
- Export of history as CSV or PDF

## Acknowledgements

PillPal is inspired by the open-source [MedTimer](https://github.com/Futsch1/medTimer) project. It is an independent project, is not affiliated with MedTimer and does not reuse its code.

The application was developed by [@abdulwajid2840](https://github.com/abdulwajid2840) with AI-assisted coding (Claude by Anthropic), and tested on a real Android device.

---

Made with ♥ by [@abdulwajid2840](https://github.com/abdulwajid2840)
