#!/usr/bin/env python3
"""Test-only loopback HTTP/TLS/UDP servers; no forwarding or public exposure."""
import socketserver,ssl,threading,json,socket
from pathlib import Path
class HTTP(socketserver.BaseRequestHandler):
 def handle(self):
  self.request.settimeout(10);data=b''
  try:
   while b'\r\n\r\n' not in data and len(data)<8192:
    chunk=self.request.recv(2048)
    if not chunk:return
    data+=chunk
   assert b'Host: pdm-antidpi.test' in data
   body=b'x'*65536+b'PDM-ANTI-DPI-OK'
   self.request.sendall(b'HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: '+str(len(body)).encode()+b'\r\n\r\n'+body)
   print(json.dumps({'http':self.server.server_address[1],'ok':True}),flush=True)
  except (OSError,AssertionError):pass
class UDP(socketserver.BaseRequestHandler):
 def handle(self):
  data,sock=self.request
  if len(data)<=512:sock.sendto(data,self.client_address);print(json.dumps({'udp':True}),flush=True)
class Server(socketserver.ThreadingTCPServer):allow_reuse_address=True;daemon_threads=True
class Server6(Server):address_family=socket.AF_INET6
if __name__=='__main__':
 tls=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);tls.load_cert_chain('build/antidpi-probe/fixture.crt','build/antidpi-probe/fixture.key')
 plain=Server(('127.0.0.1',18080),HTTP);secure=Server(('127.0.0.1',18443),HTTP)
 secure.socket=tls.wrap_socket(secure.socket,server_side=True)
 udp=socketserver.ThreadingUDPServer(('127.0.0.1',18081),UDP)
 v6=Server6(('::1',18082),HTTP)
 for server in [plain,secure,udp,v6]:threading.Thread(target=server.serve_forever,daemon=True).start()
 print('ready',flush=True);threading.Event().wait()
