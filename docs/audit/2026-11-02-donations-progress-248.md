# 2.4.8-beta.1: TV donations and update progress

- Shared beta donation page: no amount/personal refund input; server-owned fallback wallets restricted to configured networks. The original asset goes to PDM if conversion fails, disclosed before creation. Existing orders retain their policy.
- Both coin-first and network-first search, common pairs and local favorites. TV-specific compact layout; restricted WebView keeps origin protections and enables local storage for favorites.
- Activity-bound update dialog reports bytes and percentage, verification and installer handoff. Cancel aborts the actual HTTP call. Errors persist until dismissed. Signature checks remain mandatory; concurrent downloads are rejected.
- Local JVM tests and lint passed before final test-only screenshot synchronization; final lint rerun recorded separately. Two progress UI instrumentation tests and one full downloader test passed on Android TV API 31 (slow stream cancellation, cleanup, rejection of an invalid APK).
- Live donation WebView smoke test passed. Website unit/type/browser tests passed; live beta read-only browser checks passed for 234 available asset/network options, LTC pair, protected cookie, anonymous lookup rejection and cross-origin rejection. No real shift, payment or refund was created.
- Site commit b0f7081; beta Worker cd07ec02-550c-4c3a-8a36-1db005408dec. Stable Worker deployment compared unchanged.
- The new progress UI applies to downloads initiated by this version, not to the previous version downloading this APK. Android owns the final package installation UI.

- Device reports: kernel release from `Os.uname().release` added beside existing Android/API/model/app fields, within the existing optional device section and encrypted payload. No new permission, hostname, fingerprint or unique identifier. Label translated in all 12 locales; unavailable values fail gracefully.

- Final verification including kernel-report change: assembleDebug, testDebugUnitTest and lintDebug passed (2026-11-02). Final Android TV screenshot inspected after compositor synchronization: complete form fits the viewport.

## Publication verification

GitHub Android Release 41495764618 succeeded for commit 942d770caf799649982170b82bfc895e537d3d10. Both numbered and latest-beta APKs have SHA-256 ac30e8f0056c01949f63eea9098293c32aa4e8412d1371a7be1a6a5c805300e9. Production signing certificate matches c7fd0c977b2c8911c32268e3fa15e9fe50b14dccfae18f7232b28a9faa858f6d. Signed 2.4.7-beta.1 to 2.4.8-beta.1 upgrade succeeded on fresh Android TV API 31 AVD. Stable release remains v2.4.0. Release and rolling-beta notes include the kernel report change.
