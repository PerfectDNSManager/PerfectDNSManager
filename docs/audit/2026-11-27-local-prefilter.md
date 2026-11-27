# Local prefilter implementation and verification — 2026-11-27

Local branch: codex/pdm-local-prefilter. No release, beta, website or Worker
publication. Android minSdk 21, native libraries and DNS provider presets unchanged.

## Result

Optional local prefilter in advanced DNS settings, disabled by default. HaGeZi
Light snapshot (81,810 domains), bounded personal blocks and allowlist, persisted
and exported/imported with settings. Matching covers descendants, A/AAAA and
other record types, plus both original and rewritten question names. Allowed
queries retain the selected provider. Live immutable policy reload; no domain logs.
The Android-managed DoT path and excluded/app-specific DNS remain outside scope.
See ../features/local-dns-prefilter.md and asset provenance for details.

## Final validation

- Gradle offline: testDebugUnitTest, assembleDebug, assembleDebugAndroidTest,
  lintDebug all pass. 44 unit tests, zero failures/skips; lint zero errors,
  324 warnings (not a claim that the existing application has no lint warnings).
- Translation checker: 510 resources × 12 languages. git diff --check clean.
- Android TV API31 disposable AVD: 2 final instrumented tests pass (11.163s).
  Local blocked names plus bundled rule, Cloudflare and Control D DoH with
  successful A and AAAA answers; screen save, D-pad focus and selected profile
  preservation. No restart/provider replacement needed for the filter.
- Android 5.0.2/API21 disposable AVD: 2 final instrumented tests pass (5.050s).
  Loopback DNS fixture sees permitted A/AAAA/HTTPS queries, local NXDOMAIN for
  blocked names, live allowlist, disabling, export/import and selected personal
  profile preserved. Save screen does not start a VPN; initial focus corrected.
- Fixture transcript contains zero occurrences of `never.blocked.prefilter.test`;
  permitted queries reach the selected fixture. Queries already issued while a
  changed policy is loading still use the previous snapshot (no blank policy).
- TV screenshot inspected. API21 screenshot captured during its activity
  transition; behavioral focus/save checks pass, full settled visual review on
  a physical old box remains desirable.

## Findings addressed during tests

The first bundled load took about 37 seconds on the initial TV emulator run due
to repeated IDN normalization. The trusted ASCII snapshot is now validated in a
unit test, sorted/loaded without per-entry IDN conversion, and cached. Subsequent
cold-process TV observations were about 2.3s and 0.47s between load log markers;
these are emulator observations, not a hardware performance guarantee.

API21 lacks the ACTIVATE_VPN app-op used by modern tests. The test now exercises
its actual VPN consent dialog on a disposable emulator. The real app still uses
Android VPN consent and requires no root. Only the loopback UDP/53 fixture runs
as root inside this disposable AVD.

## Explicit limits / outstanding environment issue

Live DoH did not pass on the stock Android 5 image: SSLHandshakeException with
`CertPathValidatorException: Trust anchor for certification path not found`.
A standalone system TLS probe, outside PDM and with the VPN stopped, reproduces
this for cloudflare-dns.com, freedns.controld.com and api.github.com. No certificate
verification was bypassed and no trust store was changed. This is independent
of the prefilter; old-device trust-store compatibility needs separate investigation
before promising DoH on all Android 5 installations. The local filter/forwarding
path itself passed on API21. No live DoQ or physical Thomson/Fire TV test this turn.

Unbound was researched and documented separately, not added as a dependency or
runtime engine. See ../features/unbound-feasibility.md.
