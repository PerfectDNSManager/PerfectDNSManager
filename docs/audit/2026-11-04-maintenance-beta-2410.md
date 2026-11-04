# Maintenance and optimization — 2.4.10-beta.1

## Changes

- New share passwords use 20 unbiased SecureRandom characters (~98 bits). Existing v2 blobs and six-character passwords remain readable; Argon2/AES parameters and binary format are unchanged. Import labels no longer claim a fixed six-character length.
- Immutable DNS rewrite index replaces a linear lookup for each query. First enabled duplicate remains authoritative; case folding uses Locale.ROOT. A recycled rule no longer toggles the previous row, and the adapter accepts the SwitchCompat actually inflated by its layout.
- Report rendering moved from MainActivity to SecureReportBuilder. UI state is captured before IO; renderer uses a localized application context and honors independently selected private-data sections.
- Donation polling pauses in hidden documents and resumes immediately on visibility, with no overlapping loops or restart after pagehide. Android WebView receives onPause/onResume. One shared site implementation remains.
- ADB JNI rebuilt from pinned Shizuku/BoringSSL sources for all four ABIs using NDK 29, API 21 and explicit 16 KiB alignment. Source, licenses and rebuild script committed; CI rebuilds before packaging. JNI bounds include the full 8192-byte PeerInfo plus authentication tag. Allocation/registration failures are checked. Context destruction is synchronized/idempotent and later operations fail closed.
- R8 and resource shrinking enabled; persisted Gson data names, JNI entry points and KDF interop retained. Release includes compressed crash mapping. A framework-only Android instrumentation runner exercises the actual optimized APK with a local test certificate. It avoids AndroidX/Kotlin test-runtime deduplication and references only deliberately retained JNI/KDF/configuration APIs; production optimization is not weakened for testing.
- AGP 8.13.2, Kotlin 2.2.21, compileSdk 36, Core 1.16.0, AppCompat 1.7.1, RecyclerView 1.4.0, OkHttp 5.4.0, Kwik 0.12.1/Agent15 4.0, coroutines 1.11.0, Lifecycle 2.8.7, desugaring 2.1.5. Dependency catalog centralized. Conscrypt 2.8.0/Argon2kt 1.6.0 retained from beta 2.4.9.
- Profile fields declare input type and disable irrelevant autofill; RSA algorithm literal is available on API 21. Legacy Worker dependencies now pinned with a lockfile.

## Compatibility and explicit limits

minSdk remains 21, all four ABIs retained. AppCompat/Core/Lifecycle newer releases requiring API 23 are deliberately excluded. OkHttp 5.5.1 requires compileSdk 37, beyond this AGP branch; 5.4.0 is chosen with verified minSdk 21/compileSdk 36 metadata. Runtime targetSdk remains 34: opting into newer system behavior is distinct from updating compile tools and requires separate boot/VPN/edge-to-edge migration. No suppression of minSdk checks or overrideLibrary is used.

R8 sees Agent15's desktop JNDI CN fallback. PDM's custom trust manager checks chain, server EKU and matching SAN before Agent15 hostname fallback; no-SAN certificates are rejected. Only those three unavailable JNDI classes, the compile-time Lombok marker and unused desktop DNS SPI receive scoped dontwarn rules. Certificate validation is not disabled. Desktop DNS service files remain excluded from the final APK.

New lint reports include 75 UseKtx suggestions absent from the old tool. Remaining layout/style/translation recommendations and dependency pins are not security findings, and are not globally suppressed. Lint is still enforced in release CI. Public HTTP speed-test endpoints remain explicitly supported; sensitive flows use HTTPS/TLS.

GitHub issues #1 clipboard, #2 Formuler and #3 IPv6 remain open. Clipboard writes occur only on explicit Copy actions; no automatic clipboard reading was found. Emulator tests cannot establish resolution on physical Formuler or Fire TV hardware. Native source reconstruction is not a complete BoringSSL cryptographic audit. No claim of universal absence of bugs or measured DNS latency improvement is made.

## Validation

Local JVM suite: 37 tests passed. Lint: 0 errors, 324 warnings (including 75 newly introduced UseKtx suggestions). OSV scan of 65 resolved Maven coordinates found no known advisories at the audit date; this is not a guarantee against unknown issues.

Android 16 / API 36 on an actual 16 KiB x86_64 emulator: 15 instrumented UI/native/privacy tests passed. A separate IPv6 routing test passed with blocking off/on/off and live Control D setting changes. Optimized release smoke passed Argon2 vectors, native SPAKE/full PeerInfo/authentication rejection, legacy configuration roundtrip, DoH A/AAAA/HTTPS and DoQ A/AAAA/HTTPS. An initial native loader SIGBUS disappeared after a complete non-incremental reinstall of the identical APK; the standalone test instructions now require streamed installation.

Android TV / API 31 x86: native, report privacy, recycled rows, legacy configuration, navigation, IPv6 settings and update progress passed (14 tests); protocol filtering passed separately (1 test). An initial invocation misspelled that test class and was corrected.

Android 5 / API 21 exposed a release-only ART VerifyError in R8's transformed refreshIpDisplay method. A method-only keep rule did not resolve the dex verifier failure. Interface enumeration now uses a shared allocation-light loop, retained as a small separate method to avoid the failing caller register merge. Final API 21 and signed published artifact verification follows below. Site Worker beta deployed from 423f690 (version 3ae15396-bb4a-43ed-9202-9814f2842a79); deployed donations.js matches source. Main/legacy TypeScript, Worker/site/catalog/donations and browser tests pass, including a 61-second hidden polling check and immediate resume. npm audit reports zero known vulnerabilities for this snapshot. No real donation was created.


The corrected optimized APK launches MainActivity, SettingsActivity, DnsSelectionActivity and LanguageSelectionActivity on API 21, and passes Argon2/configuration checks. The pristine 2014-era API 21 emulator trust store rejects current Cloudflare chains with `Trust anchor for certification path not found`. Network results must state the test trust-store fixture; no production certificate/hostname checks are disabled. Legacy compatibility does not imply that an unmaintained OS trust store recognizes all current public CAs.

The API 21 update-progress test initially timed out in Espresso because the indeterminate drawable continuously schedules frames. The test now inspects the actual dialog and invokes its real buttons on the main thread, with queue barriers for posted updates, without relying on animation idleness.

The same Cloudflare trust-anchor failure was reproduced with the previous published 2.4.9-beta.1 APK (local test signature), confirming it predates this change. For API compatibility network tests only, seven existing public GTS/ISRG/SSL.com CA certificates from the host distribution were provisioned into the disposable API 21 AVD system trust store; fingerprints are recorded in 2026-11-04-api21-ca-fixture.json. No CA bundle or relaxed trust manager is shipped in PDM.

Final API 21 optimized release smoke passes: Argon2, legacy import/export, main/settings/DNS/language screens, DoH and DoQ A/AAAA resolution plus HTTPS with the documented CA fixture. Final JVM suite remains 37 passing tests; lint remains 0 errors / 324 warnings. The APK declares minSdk 21 and all four ABIs; ELF and APK alignment checks pass.

Final optimized APK was rechecked after the API 21 fix on the 16 KiB API 36 emulator: all native/configuration/UI checks and both DoH/DoQ DNS+HTTPS scenarios passed.

API 21 instrumented coverage: 12 native/privacy/profile/navigation/IPv6 tests passed; the two final update-progress/cancellation tests passed separately after removing animation-idle assumptions. ADB pairing is correctly skipped below its Android 11 OS requirement.
