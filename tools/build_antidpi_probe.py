#!/usr/bin/env python3
from pathlib import Path
import os,subprocess,zipfile
root=Path(__file__).resolve().parents[1];out=root/'build/antidpi-probe';out.mkdir(parents=True,exist_ok=True)
sdk=Path(os.environ.get('ANDROID_HOME',str(Path.home()/'Android/Sdk')));bt=sdk/'build-tools/35.0.0';android=sdk/'platforms/android-36/android.jar'
def run(*args):subprocess.run([str(x) for x in args],check=True)
if not (out/'fixture.key').exists():run('openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',out/'fixture.key','-out',out/'fixture.crt','-days','2','-subj','/CN=pdm-antidpi.test','-addext','subjectAltName=DNS:pdm-antidpi.test')
classes=out/'classes';classes.mkdir(exist_ok=True)
run('javac','--release','8','-cp',android,'-d',classes,root/'tools/antidpi-probe/Probe.java',root/'tools/antidpi-probe/ProbeActivity.java')
run('jar','cf',out/'runner.jar','-C',classes,'.')
run(bt/'d8','--lib',android,'--min-api','21','--output',out,out/'runner.jar')
run(bt/'aapt','package','-f','-M',root/'tools/antidpi-probe/AndroidManifest.xml','-I',android,'-F',out/'unsigned.apk')
with zipfile.ZipFile(out/'unsigned.apk','a') as z:z.write(out/'classes.dex','classes.dex');z.write(out/'fixture.crt','assets/fixture.crt')
run(bt/'zipalign','-f','4',out/'unsigned.apk',out/'probe.apk')
run(bt/'apksigner','sign','--ks',Path.home()/'.android/debug.keystore','--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--key-pass','pass:android',out/'probe.apk')
print(out/'probe.apk')
