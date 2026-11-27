# Local DNS prefilter

The optional filter is off by default. Advanced DNS settings open its screen.
It runs inside the existing PDM VPN, before the selected provider's DoH, DoQ or
UDP transport. Enabling or editing it does not select, replace or restart a
provider. There is no additional VPN, server, Cloudflare Worker or subscription.

- HaGeZi Light snapshot, optionally disabled while keeping custom rules.
- Manual blocklist and allowlist: domain plus descendants, case-insensitive;
  IDNs normalized to ASCII. The allowlist takes priority over local blocking.
- Up to 1,000 entries / 32,768 characters in each manual list. Invalid input
  is rejected before saving or importing. Settings are included in config backups.
- An immutable snapshot is loaded on the control executor before VPN startup,
  or swapped after a settings change. The bundled sorted index is cached once
  per process. No files, network lookups or domain logs on the request path.
- Blocked queries receive unsigned NXDOMAIN, preserving their question and ID,
  including AAAA, HTTPS and SVCB. Permitted queries and provider failures follow
  the existing transport path unchanged. No IPv6 routing setting is modified.
- Both original and locally rewritten names are checked. Internal DoQ bootstrap
  responses retain their existing priority to avoid recursive resolution.

## Boundaries

Only DNS questions traversing the PDM VPN are covered. Android Private DNS/DoT,
excluded applications, app-specific encrypted DNS, cached answers and direct IP
connections are outside its scope. This is query-name filtering, not inspection
of web traffic or upstream CNAME chains. Provider-side blocking remains active;
adding a local exception cannot override that provider's policy. Existing DNS
caches may retain earlier answers after a rule changes.

The list is an offline snapshot refreshed with app releases, not an automatic
subscription. See `app/src/main/assets/prefilter/README.txt` for exact provenance
and the included GPL license. PDM implements the policy in its own DNS pipeline;
it does not embed PersonalDNSfilter or dnsdist.

## Reproducible checks

Run unit tests, lint and the translation checker. The instrumented
`DnsPrefilterInstrumentedTest` must run on a disposable AVD: it resets PDM test
preferences and turns Android Private DNS off. Its fixture test uses
`tools/PrefilterDnsFixture.java` on the AVD loopback UDP port 53. Compile with
javac and Android d8, push the dex to a disposable root-capable AVD, then run it
with `adb root` and `CLASSPATH=... app_process /system/bin PrefilterDnsFixture`.
Only this isolated test fixture requires root; PDM does not. It binds only
loopback, logs only synthetic `*.prefilter.test` names, and must be stopped
when tests finish. Using a local fixture avoids host/emulator DNS interception.

Verify that the fixture receives permitted names but never
`never.blocked.prefilter.test`. Additional instrumented tests exercise two real
DoH providers (Cloudflare and Control D), A/AAAA answers, the bundled list, saving
without starting a VPN, D-pad focus, live exceptions, disabling and config import.
These live-provider checks require Internet connectivity.
