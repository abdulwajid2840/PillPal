# PillPal

A small Android medicine-reminder app with an AI helper that turns plain-text descriptions (or pasted prescription text) into reminders.

It is inspired by the open-source [MedTimer](https://github.com/Futsch1/medTimer) app. PillPal is **not affiliated with MedTimer** and does not reuse its code. It is an early, personal learning project, not a finished or medically certified product.

## Status

Early version (v1.0). It builds, installs and runs on a real Android phone, and the AI feature works with a Gemini API key. It has not been tested across many devices or Android versions, and it covers only a subset of MedTimer's features (see the comparison below).

## Features

- **Medicines**: name, dose, units per dose, multiple daily times, weekdays, optional last day, optional stock count, notes. Add, edit and delete.
- **Real alarms**: reminders use Android's `AlarmManager` (exact alarms), so they fire when the app is closed, and are rescheduled after a reboot or app update.
- **Notification actions**: each reminder has **Taken** and **Skip** buttons.
- **Today screen**: today's doses in time order, with Taken and Skip buttons.
- **Stock tracking**: marking a dose as Taken reduces stock. A refill warning appears when 5 or fewer units remain.
- **AI add (Gemini)**: type something like `Metformin 500mg after breakfast and dinner for 30 days, 60 tablets`. The app asks Gemini to extract the schedule, shows you the result, and saves only after you confirm.
- **Model fallback**: if the chosen Gemini model is busy or unavailable (HTTP 404, 429, 500, 503, 504), the app retries and tries fallback models.
- **Local storage**: all data is stored on the phone in `SharedPreferences` as JSON.

## Comparison with MedTimer

| Area | PillPal v1.0 |
|---|---|
| Reminders with exact alarms | Yes |
| Taken / Skip from notification | Yes |
| Weekday schedules, end date | Yes |
| Stock and refill warning | Basic |
| AI-assisted adding | Yes (new, not in MedTimer) |
| History and statistics | Not yet |
| Calendar view | Not yet |
| Snooze | Not yet |
| Tags, icons, colors | Not yet |
| As-needed (PRN) medicines | Not yet |
| Interval and cyclic schedules | Not yet |
| Backup / export / import | Not yet |

## How the AI feature works

1. You type or paste a description in the **AI** tab.
2. The app sends it to the Gemini REST API (`generateContent`) with a prompt that asks for a JSON array of medicines (name, dose, units per dose, times, days, duration, stock, notes).
3. The app parses the JSON, shows it, and saves it only after you tap **Save all**.

The AI can be wrong. **Always check the names, doses and times before saving.** The prompt tells the model not to invent medicines or doses, but this is not a guarantee.

### Get a Gemini API key

1. Open [aistudio.google.com](https://aistudio.google.com) and sign in with a Google account.
2. Tap **Get API key**, then **Create API key**.
3. In PillPal, open **Settings**, paste the key and tap **Save**.

A Gemini subscription bundled with a phone plan (for example a telecom offer) usually covers the Gemini app only, not the API. The API key from AI Studio is separate and has its own free tier and limits.

The default model is `gemini-3.5-flash-lite`. Model names change over time. If you see a "model is no longer available" error, change the model name in **Settings**.

## Privacy

- Medicines, schedules and history stay on your device.
- Your API key is stored on the device in app storage (not encrypted by the app).
- Text you send through the AI tab goes to Google's Gemini API. Do not include personal identifiers you are not comfortable sharing.
- The app has no accounts, analytics or backend of its own.

## Medical disclaimer

PillPal is a reminder tool, not medical advice. It does not check doses, interactions or allergies. Follow your doctor's or pharmacist's instructions, and do not rely on this app alone for critical medication.

## How this project was built

The app was built on an Android phone only, with no PC, using AI assistance:

- The code was written with the help of Claude (Anthropic), and then built, installed and tested by the repo owner on a real device.
- Early on, a web-app prototype was made to try out the flow. The native app replaced it.
- Android on-device IDEs were considered, but AndroidIDE's official repository is archived and unmaintained, so the build is done in the cloud instead with **GitHub Actions**.
- Bugs found during real use (Gemini model overload, retired model names) were fixed by adding retries, fallback models and updated defaults.

## Repository layout

The repo is intentionally flat so it can be edited and uploaded from a phone browser. A GitHub Actions step arranges the files into a normal Gradle layout at build time.

```
MainActivity.kt        all app code (UI, storage, alarms, notifications, Gemini)
AndroidManifest.xml    permissions, activity, receivers
app.gradle.kts         copied to app/build.gradle.kts during the build
build.gradle.kts       root Gradle file (plugin versions)
settings.gradle.kts    Gradle settings
.github/workflows/     CI workflow that builds the APK
```

### Code overview (`MainActivity.kt`)

- `Core`: storage helpers, alarm scheduling, notifications, the Gemini request.
- `AlarmReceiver`: shows the notification when an alarm fires and schedules the next one.
- `ActionReceiver`: handles the Taken / Skip notification buttons.
- `BootReceiver`: reschedules alarms after reboot or app update.
- `MainActivity`: a plain `Activity` with a programmatic UI (Today, Meds, AI, Settings). It uses no AndroidX libraries.

### Permissions

`INTERNET` (Gemini), `POST_NOTIFICATIONS` (Android 13+), `SCHEDULE_EXACT_ALARM` (Android 12 and 12L) and `USE_EXACT_ALARM` (Android 13+), and `RECEIVE_BOOT_COMPLETED`.

## Build the APK (no PC needed)

1. Push the files to a GitHub repo, including the workflow at `.github/workflows/`.
2. Open the **Actions** tab. The **Build APK** workflow runs on every push (or use **Run workflow**).
3. When it shows a green tick, open the run and download the **PillPal-apk** artifact (a zip).
4. Extract it, open `app-debug.apk`, and allow installing from unknown sources if asked.

It builds a debug APK with Gradle 8.9, Android Gradle Plugin 8.5.2, Kotlin 2.0.20, JDK 17, compileSdk 34 and minSdk 26. A new APK installs over the old one and keeps your data, as long as it comes from the same build setup.

## Tips and known issues

- On Android 12/12L, open **Settings → Allow exact alarms**.
- Some phones delay alarms because of aggressive battery optimization. If reminders arrive late, turn off battery optimization for PillPal.
- Medicines described as "if fever" or "as needed" are not supported yet. The AI will assign fixed times, so review or delete them.
- Notifications on Android 13+ need the notification permission to be allowed.

## Roadmap

- As-needed (PRN) medicines
- History and adherence statistics
- Backup and restore (export / import)
- Snooze from the notification
- Calendar view, tags and icons
- Interval and cyclic schedules
- Encrypted storage for the API key

## License

No license has been chosen yet. Add one (for example MIT) before others reuse the code.
