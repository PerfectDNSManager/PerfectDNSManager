"""Live A/AAAA probe of exported PDM catalogue. DoQ is tested inside Android.
Usage: python3 tools/dns_ipv6_catalog_probe.py catalogue.json report.json
No credentials, private profiles, or user browsing data are used.
"""
import base64, concurrent.futures, ipaddress, json, secrets, socket, ssl, struct, sys, subprocess

def skip_name(data, pos):
    for _ in range(128):
        size = data[pos]
        if size == 0: return pos + 1
        if size & 192 == 192: return pos + 2
        if size > 63: raise ValueError('invalid DNS label')
        pos += 1 + size
    raise ValueError('DNS name too long')

def exact(sock, count):
    out = b''
    while len(out) < count:
        part = sock.recv(count - len(out))
        if not part: raise EOFError('incomplete DNS response')
        out += part
    return out

def probe(task):
    profile, field, endpoint = task
    row = {k: profile[k] for k in ('providerName', 'name', 'type')}
    row.update(field=field, endpoint=endpoint)
    for kind in (1, 28):
        key = 'A' if kind == 1 else 'AAAA'
        q = struct.pack('!6H', secrets.randbelow(65536), 256, 1, 0, 0, 0) + b'\x07example\x03com\x00' + struct.pack('!HH', kind, 1)
        try:
            if profile['type'] == 'DOH':
                res = subprocess.run(['curl', '--silent', '--show-error', '--fail', '--http2', '--max-time', '10', '-H', 'Accept: application/dns-message', '-H', 'Content-Type: application/dns-message', '--data-binary', '@-', endpoint], input=q, capture_output=True, timeout=12)
                if res.returncode: raise OSError(res.stderr.decode().strip())
                data = res.stdout
            elif profile['type'] == 'DOT':
                host = endpoint.removeprefix('tls://')
                with socket.create_connection((host, 853), timeout=8) as raw:
                    with ssl.create_default_context().wrap_socket(raw, server_hostname=host) as sock:
                        sock.sendall(struct.pack('!H',len(q))+q)
                        data = exact(sock, struct.unpack('!H', exact(sock,2))[0])
            else:
                family = socket.AF_INET6 if ':' in endpoint else socket.AF_INET
                with socket.socket(family,socket.SOCK_DGRAM) as sock:
                    sock.settimeout(5); sock.connect((endpoint,53)); sock.send(q); data = sock.recv(65536)
            ident,flags,qd,an,ns,ar = struct.unpack('!6H',data[:12])
            if data[:2]!=q[:2] or not flags&32768 or qd!=1 or data[12:len(q)]!=q[12:]: raise ValueError('DNS mismatch')
            pos=len(q); addresses=[]
            for _ in range(an):
                pos=skip_name(data,pos); typ,cls,ttl,size=struct.unpack('!HHIH',data[pos:pos+10]);pos+=10
                if typ==kind and cls==1: addresses.append(str(ipaddress.ip_address(data[pos:pos+size])))
                pos+=size
            row[key]={'rcode':flags&15,'truncated':bool(flags&512),'addresses':addresses}
        except Exception as exc: row[key]={'error':type(exc).__name__+': '+str(exc)}
    return row

if __name__=='__main__':
    profiles=json.load(open(sys.argv[1]))['profiles']; tasks=[]
    for p in profiles:
        if p['type']=='DOQ' or (len(sys.argv)>3 and p['type']!=sys.argv[3]): continue
        for field in ('primary','secondary','primaryV6','secondaryV6'):
            if p.get(field): tasks.append((p,field,p[field]))
    results=[]
    with concurrent.futures.ThreadPoolExecutor(max_workers=10) as pool:
        for r in pool.map(probe,tasks):
            results.append(r)
            print(r['providerName'],r['name'],r['field'],r['type'],r['AAAA'],flush=True)
    json.dump({'domain':'example.com','results':results,'doq':'See Android instrumentation matrix'},open(sys.argv[2],'w'),indent=2)
