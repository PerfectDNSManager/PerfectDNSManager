# ─── PerfectDNSManager — règles ProGuard/R8 (release) ───────────────────────
# Préserve la stack trace lisible dans les rapports (file:line).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ─── Données sérialisées en JSON (Gson) ──────────────────────────────────────
# Les data class de l'app sont (dé)sérialisées via reflection — sans -keep,
# R8 renomme les fields et Gson lit du nul.
-keep class net.appstorefr.perfectdnsmanager.data.** { *; }
-keepclassmembers class net.appstorefr.perfectdnsmanager.data.** {
    <fields>;
    <init>(...);
}

# Gson : tokens TypeAdapter via génériques + AnnotationException sur enum
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepattributes Signature,*Annotation*,EnclosingMethod,InnerClasses
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ─── OkHttp / Okio ──────────────────────────────────────────────────────────
# OkHttp livre déjà ses règles consumer-proguard, mais on durcit pour éviter
# les warnings sur les optionnels (BouncyCastle/Conscrypt, Animal Sniffer).
-dontwarn okhttp3.internal.platform.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# ─── Conscrypt (TLS provider, native bridge) ────────────────────────────────
-keep class org.conscrypt.** { *; }
-keep class org.conscrypt.NativeCrypto { *; }

# ─── BouncyCastle (génération clés ADB) ─────────────────────────────────────
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ─── Argon2Kt (KDF natif via JNI) ───────────────────────────────────────────
-keep class com.lambdapioneer.argon2kt.** { *; }

# ─── Kwik (DoQ — QUIC client) ───────────────────────────────────────────────
-keep class tech.kwik.** { *; }
-dontwarn tech.kwik.**

# ─── Fuel (HTTP client) ─────────────────────────────────────────────────────
-keep class com.github.kittinunf.fuel.** { *; }
-keep class com.github.kittinunf.result.** { *; }
-dontwarn com.github.kittinunf.**

# ─── ADB lib vendored (cgutman/adblib + Shizuku adb) ────────────────────────
# Ces libs utilisent reflection pour la serial des AdbMessage, et sont
# référencées par leur nom complet dans des log strings.
-keep class com.cgutman.adblib.** { *; }
-keep class moe.shizuku.manager.adb.** { *; }
-keep class net.appstorefr.perfectdnsmanager.adblib.** { *; }

# ─── App's own service / receiver entry points ──────────────────────────────
# Manifest les référence par nom qualifié — R8 pourrait les supprimer comme
# unused si le scan ne descend pas dans les overrides. Belt-and-suspenders.
-keep class net.appstorefr.perfectdnsmanager.service.** { *; }
-keep class net.appstorefr.perfectdnsmanager.MainActivity { *; }

# ─── Préserver onClick="..." attribué via XML layout ────────────────────────
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}

# ─── Coroutines (réflexion sur les continuations) ──────────────────────────
# Si on bascule en coroutines, déjà prêt.
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ─── Kotlin (les méta utilisées par reflection lors du fromJson<T>()) ──────
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings {
    <fields>;
}
