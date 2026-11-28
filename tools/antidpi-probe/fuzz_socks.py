#!/usr/bin/env python3
"""Small deterministic malformed-input smoke test; loopback only, not a full audit."""
import argparse,socket,subprocess,time,tempfile,random,os
p=argparse.ArgumentParser();p.add_argument('binary');a=p.parse_args()
with socket.socket() as reserve:reserve.bind(('127.0.0.1',0));port=reserve.getsockname()[1]
with tempfile.TemporaryFile(mode='w+') as log:
 proc=subprocess.Popen([a.binary,'-i','127.0.0.1','-p',str(port),'-N','-c','64','-s','1+s'],stdout=log,stderr=log,env=dict(os.environ,ASAN_OPTIONS='detect_leaks=1:abort_on_error=1',UBSAN_OPTIONS='halt_on_error=1'))
 count=0
 try:
  time.sleep(.3);assert proc.poll() is None
  rng=random.Random(2415)
  payloads=[bytes([5,1,0,3,n])+b'a'*max(0,n-1) for n in [0,1,2,63,127,254,255]]
  payloads += [bytes([5,255,0,255])+rng.randbytes(n) for n in range(192)]
  for payload in payloads:
   assert proc.poll() is None
   with socket.create_connection(('127.0.0.1',port),timeout=1) as sock:
    sock.settimeout(.1);sock.sendall(b'\x05\x01\x00');assert sock.recv(2)==b'\x05\x00'
    try:sock.sendall(payload);sock.shutdown(socket.SHUT_WR);sock.recv(64)
    except (TimeoutError,ConnectionError):pass
   count+=1
  assert proc.poll() is None
 finally:
  if proc.poll() is None:proc.terminate()
  proc.wait(timeout=5)
 log.seek(0);text=log.read()
 assert 'AddressSanitizer' not in text and 'runtime error:' not in text,text
 assert proc.returncode==0,(proc.returncode,text)
 print(f'PASS: {count} malformed SOCKS messages, no ASan/UBSan finding')
