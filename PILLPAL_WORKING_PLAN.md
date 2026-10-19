# PillPal --- Persistent Project Working Plan

*Last updated: 2026-10-19*

## 1. Mission

Continue developing the existing PillPal Android application
incrementally. Preserve working features and the app's existing visual
identity while improving the UI where useful. Do not rewrite the
application from scratch.

Use the uploaded `PillPal-main.zip` as the initial baseline. Before each
substantial change, confirm the latest source state because commits made
to GitHub after the ZIP was uploaded are not automatically reflected
here.

## 2. Agreed working rules

-   **Preserve existing behavior:** Do not remove or redesign working
    features unless the user explicitly approves it.
-   **Visual direction:** Improve the UI while retaining PillPal's
    current visual identity.
-   **Implementation style:** Prefer small, focused, reviewable changes
    over broad rewrites.
-   **Build environment:** GitHub Actions is the primary build workflow.
-   **Keep it lightweight:** Avoid adding libraries, services,
    frameworks, or architectural complexity without a clear benefit.
-   **Review before implementation:** Agree on the first feature/task
    before changing application code.
-   **Regression awareness:** Identify the existing behavior affected by
    each change and define how to verify it.
-   **Honest verification:** Clearly distinguish code inspection,
    successful CI compilation, and actual on-device testing. Never claim
    a build or device test passed unless it did.
-   **Health safety:** PillPal is a reminder and record-keeping tool,
    not a diagnostic or prescribing system. AI suggestions must remain
    reviewable and must not be presented as medically verified.
-   **Secrets:** Never request or commit API keys, passwords, signing
    secrets, or personal health data. Do not use the repository's debug
    signing key for a production release.

## 3. Initial source baseline

The uploaded repository is intentionally flat. GitHub Actions arranges
it into a Gradle Android project during the build.

Main files: - `MainActivity.kt` --- main application implementation; a
large Kotlin file containing UI, data handling, alarms, notifications,
encryption and Gemini integration. - `AndroidManifest.xml` ---
permissions, activity and receivers. - `app.gradle.kts`,
`build.gradle.kts`, `settings.gradle.kts` --- Gradle configuration. -
`.github/workflows/build.yml` --- CI build workflow. - `res.zip` ---
Android resources and launcher assets. - `README.md` --- feature,
privacy, build and roadmap documentation. - `debug.keystore` ---
repository debug signing key; appropriate only for personal/debug use,
not a production release identity.

Documented stack and build settings: - Kotlin and the plain Android
framework. - No AndroidX or third-party application libraries are
documented. - `SharedPreferences` with JSON for medicines, daily logs
and history. - `AlarmManager` and broadcast receivers for reminder
delivery and reboot rescheduling. - Optional Gemini REST API
integration. - JDK 17, Gradle 8.9, Android Gradle Plugin 8.5.2, Kotlin
2.0.20, compileSdk 34, minSdk 26.

These details describe the uploaded baseline and README; confirm against
the latest repository before relying on them for a change.

## 4. Existing features to preserve unless explicitly changed

-   Medication creation and editing.
-   Fixed-time, interval, as-needed, weekday, every-N-days, on/off-cycle
    and date-range schedules.
-   Today screen, dose progress, next dose, adherence overview, search
    and stock warnings.
-   Notifications and Taken, Skip, Snooze and Undo actions where
    supported.
-   History with action timestamps, search and filters.
-   Optional Gemini-assisted schedule suggestions with user
    review/edit/selection before adding.
-   Export/import backup, optional password protection, and exclusion of
    the API key from backups.
-   Light/dark/system theme options and existing navigation and visual
    identity.
-   GitHub Actions APK build and downloadable artifact workflow.

Do not assume every feature is bug-free simply because it exists;
changes should preserve intended behavior while fixing verified defects.

## 5. Roadmap currently documented in README

The user has not yet supplied a new feature wishlist. The existing
README lists: 1. Full history calendar with adherence reports. 2. Refill
reminders with a configurable threshold. 3. Multiple profiles, for
example family members. 4. CSV or PDF history export.

Treat this as the repository's documented roadmap, not as a confirmed
priority order. Ask the user which item, if any, they want to work on
first.

## 6. Initial review findings to track

These are issues flagged during the initial source review. Reconfirm
them against the latest source and tests before editing; some behavior
requires runtime verification.

### High-priority candidates

-   The API-key encryption path appears to fall back to storing
    plaintext if encryption fails.
-   `android:allowBackup="true"` is enabled while preferences contain
    health-related records; define and test the backup/device-transfer
    privacy policy.
-   Reminder behavior needs systematic testing for reboot, app update,
    denied notification/exact-alarm access, Doze, wall-clock/time-zone
    changes, and duplicate/stale alarms.
