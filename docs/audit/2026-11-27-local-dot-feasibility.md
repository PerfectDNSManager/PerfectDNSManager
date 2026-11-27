# Local DoT filtering feasibility — 27 November 2026

Requested: personalDNSfilter-style filtering entirely on the Android box, leaving the VPN slot free. A Cloudflare Worker used solely for certificate issuance/renewal is acceptable in the proposed architecture; no DNS query relay through Workers is desired.

## Reproducible diagnostic

`python3 tools/local-dot-probe/build.py` builds an isolated APK, not a PDM component. Install on an owned test device with `adb -s SERIAL install --no-incremental -r build/local-dot-probe/apk/probe.apk`, then run `adb -s SERIAL shell am instrument -w app.perfectdnsmanager.localdotprobe/.Probe`.

The probe checks TCP loopback bind/connect on 853 and 8853 and rejection of an untrusted self-signed certificate by the app's default Android trust manager. This is NOT a complete DoT handshake or system-resolver validation test. Instrumentation completion is not a blanket pass: examine the per-check result text.

For the ADB privilege comparison, push the same diagnostic APK to `/data/local/tmp/pdm-local-dot-probe.apk` and execute `CLASSPATH=/data/local/tmp/pdm-local-dot-probe.apk app_process /system/bin app.perfectdnsmanager.localdotprobe.Probe` through `adb shell`. No root was used and no global DNS, routing or trust settings were changed.

Fresh Android TV API 31 x86 AVD, ordinary INTERNET-permission app, UID 10080:

uid=10080 sdk=31
BIND_CONNECT_FAIL 127.0.0.1:853 java.net.BindException: bind failed: EACCES (Permission denied)
BIND_CONNECT_FAIL ::1:853 java.net.BindException: bind failed: EACCES (Permission denied)
BIND_CONNECT_OK 127.0.0.1:8853
BIND_CONNECT_OK ::1:8853
SELF_SIGNED_REJECTED CertificateException


ADB shell comparison:

shell uid=2000
SHELL_BIND_FAIL 853 java.net.BindException: bind failed: EACCES (Permission denied)
SHELL_BIND_OK 8853

`net.ipv4.ip_unprivileged_port_start` was 1024. This demonstrates an actual blocker on this Android TV image; it is not a claim that every vendor image has identical policy. The first diagnostic revision attempted to load a modern PKCS12 and encountered an Android provider MAC incompatibility; the final probe imports only its public X.509 certificate, avoiding that unrelated test-fixture problem.

## Architecture assessment

A Worker can broker DNS-01 ACME issuance for a per-installation hostname and CSR. The private key must be generated and retained on that device. Ordinary DNS queries would flow Android → local DoT/filter → chosen upstream, with no Worker invocation per query. DNS-01 validation is separate from runtime loopback name resolution, which still needs testing (including DNS rebinding protections and network switching).

A shared private certificate key in all APKs is not an acceptable design. Cloudflare Origin CA certificates do not provide the public trust needed here. A recognized public CA is required for the normal Android private-DNS trust path.

Worker request use could be restricted to enrollment/renewal with bounded retries, persisted state and no periodic per-device polling. However the current Workers Free account quota is shared (100,000 requests/day, 10 ms CPU/invocation); CPU and storage usage must also be measured. A separate Worker is not a separate free account allowance.

Let's Encrypt currently limits new certificates per registered domain to 50 per 7 days by default; new per-installation subdomains do not bypass that limit. ARI renewals are exempt under its documented conditions. Public-scale enrollment requires a CA quota agreement/override or a different issuance plan, not a shared key or quota circumvention.

**No production integration or certificate issuance has been performed.** Even solving certificates leaves port 853 unavailable to both the ordinary application and ADB shell on the tested box image. Android private DNS does not expose a custom-port field. Root/system privileges or a device-specific verified alternative would be necessary on this image; adding dnsdist does not grant those privileges. Retain the existing VPN method rather than exposing a non-working local-DoT option.

Sources checked:
- https://github.com/IngoZenz/personaldnsfilter (reviewed HEAD 94182b2b17b2f0f392e5d973d2076fb3b32abfde)
- https://www.dnsdist.org/install.html
- https://www.dnsdist.org/guides/dns-over-tls.html
- https://android.googlesource.com/platform/packages/modules/DnsResolver/+/refs/tags/android-security-16.0.0_r1/DnsTlsSocket.cpp
- https://android.googlesource.com/platform/packages/modules/DnsResolver/+/8e1561fb603fdaec15798372f865fb1750537f4f/DnsTlsServer.h
- https://developers.cloudflare.com/workers/platform/limits/
- https://developers.cloudflare.com/ssl/origin-configuration/origin-ca/
- https://letsencrypt.org/docs/challenge-types/
- https://letsencrypt.org/docs/rate-limits/
