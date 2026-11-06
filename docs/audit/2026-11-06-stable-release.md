# Stable 2.4.11 — 6 November 2026

Owner explicitly requested stable publication after application/site language review.

Source tag `v2.4.11`, commit `6e079ca0f5ccc8b556f26270706d75bd522d43a7`. Compared with the tested 2.4.10-beta.1, application changes are version metadata and cumulative changelog only. Stable BuildConfig selects https://perfectdnsmanager.app for the shared backend. Version code 20411 exceeds both stable 20400 and beta 20410; no downgrade or data wipe is needed.

Existing compatibility and test evidence: [maintenance audit](2026-11-04-maintenance-beta-2410.md), [localization review](2026-11-05-localization-review.md). Android 5/API 21 minimum, all four ABIs and 16 KiB support retained. These checks do not establish compatibility with every physical TV box or every vendor trust store.

Production website and donation backend were deployed before pushing the release tag. All twelve presentation pages were reviewed and corrected, with aligned changelogs/catalogue and local Hindi/Bengali fonts. Production SideShift session/options/pair checks passed (237 available asset/network combinations at the time), fixed USDT0/Polygon receiver verified. Anonymous order lookup and cross-origin session creation were rejected. No funded order or transfer created.

GitHub Actions run: https://github.com/PerfectDNSManager/PerfectDNSManager/actions/runs/42201038063

Published artifact and upgrade verification follows after the build completes.

## Published artifact

GitHub Actions run succeeded: native rebuild, translation parity, JVM tests/lint, optimized release, native/APK alignment and production signature. Release `v2.4.11` is non-prerelease and GitHub's current latest stable; release notes contain the exact 2.4.11 changelog section. Website badge/download listing now report 2.4.11, and GET /apk redirects to the stable alias.

Downloaded numbered APK and both stable aliases are byte-identical: 8,863,586 bytes, SHA-256 `ef6086e0b950e4b168d6911f855923b0ed9440f87e7ec4af035ec1d1917be361`. Production signer SHA-256 `c7fd0c977b2c8911c32268e3fa15e9fe50b14dccfae18f7232b28a9faa858f6d`, v1 and v2 signature schemes verified. Metadata: versionName 2.4.11, versionCode 20411, minSdk 21, targetSdk 34; arm64-v8a, armeabi-v7a, x86 and x86_64 preserved. Independent native alignment and zipalign -P 16 checks passed.

Owned API 36 emulator reports actual 16384-byte pages. Streamed `adb install -r --no-incremental` upgraded the existing production-signed 2.4.10-beta.1 to 2.4.11 successfully, without uninstalling or clearing data. The prior beta initially encountered an ANR during the heavily loaded emulator boot (guest load about 77); it was stopped before the upgrade. Stable MainActivity launch returned status OK in 4.8 seconds after installation.

The post-upgrade visual/data-preservation check is inconclusive: UiAutomator could not obtain its hierarchy, the window service returned DEAD_OBJECT and the screenshot was black. Crash logs include Bluetooth, SystemUI and multiple Google/system processes, including before the stable installation. No application-code change was introduced for promotion, and earlier beta API 21/16 KiB functional checks remain documented separately; this run must not be reported as a fresh successful UI smoke or verified preservation of the selected profile. The installation success and package-manager metadata above were independently observed before the emulator system failure.
