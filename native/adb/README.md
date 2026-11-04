# Rebuilding the ADB pairing JNI library

Run `ANDROID_HOME=/path/to/sdk python3 tools/build_adb_native.py` on Linux, with NDK **29.0.14206865**, CMake **3.22.1**, Git and Go installed. The script builds all four ABIs, targets Android API 21 and enables 16 KiB ELF alignment. Outputs replace `app/src/main/jniLibs/*/libadb.so`; the release workflow runs this before compiling the APK.

Sources:
- JNI pairing implementation: RikkaApps/Shizuku commit `b844bc491f1790c72328e1a8e5b2349f8978f0ea`, `manager/src/main/jni/adb_pairing.cpp`, `adb_pairing.h`, `logging.h` (Apache 2.0; license included).
- BoringSSL: google/boringssl commit `79048f1f1d8e6b7f9ca59b95c24486c8149122a4`, the source pinned by BoringSSL_Android's 20251124 package. Git fetch uses this exact immutable commit; no branch HEAD or unversioned binary download. License included.

PDM changes: input bounds, fixed-size buffers, JNI/allocation error checks, the availability entry point, and error propagation from JNI registration. Kotlin owns context lifetime and synchronizes native operations/destruction so repeated close cannot double-free a context. Only pairing is built; Shizuku process-starting/root components are excluded.

Run `tools/check_native_alignment.py APK` and build-tools 35 `zipalign -c -P 16 4 APK` after packaging. `NativeCompatibilityInstrumentedTest` exercises the native SPAKE client/server exchange and repeated destruction on API 30+, along with Conscrypt TLS/exporter and Argon2 interoperability. Rebuilding source is not an exhaustive cryptographic audit of BoringSSL.
