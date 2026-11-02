#!/usr/bin/env python3
"""Reject missing legacy ABIs or non-16-KiB ELF/APK alignment before release."""
import argparse
import struct
import zipfile


def check(path):
    required = {'armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'}
    libraries = {'libadb.so', 'libargon2jni.so', 'libargon2native.so', 'libconscrypt_jni.so'}
    seen = set()
    with zipfile.ZipFile(path) as apk, open(path, 'rb') as raw:
        for info in apk.infolist():
            if not info.filename.startswith('lib/') or not info.filename.endswith('.so'):
                continue
            _, abi, name = info.filename.split('/')
            seen.add((abi, name))
            data = apk.read(info)
            assert data[:4] == b'\x7fELF', info.filename
            endian = '<' if data[5] == 1 else '>'
            wide = data[4] == 2
            offset = struct.unpack_from(endian + ('Q' if wide else 'I'), data, 32 if wide else 28)[0]
            stride, count = struct.unpack_from(endian + 'HH', data, 54 if wide else 42)
            if abi in {'arm64-v8a', 'x86_64'}:
                loads = 0
                headers = [struct.unpack_from(endian + 'IIQQQQQQ', data, offset + stride * i) for i in range(count)]
                for i in range(count):
                    header = headers[i]
                    kind, _, file_offset, virtual, _, _, memory, align = header
                    if kind == 1:
                        loads += 1
                        assert align >= 16384 and file_offset % 16384 == virtual % 16384, info.filename + ': ELF LOAD alignment'
                    if kind == 0x6474e552:
                        # An isolated RELRO LOAD may end inside a page if rounding does
                        # not protect writable data. Older NDKs used this layout for ADB.
                        end = virtual + memory
                        if end % 16384:
                            rounded_end = (end + 16383) // 16384 * 16384
                            for other in headers:
                                if other[0] == 1 and other[1] & 2:
                                    start, stop = other[3], other[3] + other[6]
                                    assert max(end, start) >= min(rounded_end, stop), info.filename + ': RELRO overlaps writable data'
                assert loads, info.filename + ': no LOAD segments'
                if info.compress_type == zipfile.ZIP_STORED:
                    raw.seek(info.header_offset + 26)
                    name_len, extra_len = struct.unpack('<HH', raw.read(4))
                    start = info.header_offset + 30 + name_len + extra_len
                    assert start % 16384 == 0, info.filename + ': APK data alignment'
            print('OK', info.filename)
    assert required <= {abi for abi, _ in seen}, 'Legacy ABI removed'
    assert {(abi, lib) for abi in required for lib in libraries} <= seen, 'Missing native library/ABI'
    print('PASS: four ABIs preserved; all 64-bit native libraries support 16 KiB alignment')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk')
    check(parser.parse_args().apk)
