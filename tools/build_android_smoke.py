#!/usr/bin/env python3
"""Build a framework-only runner, signed with the public Android debug key."""
from pathlib import Path
import os,subprocess,zipfile
root=Path(__file__).resolve().parents[1]
sdk=Path(os.environ.get('ANDROID_HOME',str(Path.home()/'Android/Sdk')))
bt=sdk/'build-tools/35.0.0'; android=sdk/'platforms/android-36/android.jar'
out=root/'build/android-smoke';out.mkdir(parents=True,exist_ok=True)
classes=out/'classes';classes.mkdir(exist_ok=True)
def run(*args):subprocess.run([str(x) for x in args],check=True)
run('javac','--release','8','-cp',android,'-d',classes,root/'tools/android-smoke/SmokeRunner.java')
run('jar','cf',out/'runner.jar','-C',classes,'.')
run(bt/'d8','--lib',android,'--min-api','21','--output',out,out/'runner.jar')
run(bt/'aapt','package','-f','-M',root/'tools/android-smoke/AndroidManifest.xml','-I',android,'-F',out/'unsigned.apk')
with zipfile.ZipFile(out/'unsigned.apk','a') as z:z.write(out/'classes.dex','classes.dex')
run(bt/'zipalign','-f','4',out/'unsigned.apk',out/'smoke.apk')
run(bt/'apksigner','sign','--ks',Path.home()/'.android/debug.keystore','--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--key-pass','pass:android',out/'smoke.apk')
print(out/'smoke.apk')
