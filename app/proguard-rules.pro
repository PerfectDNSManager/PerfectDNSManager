# ──────────────────────────────────────────────────────────────────────────
#  Perfect DNS Manager — règles R8
#
#  L'app était livrée SANS minification ni élagage des ressources : l'APK
#  embarquait kwik, BouncyCastle et Conscrypt en entier. Les règles ci-dessous
#  couvrent les trois choses que R8 ne peut pas deviner : la réflexion de Gson,
#  les méthodes natives (JNI), et le chargement par nom des fournisseurs de
#  crypto et du provider QUIC.
# ──────────────────────────────────────────────────────────────────────────

# ── Gson : sérialisation par réflexion ────────────────────────────────────
# Les noms de champs SONT le format de fichier (export/import de config, prefs).
# Les renommer casserait la lecture de toute config déjà enregistrée.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault
-keep class app.perfectdnsmanager.data.** { <fields>; <init>(...); }
-keep class app.perfectdnsmanager.util.UrlBlockingTester$* { <fields>; }
-keep class app.perfectdnsmanager.util.DnsLeakTester$* { <fields>; }
-keep class app.perfectdnsmanager.util.SpeedTester$* { <fields>; }
-keep class app.perfectdnsmanager.InternetSpeedtestActivity$OoklaServer { <fields>; }
# TypeToken s'appuie sur la signature générique conservée ci-dessus.
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# ── Enums : Gson les résout par nom ───────────────────────────────────────
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── JNI : Argon2 et Conscrypt appellent du natif dans les deux sens ───────
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.lambdapioneer.argon2kt.** { *; }
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**

# ── BouncyCastle : providers chargés par nom (AdbKey signe son cert X509) ─
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.crypto.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# ── kwik / agent15 (DoQ) : services et implémentations résolus dynamiquement
-keep class tech.kwik.** { *; }
-dontwarn tech.kwik.**

# ── OkHttp / Okio ─────────────────────────────────────────────────────────
-dontwarn okhttp3.internal.platform.**
-dontwarn org.openjsse.**
-dontwarn okio.**

# ── ADB embarqué (Shizuku vendored + cgutman) : réflexion sur les messages ─
-keep class moe.shizuku.manager.adb.** { *; }
-keep class com.cgutman.adblib.** { *; }

# ── Services Android déclarés dans le manifeste ───────────────────────────
-keep class app.perfectdnsmanager.service.** extends android.app.Service
-keep class app.perfectdnsmanager.service.BootReceiver

# Conserver les traces exploitables dans les rapports d'erreur.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# Desktop-only optional DNS service providers and compile-time annotations.
-dontwarn lombok.Generated
-dontwarn sun.net.spi.nameservice.**
-dontwarn org.xbill.DNS.spi.DnsjavaInetAddressResolverProvider
