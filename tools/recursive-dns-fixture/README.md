# Controlled recursive DNS test

Run from the repository root on a disposable rooted Android emulator with the
app debug APK and AndroidTest APK installed. The emulator must have the NAT
iptables table (the API 21 image used here does not; API 36 does).

```sh
python3 tools/recursive-dns-fixture/run.py emulator-5562 --avd-name pdmrecursive2414
```

The explicit AVD name must start with `pdmrecursive`. The tool redirects only
this test app UID’s UDP/TCP port 53 inside that emulator to two loopback-only
servers on the host. It removes its exact rules and terminates its server in
`finally`; no host firewall is modified. Do not use on a personal device.

The root forces TCP retry and delegates `pdmproof.` to a separate authoritative
server. Assertions check A/AAAA answers and root/child traffic. This isolated
hierarchy is unsigned: the instrumented test uses an empty trust-anchor file.
Production always loads the bundled IANA anchors; separate native and VPN tests
check signed domains, bogus DNSSEC, and authenticated DoT.

This fixture avoids mistaking transparent port-53 interception by a test network
for successful direct public recursion. It is not an Internet resolver and never
forwards queries outside the host.
