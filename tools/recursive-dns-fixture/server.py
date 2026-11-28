#!/usr/bin/env python3
"""Loopback-only unsigned root/child fixture. Pair with owned-AVD DNAT rules."""
import json
import socketserver
import struct
import threading


def name(value):
    return b''.join(bytes([len(part)]) + part.encode('ascii') for part in value.rstrip('.').split('.') if part) + b'\0'


def rr(owner, kind, payload):
    return name(owner) + struct.pack('!HHIH', kind, 1, 60, len(payload)) + payload


def reply(wire, role, tcp):
    if len(wire) < 12 or wire[4:6] != b'\0\1':
        return b''
    offset = 12
    labels = []
    while offset < len(wire) and wire[offset]:
        size = wire[offset]
        if size > 63 or offset + 1 + size > len(wire):
            return b''
        labels.append(wire[offset+1:offset+1+size].decode('ascii'))
        offset += size + 1
    if offset + 5 > len(wire):
        return b''
    end = offset + 5
    qname = '.'.join(labels).lower() + '.'
    qtype = int.from_bytes(wire[offset+1:offset+3], 'big')
    print(json.dumps({'role': role, 'transport': 'tcp' if tcp else 'udp', 'name': qname, 'type': qtype}), flush=True)
    answer, authority, extra = [], [], []
    flags = 0x8000
    if role == 'root' and not tcp:
        flags |= 0x0200  # Force TCP retry, proving recursion supports both transports.
    elif role == 'root' and qname == '.' and qtype == 2:
        flags |= 0x0400
        answer = [rr('.', 2, name('a.root-servers.net.'))]
        extra = [rr('a.root-servers.net.', 1, bytes([198,41,0,4]))]
    elif role == 'root' and qname.endswith('pdmproof.'):
        authority = [rr('pdmproof.', 2, name('ns.pdmproof.'))]
        extra = [rr('ns.pdmproof.', 1, bytes([203,0,113,53]))]
    elif role == 'child':
        flags |= 0x0400
        if qname == 'www.pdmproof.' and qtype == 1:
            answer = [rr(qname, 1, bytes([203,0,113,80]))]
        elif qname == 'www.pdmproof.' and qtype == 28:
            answer = [rr(qname, 28, bytes.fromhex('20010db8000000000000000000000080'))]
        elif qname == 'pdmproof.' and qtype == 2:
            answer = [rr(qname, 2, name('ns.pdmproof.'))]
            extra = [rr('ns.pdmproof.', 1, bytes([203,0,113,53]))]
        elif qname == 'ns.pdmproof.' and qtype == 1:
            answer = [rr(qname, 1, bytes([203,0,113,53]))]
        else:
            flags |= 3
    else:
        flags |= 2
    return wire[:2] + struct.pack('!HHHHH', flags, 1, len(answer), len(authority), len(extra)) + wire[12:end] + b''.join(answer+authority+extra)


class UDP(socketserver.BaseRequestHandler):
    def handle(self):
        wire, sock = self.request
        response = reply(wire, self.server.role, False)
        if response:
            sock.sendto(response, self.client_address)


class TCP(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(10)
        def read(size):
            out = b''
            while len(out) < size:
                part = self.request.recv(size-len(out))
                if not part: raise EOFError()
                out += part
            return out
        try:
            while True:
                size = int.from_bytes(read(2),'big')
                if not 12 <= size <= 4096: return
                response = reply(read(size), self.server.role, True)
                self.request.sendall(len(response).to_bytes(2,'big')+response)
        except (EOFError, TimeoutError, ConnectionError):
            pass


if __name__ == '__main__':
    servers = []
    for role, port in [('root',15353),('child',15354)]:
        for base, handler in [(socketserver.ThreadingUDPServer,UDP),(socketserver.ThreadingTCPServer,TCP)]:
            server = base(('127.0.0.1',port),handler)
            server.daemon_threads = True
            server.role = role
            servers.append(server)
    for server in servers:
        threading.Thread(target=server.serve_forever,daemon=True).start()
    print(json.dumps({'ready':True}),flush=True)
    threading.Event().wait()
