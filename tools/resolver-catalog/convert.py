#!/usr/bin/env python3
"""Explicit, offline-reviewable DoH catalog conversion; never changes PDM itself."""
import argparse
import base64
import hashlib
import json
import re
import urllib.parse
import urllib.request
from pathlib import Path

SOURCES = {
    'yoga': 'https://yogadns.com/resolvers/resolvers.md',
    'dnscrypt': 'https://raw.githubusercontent.com/DNSCrypt/dnscrypt-resolvers/master/v3/public-resolvers.md',
    'relays': 'https://raw.githubusercontent.com/DNSCrypt/dnscrypt-resolvers/master/v3/relays.md',
}
MAX_BYTES = 1024 * 1024


def decode(stamp):
    encoded = stamp.removeprefix('sdns://')
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,4096}={0,2}', encoded):
        raise ValueError('Malformed stamp')
    data = base64.b64decode(encoded + '=' * (-len(encoded) % 4), altchars=b'-_', validate=True)
    if not data or data[0] != 2:
        raise ValueError('Not a DoH resolver (DNSCrypt, DoT, DoQ and relays are not converted)')
    if len(data) < 10:
        raise ValueError('Truncated stamp')
    props = int.from_bytes(data[1:9], 'little')
    pos = 9

    def lp():
        nonlocal pos
        if pos >= len(data):
            raise ValueError('Missing field')
        size = data[pos]
        pos += 1
        if pos + size > len(data):
            raise ValueError('Truncated field')
        result = data[pos:pos + size]
        pos += size
        return result

    lp()  # Address hint: PDM resolves the authenticated hostname on its own network.
    # Never discard certificate pin constraints that PDM cannot represent.
    if lp():
        raise ValueError('Certificate pins cannot be preserved by PDM profiles')
    host = lp().decode('ascii')
    path = lp().decode('ascii')
    if pos < len(data):
        # Optional bootstrap resolver list can be ignored, but must be well formed.
        while True:
            size = data[pos]
            more = bool(size & 128)
            pos += 1
            size &= 127
            if pos + size > len(data):
                raise ValueError('Truncated bootstrap field')
            pos += size
            if not more:
                break
            if pos >= len(data):
                raise ValueError('Truncated bootstrap list')
    if pos != len(data):
        raise ValueError('Trailing data')
    if not path.startswith('/') or path.startswith('//'):
        raise ValueError('Invalid DoH path')
    url = 'https://' + host + path
    parsed = urllib.parse.urlsplit(url)
    if (not parsed.hostname or parsed.username is not None or parsed.password is not None
            or parsed.fragment or len(url) > 2048 or any(ord(c) <= 32 for c in url)
            or '\\' in url or not re.fullmatch(r'[A-Za-z0-9.-]+(?::[0-9]{1,5})?', host)):
        raise ValueError('Invalid DoH URL')
    if parsed.port is not None and not 1 <= parsed.port <= 65535:
        raise ValueError('Invalid port')
    labels = parsed.hostname.rstrip('.').split('.')
    if (len(labels) < 2 or len(parsed.hostname) > 253
            or any(not re.fullmatch(r'[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?', v) for v in labels)
            or not any(c.isalpha() for c in labels[-1])):
        raise ValueError('Invalid authenticated hostname')
    return url, props


def catalog(text):
    if len(text.encode('utf-8')) > MAX_BYTES:
        raise ValueError('Catalog too large')
    accepted, rejected = {}, []
    name = ''
    for line in text.lstrip('\ufeff').splitlines():
        line = line.strip()
        if line.startswith('## '):
            name = line[3:].strip()
        elif line.startswith('sdns://'):
            try:
                if not name or len(name) > 128 or any(ord(c) < 32 for c in name):
                    raise ValueError('Invalid resolver name')
                url, props = decode(line)
                if name in accepted and accepted[name][0] != url:
                    raise ValueError('Ambiguous duplicate name')
                accepted[name] = (url, props)
            except (ValueError, UnicodeError) as error:
                rejected.append({'name': name, 'reason': str(error)})
    return accepted, rejected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', choices=SOURCES, required=True)
    parser.add_argument('--input', type=Path, help='Use a previously downloaded catalog instead of HTTPS')
    parser.add_argument('--name', action='append', default=[], help='Exact resolver name, repeatable; omit to list')
    parser.add_argument('--base', type=Path, help='PDM export: preserve its profiles when generating an import')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if args.input:
        with args.input.open('rb') as source:
            raw = source.read(MAX_BYTES + 1)
    else:
        with urllib.request.urlopen(SOURCES[args.source], timeout=20) as response:
            if not response.url.startswith('https://'):
                raise ValueError('HTTPS required')
            raw = response.read(MAX_BYTES + 1)
    if len(raw) > MAX_BYTES:
        raise ValueError('Catalog too large')
    entries, rejected = catalog(raw.decode('utf-8-sig'))
    if not args.name:
        print(json.dumps({'source': SOURCES[args.source], 'sha256': hashlib.sha256(raw).hexdigest(),
                          'compatible': {k: v[0] for k, v in entries.items()}, 'excluded': rejected}, indent=2))
        return
    if args.base is None or args.output is None:
        parser.error('--name requires --base (PDM export) and --output')
    with args.base.open('rb') as base:
        raw_base = base.read(MAX_BYTES + 1)
    if len(raw_base) > MAX_BYTES:
        raise ValueError('Base too large')
    profiles = json.loads(raw_base)['profiles']
    if not isinstance(profiles, list) or any(not isinstance(p, dict) for p in profiles):
        raise ValueError('Invalid base profiles')
    known = {(p.get('type'), p.get('primary')) for p in profiles}
    used_ids = {p.get('id') for p in profiles}
    for name in args.name:
        url, props = entries[name]  # Unknown/incompatible selections fail before writing.
        if ('DOH', url) in known:
            continue
        pid = int.from_bytes(hashlib.sha256(url.encode()).digest()[:6], 'big') + 1_000_000
        while pid in used_ids:
            pid += 1
        used_ids.add(pid)
        profiles.append({'id': pid, 'providerName': name, 'name': 'DoH', 'type': 'DOH', 'primary': url,
                         'isCustom': True, 'isAdblock': not bool(props & 4), 'descResId': 0,
                         'description': 'Imported from ' + SOURCES[args.source] + '; filtering policy must be checked with its operator.'})
        known.add(('DOH', url))
    output = json.dumps({'profiles': profiles}, indent=2, ensure_ascii=False)
    if len(profiles) > 512 or len(output.encode()) > MAX_BYTES:
        raise ValueError('PDM import limits exceeded')
    # No selected DNS/settings in this output; existing profile list is preserved.
    with args.output.open('x', encoding='utf-8') as destination:
        destination.write(output + '\n')
    print(f'{len(profiles)} profiles written to {args.output}; review before importing into PDM.')


if __name__ == '__main__':
    main()
