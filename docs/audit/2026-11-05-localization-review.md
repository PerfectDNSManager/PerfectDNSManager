# Application localization review — 5 November 2026

Scope: all 481 existing strings and both plural resources in French (base), English, German, Spanish, Italian, Brazilian Portuguese, Russian, simplified Chinese, Japanese, Arabic, Hindi and Bengali. This was a source-level linguistic and functional review; it is not certification by twelve native-speaking translators. Proper names, protocols, example addresses and external/system diagnostics remain technical text.

## Corrections

- Corrected grammar, vocabulary, inconsistent forms of address, Android string quoting and spaces around the domain-test conjunction.
- Removed the obsolete six-character sharing-password label in Bengali and the inaccurate AES-256 claim. The interoperable sharing format still uses AES-128-GCM; this change does not alter encryption or break old links.
- Documented DNS rewriting for DoH, DoQ and classic DNS through the local VPN, excluding system DoT/ADB.
- Corrected Fire TV developer-mode instructions (device name, not serial number) and Android wireless-ADB pairing guidance. No guarantee of permanent permission or unrestricted local ADB on every vendor device.
- DNS resolver differences no longer claim to prove absence of leaks; automatic DoT deactivation asks the user to check connectivity instead of asserting recovery.
- Localized report headings/metrics, speed-test labels and progress, configuration import previews and application-owned DNS diagnostic messages. Raw OS/network errors remain useful technical diagnostics.
- Added 18 shared string resources in every locale: 501 total resources per language, including plurals.

References for device instructions: [Amazon Fire TV ADB](https://developer.amazon.com/docs/fire-tv/connecting-adb-to-device.html), [Android device debugging](https://developer.android.com/studio/run/device).

## Regression protection

`tools/check_translations.py` checks every locale for duplicate/missing/extra keys, plural fallback, empty translations and matching Java Formatter argument indices/types. Arabic singular/dual forms may spell out numeric counts. The release workflow runs this check and extracts the exact version's CHANGELOG section for both numbered and rolling beta release notes.

`LocalizationInstrumentedTest` exercises compiled Android string formatting for all twelve locales, plural counts 0/1/2/3/11/100, preserved conjunction whitespace/search quotes, current password labels, and locale-specific report/import previews. This complements source comparison: AAPT can silently strip unescaped quotes or trim whitespace.

Validation and release results are recorded below after execution.

## Local validation

- Translation checker: 501 resources × 12 languages, passed.
- JVM: 37 tests, zero failures/errors. Debug/release/test APK assembly passed.
- Android lint: zero errors, 330 warnings (including existing styling, legacy API, diagnostics and dependency advisories; not a warning-free claim).
- Android 5/API 21: both localization instrumentation tests passed, exercising all 12 configurations. Visual checks of English/Bengali main screens and the language picker confirmed readable Hindi/Bengali characters. Old Android renders some flag emoji as regional letters; names remain readable.
- API 21 optimized release, locally re-signed with the public debug certificate solely for the owned emulator: framework smoke passed (4 KiB page size, Argon2 production vector, legacy Gson import/export, main/settings/provider/language screens). No network trust changes were needed for these offline checks.
- Native verification: all four ABIs present, 64-bit libraries and APK meet 16 KiB alignment requirements.
- Workflow YAML parsed and changelog extraction executed successfully against version 2.4.10-beta.1.
- API 36 emulator with actual 16 KiB pages: optimized-release framework smoke passed, including Argon2, native ADB full PeerInfo/input bounds/repeated close, Gson compatibility and all four screens.
- API 36/16 KiB: both localization tests plus report privacy and Gson tests passed. The final adapter/UI test was interrupted by the emulator's low-memory killer during boot (`ApplicationExitInfo: LOW_MEMORY`, confirmed in logcat); an isolated retry passed. No Java/native application crash was reported.
