#!/usr/bin/env python3
import subprocess,re,time,json,os,argparse
from pathlib import Path
parser=argparse.ArgumentParser()
parser.add_argument('serial'); parser.add_argument('--avd-name',required=True)
args=parser.parse_args()
adb=[str(Path(os.environ.get('ANDROID_HOME',str(Path.home()/'Android/Sdk')))/'platform-tools/adb'),'-s',args.serial]
def run(*args):return subprocess.run(adb+list(args),check=True,capture_output=True,text=True).stdout
assert args.avd_name.startswith('pdmrecursive') and args.avd_name == run('emu','avd','name').splitlines()[0].strip()
assert int(run('shell','getprop','ro.build.version.sdk').strip()) >= 23
run('shell','iptables','-t','nat','-L','OUTPUT','-n')
uid=re.search(r'(?:userId|appId)=(\d+)',run('shell','dumpsys','package','app.perfectdnsmanager.design')).group(1)
log=open('build/recursive-fixture.jsonl','w')
server=subprocess.Popen(['python3','tools/recursive-dns-fixture/server.py'],stdout=log,stderr=subprocess.STDOUT)
rules=[]
try:
 time.sleep(1)
 assert server.poll() is None
 for proto in ['udp','tcp']:
  for dest,port in [(None,'15353'),('203.0.113.53','15354')]:
   rule=['OUTPUT','-p',proto,'--dport','53','-m','owner','--uid-owner',uid]
   if dest:rule+=['-d',dest]
   rule+=['-j','DNAT','--to-destination','10.0.2.2:'+port]
   run('shell','iptables','-t','nat','-I',*rule);rules.append(rule)
 result=run('shell','am','instrument','-w','-e','class','app.perfectdnsmanager.UnboundInstrumentedTest#controlledRootReferralReachesSeparateAuthoritativeServer','-e','recursiveFixture','true','app.perfectdnsmanager.design.test/androidx.test.runner.AndroidJUnitRunner')
 Path('build/recursive-fixture-result.log').write_text(result);print(result);print(run('shell','iptables','-t','nat','-L','OUTPUT','-nv'))
 assert 'OK (1 test)' in result
finally:
 try:
  for rule in reversed(rules):run('shell','iptables','-t','nat','-D',*rule)
 finally:
  server.terminate();server.wait(timeout=10);log.close()
rows=[json.loads(x) for x in Path('build/recursive-fixture.jsonl').read_text().splitlines() if x.startswith('{')]
assert any(r.get('role')=='root' and r.get('transport')=='tcp' for r in rows)
for typ in [1,28]:assert any(r.get('role')=='child' and r.get('name')=='www.pdmproof.' and r.get('type')==typ for r in rows)
print('PASS: controlled root TCP referral and child A/AAAA; all DNAT rules removed')
