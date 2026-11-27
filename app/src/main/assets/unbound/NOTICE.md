# PDM libunbound JNI

Build with `python3 tools/build_unbound_native.py`, Android NDK 29.0.14206865.
Source archives and checksums are pinned in that script; no prebuilt third-party
Unbound binary is fetched. Output: `app/src/main/jniLibs/<ABI>/libpdm_unbound.so`.
Four ABIs, API 21, ELF maximum page size 16384; static OpenSSL and C++ runtime.

Dependencies: Unbound 1.26.2 (BSD license in licenses/Unbound-LICENSE),
OpenSSL 3.5.9 LTS (Apache 2.0 in licenses/OpenSSL-LICENSE.txt).
The build disables the unrelated unbound-anchor Expat configure check; only
libunbound is built. It forces pthread mutexes because Android exposes the
pthread_spinlock_t type before API 24 actually provides its functions.

Bundled trust material, updated through app releases rather than runtime fetch:
- `cacert.pem`: https://curl.se/ca/cacert-2026-11-05.pem, Mozilla CA bundle,
  MPL 2.0 (see licenses/MPL-2.0.txt), SHA256
  `427c064216761a1e72a8b30f0d954d08cb6e4435a82091fb865afd20c8497285`.
- `root-anchors.xml`: https://data.iana.org/root-anchors/root-anchors.xml,
  SHA256 `3ccaab38830025ee0a0f6c1f25769427544f81ea2865aa860468f3ef5278b908`.
- `root.key`: active DS anchors 20326 and 38696 extracted from that XML,
  SHA256 `2c212250f1ec271109464e0db2f674f2b6b497da6a177d4d1b264fccb0f6d111`.

TLS uses the bundled CA roots AND the selected provider's hostname. No
trust-all callback, anonymous TLS, UDP fallback or autonomous recursive mode.
DNSSEC validation is mandatory; intentionally invalid signatures fail closed.
The host name of the DoT provider is bootstrapped through system DNS before
establishing the PDM VPN. Browsing queries go through authenticated DoT.

Each active endpoint owns a context, bounded cache (2 MiB message, 4 MiB rrset,
1 MiB key), at most 64 outstanding JNI queries and an 8-second query deadline.
Libunbound is loaded only when an Unbound profile starts. Closing cancels the
worker before freeing callback state; handles are idempotently invalidated.
No listening DNS socket or privileged port is needed. The existing DNS-only
VPN captures virtual DNS addresses; public DoT connections use the underlying
network. This still occupies Android's VPN slot, unlike system Private DNS.
