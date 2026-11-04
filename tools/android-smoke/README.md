# Optimized APK integration tests

Run only on a disposable AVD owned by the test session. This runner imports a synthetic profile, starts the app VPN, makes DNS/HTTPS requests, and stops the VPN. It creates no encrypted upload or donation.

`python3 tools/build_android_smoke.py` builds `build/android-smoke/smoke.apk` using SDK build-tools 35 / platform 36 and the public Android debug certificate. It has no AndroidX, Kotlin, desugaring or other bundled test runtime, so those dependencies cannot shadow classes in the optimized app.

Sign a **copy** of the local unsigned release APK with the same debug certificate, then install that copy and the runner on the owned AVD using `adb install --no-incremental -r APK`. A complete streamed install avoids incomplete native-library pages observed with incremental installation on the experimental x86_64 16 KiB emulator. Do not replace a production user's APK with a differently signed build. Grant ACTIVATE_VPN via `adb shell appops set app.perfectdnsmanager ACTIVATE_VPN allow`; on API 33+ also grant POST_NOTIFICATIONS. The instrumentation target is `app.perfectdnsmanager`.

Run `adb -s OWNED_SERIAL shell am instrument -w -e expectedPageSize 16384 app.perfectdnsmanager.smoke/.SmokeRunner` (use 4096 for a 4 KiB AVD). Require `ALL PASSED` in the result; adb's own exit status does not represent test success.

Default checks: actual page size; production Argon2 known vector; API 30+ native SPAKE full 8192-byte PeerInfo, tampered authentication tag, oversized input and repeated destruction; actual ConfigManager legacy import/export; main/settings/DNS/language screen launch; VPN DoH A/AAAA resolution and HTTPS. `-e mode offline` omits network. `-e mode network -e endpoint quic://p0.freedns.controld.com` checks DoQ instead. Framework UI tests in androidTest cover navigation and report privacy separately.

On API 21, use `-e allowConsentDialog true` and accept the normal Android VPN consent dialog on the owned AVD; the newer ACTIVATE_VPN appops command is unavailable there. The runner waits up to 60 seconds for consent.

`-e mode tls` diagnoses the platform TLS trust chain without starting a VPN. The pristine API 21 emulator has an obsolete root store; see the audit CA-fixture record for the network-test setup. No trust override is implemented by the runner.
