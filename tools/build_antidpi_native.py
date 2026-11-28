#!/usr/bin/env python3
"""Build the optional anti-DPI engine from pinned, verified Git commits (NDK 29)."""
from pathlib import Path
import os, subprocess, shutil
ROOT=Path(__file__).resolve().parents[1]
OUT=Path(os.environ.get('PDM_ANTIDPI_BUILD_DIR','/tmp/pdm-antidpi-native')); OUT.mkdir(parents=True,exist_ok=True)
SDK=Path(os.environ.get('ANDROID_HOME',str(Path.home()/'Android/Sdk')))
NDK=SDK/'ndk/29.0.14206865'
PINS={
 'byedpi':('hufrea/byedpi','ba532298de7b28cfe854aea83d061369d13ca290'),
 'hev':('heiher/hev-socks5-tunnel','4d6c334dbfb68a79d1970c2744e62d09f71df12f'),
 'hev/src/core':('heiher/hev-socks5-core','4be2e621813ba0315cfacd995bf501bde91d6996'),
 'hev/third-part/hev-task-system':('heiher/hev-task-system','8d83bbbf79557138726c8ee5a5fae99cbb978d61'),
 'hev/third-part/lwip':('heiher/lwip','07dbf162c718cc78ddedb9e67c6ebd17065eaf13'),
 'hev/third-part/yaml':('heiher/yaml','efa36117a8646d26d12b58e05bac472d7854a70d')}
def run(*args,**kw):subprocess.run([str(x) for x in args],check=True,**kw)
for path,(repo,commit) in PINS.items():
 dest=OUT/path
 if not (dest/'.git').exists():
  dest.mkdir(parents=True,exist_ok=True)
  run('git','init',dest)
  run('git','-C',dest,'remote','add','origin','https://github.com/'+repo+'.git')
  run('git','-C',dest,'fetch','--depth','1','origin',commit)
  run('git','-C',dest,'checkout','--detach','FETCH_HEAD')
 actual=subprocess.check_output(['git','-C',str(dest),'rev-parse','HEAD'],text=True).strip()
 assert actual==commit,(path,actual)
 # These are disposable build clones, never the application working tree.
 run('git','-C',dest,'reset','--hard',commit)
# Only modify disposable build copies. Port 0 makes the kernel allocate the
# loopback listener atomically; the JNI callback publishes its actual port.
s=subprocess.check_output(['git','-C',str(OUT/'byedpi'),'show','HEAD:main.c'],text=True)
s=s.replace("if (val <= 0 || val > 0xffff || *end)","if (val < 0 || val > 0xffff || *end)")
(OUT/'byedpi/main.c').write_text(s)
s=subprocess.check_output(['git','-C',str(OUT/'byedpi'),'show','HEAD:proxy.c'],text=True)
s=s.replace('    return srvfd;', '    extern void pdm_listener_ready(int);\n    pdm_listener_ready(srvfd);\n    return srvfd;')
(OUT/'byedpi/proxy.c').write_text(s)
# Retain non-NEON ARMv7 compatibility; no optional SIMD instruction baseline.
for p in (OUT/'hev').rglob('Android.mk'):
 s=p.read_text().replace('-mfpu=neon','-mfpu=vfpv3-d16');p.write_text(s)
mk=OUT/'Android.mk'
mk.write_text('''LOCAL_PATH := $(call my-dir)
PDM_ANTI_PATH := $(LOCAL_PATH)
include $(PDM_ANTI_PATH)/hev/Android.mk
LOCAL_PATH := $(PDM_ANTI_PATH)
include $(CLEAR_VARS)
LOCAL_MODULE := pdm_antidpi
LOCAL_SRC_FILES := '''+' '.join('byedpi/'+p.name for p in sorted((OUT/'byedpi').glob('*.c')) if p.name!='win_service.c')+''' wrapper.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/byedpi
LOCAL_CFLAGS := -std=c11 -O2 -DANDROID_APP -Dmain=pdm_byedpi_main
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
LOCAL_CFLAGS += -mfpu=vfpv3-d16
endif
LOCAL_LDLIBS := -llog -ldl
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
include $(BUILD_SHARED_LIBRARY)
''')
shutil.copy2(ROOT/'native/antidpi/wrapper.c',OUT/'wrapper.c')
run(NDK/'ndk-build','NDK_PROJECT_PATH=null','APP_BUILD_SCRIPT='+str(mk),'APP_ABI=armeabi-v7a arm64-v8a x86 x86_64','APP_PLATFORM=android-21','APP_SUPPORT_FLEXIBLE_PAGE_SIZES=true','APP_STL=none','NDK_OUT='+str(OUT/'obj'),'NDK_LIBS_OUT='+str(OUT/'libs'),'-j2')
for abi in ['armeabi-v7a','arm64-v8a','x86','x86_64']:
 for lib in ['libpdm_antidpi.so','libhev-socks5-tunnel.so']:
  target=ROOT/'app/src/main/jniLibs'/abi/lib;shutil.copy2(OUT/'libs'/abi/lib,target)
print('Anti-DPI: four ABIs built for API 21 with 16 KiB alignment')