-   Schedule inputs and imported/AI-generated data need shared, strict
    validation.
-   Backup import should validate the full file before replacing
    existing data.
-   The dose log may associate an action with the current date rather
    than the intended scheduled date, especially after midnight.
-   Repeating-interval Skip behavior may not be recorded consistently.
-   Interval parsing may permit overflow or unreasonable values.
-   Clearing history may clear the event timeline but leave the separate
    dose-status log.
-   Gemini requests need bounded timeouts/retries and strict response
    validation.
-   The committed debug keystore must not be treated as a production
    signing credential.

### Maintainability

`MainActivity.kt` combines many responsibilities. Prefer extracting
focused components only when needed for a task, for example: -
`MedicineValidator` - `MedicineRepository` - `ReminderScheduler` -
`Crypto` - `GeminiClient`

Keep the app lightweight and retain `SharedPreferences` initially unless
a concrete requirement justifies a storage migration.

## 7. Incremental development workflow

For each feature or bug fix:

1.  **Confirm baseline:** Use the latest repository source, not an old
    ZIP if newer commits exist.
2.  **Define scope:** State the user-visible behavior, non-goals,
    affected screens/files, and acceptance criteria.
3.  **Inspect dependencies:** Trace relevant functions, data structures,
    receivers, permissions and existing behavior before editing.
4.  **Plan the smallest safe change:** Avoid unrelated cleanup and broad
    rewrites.
5.  **Implement:** Make a focused patch or provide complete replacement
    files only when that is the safest practical method.
6.  **Review diff:** Check for accidental deletions, API/key leakage,
    changed data formats, alarm identity changes, and unintended UI
    changes.
7.  **Verify:** Run available static checks/tests; trigger GitHub
    Actions and inspect build output. Test on the user's device when
    runtime behavior matters.
8.  **Regression checklist:** Verify affected existing features and
    relevant edge cases.
9.  **Report status:** Summarize files changed, behavior changed,
    test/build results, remaining risks and any manual test steps.
10. **Update this plan:** Record decisions, completed work, outstanding
    issues and the next agreed task.

Do not proceed to a new feature until the user has reviewed the result
or explicitly asks to batch related changes.

## 8. Verification levels

Always label verification accurately: - **Source reviewed:** Code was
inspected; no claim that it runs. - **Automated checks passed:** Name
the actual checks that ran. - **GitHub Actions build passed:** The
relevant workflow completed successfully on the relevant commit. -
**Device tested:** State the Android version/device and scenarios
actually tested. - **Not verified:** Explicitly list runtime or device
behaviors that remain untested.

## 9. Information still needed from the user

The following are open questions, not assumptions:

1.  **First task / priorities:** Which feature or bug should be tackled
    first? Is the README roadmap still relevant?
2.  **Current repository state:** Has GitHub changed since the uploaded
    ZIP? If so, provide the latest ZIP/commit or ensure the connected
    repository can be read.
3.  **Claude handoff:** Any decisions, unfinished work, rejected
    approaches, known bugs, or prompts from prior Claude sessions that
    should be preserved.
4.  **Design constraints:** Which screens or visual elements are
    especially important to retain? Screenshots or design references are
    helpful.
5.  **Device/testing details:** Android version(s), manufacturer/device
    if relevant, and whether you can install test APKs.
6.  **Privacy and backup policy:** Should Android cloud/device-transfer
    backups be disabled, or should any specific data be included? Should
    exports default to password-protected?
7.  **AI behavior:** Which Gemini models are currently working for you,
    and what limits or failures have you observed? Do not share the API
    key itself.
8.  **Release intent:** Is this a personal-use APK, a public GitHub
    project, or intended for Play Store distribution? This affects
    signing, privacy and release requirements.

## 10. Current status

-   Project baseline: uploaded `PillPal-main.zip`.
-   New feature implementation: **not started**.
-   Feature priority: **not yet chosen**.
-   Design direction: improve UI while preserving visual identity.
-   Workflow: small changes, review each change, build and test.
-   Build environment: GitHub Actions.
-   Constraints: lightweight app; preserve working features.

**Next action:** The user chooses the first task and provides any
critical Claude handoff context. Then define acceptance criteria before
editing code.

## Performance pass (2026-10-19)
- Reused `Paint` instances and cached immutable chart bounds in `TempChart.onDraw` to reduce per-frame allocations and repeated scans.
- Reused compiled regular expressions for time validation and developer test-intent detection.
- No new dependencies were introduced.
- Remaining performance work: measure screen rebuild costs (`show()` rebuilds the current tab), list-rendering cost, and scroll frame pacing with Android Studio profiling on-device before considering a larger UI refactor.
- Verification status: source-level change only; CI build and high-refresh-rate device testing remain required.
