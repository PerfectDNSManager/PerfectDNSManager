import base64
import unittest
from convert import decode, catalog


def stamp(host=b'dns.example.com', path=b'/dns-query', pin=b'', proto=2):
    def lp(v): return bytes([len(v)]) + v
    return 'sdns://' + base64.urlsafe_b64encode(bytes([proto])+bytes(8)+lp(b'')+lp(pin)+lp(host)+lp(path)).decode().rstrip('=')


class ConversionTest(unittest.TestCase):
    def test_valid(self):
        self.assertEqual(('https://dns.example.com/dns-query', 0), decode(stamp()))
        self.assertEqual(1, len(catalog('\ufeff## example\n'+stamp())[0]))

    def test_constraints_never_silently_discarded(self):
        for value in [stamp(pin=bytes(32)), stamp(proto=1), stamp(proto=129), stamp(host=b''),
                      stamp(host=b'u@dns.example.com'), stamp(host=b'dns.example.com:0'),
                      stamp(path=b'//evil.example'), stamp(path=b'/dns-query#fragment'), stamp()[:-10]]:
            with self.assertRaises((ValueError, UnicodeError)):
                decode(value)

    def test_no_fabricated_relays_or_ambiguous_names(self):
        self.assertFalse(catalog('## relay\nsdns://gRE3NS4xMTkuMTQ0LjA6ODQ0Mw')[0])
        entries, rejected = catalog('## example\n'+stamp()+'\n'+stamp(path=b'/other'))
        self.assertEqual(1, len(rejected))
        self.assertEqual('https://dns.example.com/dns-query', entries['example'][0])

if __name__ == '__main__': unittest.main()
