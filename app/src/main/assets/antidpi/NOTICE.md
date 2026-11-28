# Optional local anti-DPI engine

PDM embeds ByeDPI and hev-socks5-tunnel, the engines also used by ByeByeDPI.
No second VPN, remote proxy, root access or runtime executable download.
Sources and exact commits are pinned in tools/build_antidpi_native.py.

- hufrea/byedpi: ba532298de7b28cfe854aea83d061369d13ca290 (MIT)
- heiher/hev-socks5-tunnel: 4d6c334dbfb68a79d1970c2744e62d09f71df12f (MIT)
- heiher/hev-socks5-core: 4be2e621813ba0315cfacd995bf501bde91d6996 (MIT)
- heiher/hev-task-system: 8d83bbbf79557138726c8ee5a5fae99cbb978d61 (MIT)
- heiher/lwip: 07dbf162c718cc78ddedb9e67c6ebd17065eaf13 (BSD)
- heiher/yaml: efa36117a8646d26d12b58e05bac472d7854a70d (MIT)

PDM changes: kernel-assigned loopback port, readiness callback, JNI wrappers,
private one-session Android worker, bounded packet bridge, IPv6 setting,
64-session limit and ARMv7 baseline without mandatory NEON. No arbitrary CLI,
daemon, file-based strategies or certificate-validation bypass is exposed.
Licenses are bundled alongside this notice.
