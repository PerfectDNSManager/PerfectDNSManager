# Gson persisted/exported models must preserve field names across app upgrades.
-keep class app.perfectdnsmanager.data.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
# JNI_OnLoad resolves PairingContext by name and registers these methods.
-keep class moe.shizuku.manager.adb.PairingContext { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
# Argon2/Conscrypt ship consumer rules. Retain the production KDF entry point
# so the instrumented interop test also exercises the optimized APK.
-keep class app.perfectdnsmanager.util.EncryptedSharer$Companion { *; }
# Agent15's desktop-only CN fallback uses JNDI. PDM's custom trust manager
# requires a matching SAN and rejects missing/mismatched SAN before that fallback.
# Do not suppress TLS warnings generally or disable certificate validation.
-dontwarn javax.naming.InvalidNameException
-dontwarn javax.naming.ldap.LdapName
-dontwarn javax.naming.ldap.Rdn
# Compile-time marker and unused desktop DNS SPI (service entries excluded).
-dontwarn lombok.Generated
-dontwarn sun.net.spi.nameservice.NameServiceDescriptor
# Keep the small interface enumerator out of caller try/catch register merges.
# The API 21 ART verifier rejects that merged shape after dex compilation.
-keep class app.perfectdnsmanager.util.LocalNetworkInfo { *; }
