#!/usr/bin/env python3
"""Reproducible Unbound/OpenSSL static dependencies + isolated JNI, API21/16KiB."""
import hashlib, os, pathlib, shutil, subprocess, tarfile, urllib.request
ROOT = pathlib.Path(__file__).resolve().parents[1]
SDK = pathlib.Path(os.environ.get('ANDROID_HOME', str(pathlib.Path.home() / 'Android/Sdk')))
NDK = SDK / 'ndk/29.0.14206865'
TOOL = NDK / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
CACHE = pathlib.Path(os.environ.get('PDM_NATIVE_CACHE', str(pathlib.Path.home()/'.cache/pdm-unbound')))
SOURCES = CACHE / 'source'
SOURCES.mkdir(parents=True, exist_ok=True)
PINS = {
 'unbound-1.26.2': ('https://nlnetlabs.nl/downloads/unbound/unbound-1.26.2.tar.gz', 'd8ee7aa6bbb3ff1be7d9e57637d0d0ec2f18273a8efff1a0afe2c665858b96f7'),
 'openssl-3.5.9': ('https://github.com/openssl/openssl/releases/download/openssl-3.5.9/openssl-3.5.9.tar.gz', '603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a'),
}
for name, (url, checksum) in PINS.items():
 archive = SOURCES / (name + '.tar.gz')
 if not archive.exists():
  local = ROOT/'build/native-source'/archive.name
  if local.exists(): shutil.copyfile(local, archive)
  else: urllib.request.urlretrieve(url, archive)
 if hashlib.sha256(archive.read_bytes()).hexdigest() != checksum: raise RuntimeError('Source checksum mismatch: '+name)
 if not (SOURCES / name).exists():
  with tarfile.open(archive) as tar: tar.extractall(SOURCES, filter='data')
# Expat is only required by the unbound-anchor executable, which is not built.
# Generate a libunbound-only configure script; upstream source archive stays pinned.
configure = SOURCES/'unbound-1.26.2/configure'
content = configure.read_text()
start = content.index('# check for libexpat\n')
end = content.index('# hiredis (redis C client for cachedb)', start)
pdm_configure = configure.with_name('configure-pdm')
pdm_configure.write_text(content[:start] + '# PDM builds libunbound only; no unbound-anchor or Expat.\n' + content[end:])
pdm_configure.chmod(0o755)
ABIS = {
 'armeabi-v7a': ('android-arm', 'armv7a-linux-androideabi', 'arm-linux-androideabi'),
 'arm64-v8a': ('android-arm64', 'aarch64-linux-android', 'aarch64-linux-android'),
 'x86': ('android-x86', 'i686-linux-android', 'i686-linux-android'),
 'x86_64': ('android-x86_64', 'x86_64-linux-android', 'x86_64-linux-android'),
}
for abi in os.environ.get('PDM_UNBOUND_ABIS', ','.join(ABIS)).split(','):
 target, clang, host = ABIS[abi]
 base = CACHE / 'build' / abi
 base.mkdir(parents=True, exist_ok=True)
 log = (base / 'build.log').open('a')
 def run(args, cwd, env):
  subprocess.run([str(a) for a in args], cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
 env=dict(os.environ, PATH=str(TOOL)+os.pathsep+os.environ['PATH'], ANDROID_NDK_ROOT=str(NDK),
          CFLAGS='-O2 -fPIC -fvisibility=hidden', LDFLAGS='-Wl,-z,max-page-size=16384', AR=str(TOOL/'llvm-ar'), RANLIB=str(TOOL/'llvm-ranlib'))
 ssl = base / 'openssl'; prefix = base / 'prefix'
 ssl.mkdir(exist_ok=True)
 if not (prefix/'lib/libssl.a').exists():
  print('Building OpenSSL for', abi, flush=True)
  run(['perl', SOURCES/'openssl-3.5.9/Configure', target, '-D__ANDROID_API__=21',
       'no-shared', 'no-tests', 'no-apps', 'no-module', 'no-dso', 'no-legacy', 'no-docs',
       '--prefix='+str(prefix), '--libdir=lib'], ssl, env)
  run(['make','-j'+os.environ.get('PDM_NATIVE_JOBS','2')], ssl, env)
  run(['make','install_sw'], ssl, env)
 ub = base / 'unbound'; ub.mkdir(exist_ok=True)
 env.update(CC=str(TOOL/(clang+'21-clang')), CXX=str(TOOL/(clang+'21-clang++')),
            ac_cv_type_pthread_spinlock_t='no')  # spin APIs require Android 24; use mutexes on API21
 if not (ub/'.libs/libunbound.a').exists():
  print('Building Unbound for', abi, flush=True)
  run([pdm_configure, '--host='+host, '--with-ssl='+str(prefix),
       '--with-pthreads', '--without-libevent', '--without-libexpat', '--without-libnghttp2',
       '--without-pythonmodule', '--without-pyunbound', '--disable-shared', '--enable-static',
       '--enable-pic', '--disable-flto'], ub, env)
  run(['make','-j'+os.environ.get('PDM_NATIVE_JOBS','2'),'libunbound.la'], ub, env)
 jni=ROOT/'native/unbound/unbound_jni.cpp'
 if jni.exists():
  output=ROOT/'app/src/main/jniLibs'/abi/'libpdm_unbound.so'
  print('Linking JNI for', abi, flush=True)
  run([env['CXX'],'-std=c++17','-O2','-fPIC','-shared','-static-libstdc++',
       '-I'+str(SOURCES/'unbound-1.26.2/libunbound'), jni,
       ub/'.libs/libunbound.a', prefix/'lib/libssl.a', prefix/'lib/libcrypto.a',
       '-Wl,--exclude-libs,ALL','-Wl,-z,max-page-size=16384','-Wl,-z,relro,-z,now',
       '-Wl,--no-undefined','-ldl','-o',output], base, env)
  run([TOOL/'llvm-strip','--strip-unneeded',output], base, env)
 log.close()
