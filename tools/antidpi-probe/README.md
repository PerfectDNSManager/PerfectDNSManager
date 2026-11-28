# Disposable anti-DPI transport probe

Only use disposable AVDs. The probe needs a different UID from PDM: native
upstream sockets intentionally bypass the VPN under PDM's own UID.

1. Build `python3 tools/build_antidpi_probe.py` and install
   `build/antidpi-probe/probe.apk` on the AVD.
2. Start `python3 tools/antidpi-probe/fixture.py` on the emulator host. It binds
   loopback only: HTTP 18080, TLS 18443, UDP echo 18081, IPv6 HTTP 18082.
3. Build/install the PDM debug APK and AndroidTest APK. Grant VPN permission
   normally, then run `AntiDpiInstrumentedTest` with `antiDpiFixture=true`.
   Add `ipv6Fixture=true` only on an emulator supporting host alias `fec0::2`.
4. For optimized APK tests, use the existing framework SmokeRunner with
   `antiDpiChecks=true`, the actual `expectedPageSize`, and optionally
   `ipv6Fixture=true`. The APK and runner must share the disposable debug key.
   Never publish that test-signed APK.

The fixture's generated test certificate is pinned only in the probe APK.
Its private key stays under ignored build/; no changes to PDM trust or TLS.
The probe opens an Activity so Android's background-UID network restrictions
do not invalidate the transport test. It checks DNS A/AAAA, a 64 KiB HTTP body,
a TLS handshake with SNI, UDP echo, and optional explicit IPv6 allow/block.
The controller also tests prefilter NXDOMAIN, toggles, Unbound with DoT, and
native worker death. Tests restore their configuration on exit.

The bounded SOCKS malformed-input harness is `fuzz_socks.py`; run against an
ASan/UBSan host build of the pinned ByeDPI source, never a public proxy.
These fixtures prove mechanics and connectivity, not bypass of a real ISP DPI.
