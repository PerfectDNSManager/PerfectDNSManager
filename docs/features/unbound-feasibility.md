# Optional local recursive resolver: Unbound

Status: feasibility/design note, 2026-11-27. Not integrated or published.

Suggested user-facing choice: “Local resolution — Unbound”, alongside existing
DNS providers. Start the engine only while this profile is active, release its
resources when switching provider or stopping PDM. The optional local prefilter
remains upstream of either backend:

    Applications → PDM VPN → optional local prefilter → selected backend
                                                     ├─ existing DoH/DoQ/UDP provider
                                                     └─ Unbound recursive resolution

Full recursion queries authoritative DNS servers directly. It does not also
forward to the selected Cloudflare/Control D endpoint. Unbound can alternatively
be configured as a forwarder, but that retains the forwarding provider's policies.
“Neutral” here means no provider-added blocklist, not censorship-proof or fully
confidential DNS. DNSSEC authenticates signed data; it does not encrypt normal
recursive UDP/TCP traffic. ISP/network interception or blocking of DNS port 53
can prevent operation. Preserve explicit errors; do not silently substitute a
third-party resolver for a user who chose local recursion.

## Integration route to prototype

Prefer the libunbound API through JNI, managed by the existing foreground VPN
service. This avoids a second always-running Android service, notification or
local TLS certificate. The library exposes asynchronous resolution and DNS
answer packets. A loopback daemon on an unprivileged port is another possible
route, but not required when using the library API. No root should be required
for the production design; this remains to be proven by a native prototype.

Maintain the existing Android 5/API21 minimum, armeabi-v7a, arm64-v8a, x86,
x86_64 and 16 KiB native alignment. Native compilation, cryptographic dependency
compatibility and maintenance are additional work; compatibility has NOT been
validated merely by reviewing documentation.

Before shipping: test JNI lifetime/cancellation, original DNS IDs and questions,
DNSSEC/trust-anchor updates, incorrect device clocks, network transitions,
blocked UDP/TCP53, IPv4-only and IPv6 networks, memory/CPU on old boxes and cold
versus warm lookup latency. Keep bounded concurrency and caches, no query logs,
and ensure recursive sockets use the underlying network rather than reentering
PDM. Use QNAME minimisation. No listening service exposed on the LAN.

The VPN slot remains occupied to route system/application DNS through PDM.
Android-managed Private DNS/DoT cannot be locally prefiltered by this design.

## Primary sources reviewed

- https://unbound.docs.nlnetlabs.nl/en/latest/ — recursive resolver, cache, DNSSEC.
- https://unbound.docs.nlnetlabs.nl/en/latest/developer/libunbound-tutorial/index.html — embedding and asynchronous library API.
- https://unbound.docs.nlnetlabs.nl/en/latest/manpages/unbound.conf.html — forwarding, transports, cache settings and QNAME minimisation.

## Encrypted forwarding

No special Unbound fork is needed for DoT forwarding. Configure a forward zone
for the DNS root with `forward-tls-upstream: yes`, the chosen provider's
address/port and TLS authentication name, plus a trusted CA bundle. In that mode
Unbound adds its cache/validation while relying on that upstream provider.
Encrypting only an incoming local DoH/DoT listener does not encrypt recursive
traffic to authoritative servers. The existing PDM prefilter followed by the
selected DoH provider already provides local filtering plus encrypted upstream
transport; Unbound is an optional extension, not a prerequisite for that path.
