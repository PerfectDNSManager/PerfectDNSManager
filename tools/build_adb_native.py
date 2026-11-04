#!/usr/bin/env python3
"""Build every shipped ADB ABI from pinned source with Android API 21 / 16 KiB support."""
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SDK = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Android/Sdk')))
NDK = SDK / 'ndk/29.0.14206865'
CMAKE = SDK / 'cmake/3.22.1/bin/cmake'
NINJA = SDK / 'cmake/3.22.1/bin/ninja'
REV = '79048f1f1d8e6b7f9ca59b95c24486c8149122a4'
SOURCE = ROOT / 'build/native-source/boringssl'
def run(*args):
    subprocess.run([str(a) for a in args], check=True)
if not (SOURCE / '.git').exists():
    SOURCE.mkdir(parents=True, exist_ok=True)
    run('git', 'init', SOURCE)
if subprocess.run(['git', '-C', str(SOURCE), 'cat-file', '-e', REV], capture_output=True).returncode:
    run('git', '-C', SOURCE, 'fetch', '--depth', '1', 'https://github.com/google/boringssl.git', REV)
run('git', '-C', SOURCE, 'checkout', '--detach', REV)
for abi in ('armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'):
    build = ROOT / 'build/native-adb' / abi
    run(CMAKE, '-S', ROOT / 'native/adb', '-B', build, '-G', 'Ninja',
        f'-DCMAKE_MAKE_PROGRAM={NINJA}', f'-DCMAKE_TOOLCHAIN_FILE={NDK}/build/cmake/android.toolchain.cmake',
        f'-DANDROID_ABI={abi}', '-DANDROID_PLATFORM=android-21', '-DANDROID_STL=c++_static',
        '-DCMAKE_BUILD_TYPE=Release', f'-DBORINGSSL_SOURCE={SOURCE}')
    run(CMAKE, '--build', build, '--target', 'adb', '--parallel', os.environ.get('PDM_NATIVE_JOBS', '2'))
    output = ROOT / 'app/src/main/jniLibs' / abi / 'libadb.so'
    shutil.copyfile(build / 'libadb.so', output)
    run(NDK / 'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip', '--strip-unneeded', output)
