# Changelog

## Unreleased — UI, selection and safety improvements

- Added long-press and Manage selection modes for medicines and AI suggestions, including Select all and remove selected.
- Added horizontal swiping between the four primary tabs while retaining tap navigation.
- Replaced the custom-drawn AI pencil glyph with a clearly labelled Edit control.
- Added selectable accent palettes alongside System, Light and Dark themes.
- Added a destructive Delete everything action that clears stored preferences/data, cancels medicine alarms and notifications, and removes the stored Gemini Keystore key.
- Expanded heuristic safety warnings for very large mass doses, unusually large liquid volumes (including common spelling variants), very frequent reminder intervals, and potentially hazardous substance/radiation terms.
- Added basic bounds validation for dose units, stock, repeat days, and cycle durations; bounded interval parsing to avoid integer overflow.
- Updated the README and added `PILLPAL_WORKING_PLAN.md` to document project rules and follow-up work.

### Verification note

This change set was reviewed statically in the available environment. A full Android Gradle build and on-device tests were not run here because an Android SDK/Gradle installation was not available. Run the GitHub Actions workflow on a branch before replacing a known-good APK.

## Performance pass (2026-10-19)
- Reused chart Paint objects instead of allocating them on every temperature-chart draw.
- Precomputed temperature-chart bounds when the chart view is created rather than scanning the same series on each frame.
- Avoided allocating a new time-validation Regex for each validation call.
- Cached the developer-test-intent Regex rather than recompiling it for each AI request.
- Kept the existing no-dependency Android architecture and user-facing behavior.
- Performance changes are source-reviewed only; run GitHub Actions and test on-device before treating frame pacing or compilation as verified.
