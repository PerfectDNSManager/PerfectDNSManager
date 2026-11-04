import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// ─── Version : correspond au numéro du zip livré ───────────────────────────
val versionPropsFile = file("version.properties")
if (!versionPropsFile.exists()) {
    versionPropsFile.writeText("VERSION_MAJOR=1\nVERSION_MINOR=0\nVERSION_BUILD=21\n")
}
val versionProps = Properties()
FileInputStream(versionPropsFile).use { fis -> versionProps.load(fis) }

val vMajor = (versionProps["VERSION_MAJOR"] as? String ?: "1").toInt()
val vMinor = (versionProps["VERSION_MINOR"] as? String ?: "0").toInt()
val vBuild = (versionProps["VERSION_BUILD"] as? String ?: "21").toInt()
val vSuffix = (versionProps["VERSION_SUFFIX"] as? String ?: "").trim()

val computedVersionCode = vMajor * 10000 + vMinor * 100 + vBuild
val computedVersionName = if (vSuffix.isNotEmpty()) "$vMajor.$vMinor.$vBuild-$vSuffix" else "$vMajor.$vMinor.$vBuild"
val buildTimestamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())

android {
    namespace = "app.perfectdnsmanager"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.perfectdnsmanager"
        minSdk = 21
        targetSdk = 34
        versionCode = computedVersionCode
        versionName = computedVersionName

        buildConfigField("String", "PDM_BASE_URL", "\"${if (vSuffix.startsWith("beta")) "https://beta.perfectdnsmanager.app" else "https://perfectdnsmanager.app"}\"")
        buildConfigField("String", "BUILD_TIMESTAMP", "\"$buildTimestamp\"")
        buildConfigField("String", "VERSION_DISPLAY", "\"v$computedVersionName (build $vBuild — $buildTimestamp)\"")
        buildConfigField("int", "BUILD_NUMBER", "$vBuild")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
        aidl = true
    }

    // Conflit BouncyCastle : bcpkix/bcprov/bcutil shippent chacun
    // META-INF/versions/9/OSGI-INF/MANIFEST.MF (signed JAR multi-release).
    // Idem pour leurs LICENSE/NOTICE — on pickFirst pour éviter les
    // DuplicateRelativeFileException au mergeJavaResource.
    packaging {
        resources {
            pickFirsts += listOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/INDEX.LIST",
            )
            excludes += listOf(
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/DEPENDENCIES",
                "META-INF/*.kotlin_module",
                "META-INF/services/java.net.spi.InetAddressResolverProvider",
                "META-INF/services/sun.net.spi.nameservice.NameServiceDescriptor",
            )
        }
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("KEYSTORE_FILE")
                ?: rootProject.file("signing/release-keystore.jks").takeIf { it.exists() }?.absolutePath
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".design"
            versionNameSuffix = "-local"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val ksPath = System.getenv("KEYSTORE_FILE")
                ?: rootProject.file("signing/release-keystore.jks").takeIf { it.exists() }?.absolutePath
            if (ksPath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    @Suppress("DEPRECATION")
    kotlinOptions {
        jvmTarget = "17"
    }
}

// ─── Renommer l'APK de sortie avec la version ──────────────────────────────
android.applicationVariants.all {
    outputs.all {
        val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
        output.outputFileName = "PerfectDNSManager-v${computedVersionName}-${buildType.name}.apk"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat.appcompat)
    implementation(libs.androidx.recyclerview.recyclerview)
    implementation(libs.com.google.code.gson.gson)
    implementation(libs.com.squareup.okhttp3.okhttp)
    implementation(libs.dnsjava.dnsjava)
    implementation(libs.org.slf4j.slf4j.nop)

    // ─── ADB pairing (Android 11+ Wireless Debugging) ───
    // Vendored from Shizuku (Apache 2.0) : voir app/src/main/java/moe/shizuku/manager/adb/.
    // BouncyCastle pour cert X509 (AdbKey signe son cert avec sa propre RSA).
    // Conscrypt pour exportKeyingMaterial (RFC 5705) requis par SPAKE2 pairing.
    implementation(libs.org.bouncycastle.bcpkix.jdk18on)
    implementation(libs.org.bouncycastle.bcprov.jdk18on)
    implementation(libs.org.conscrypt.conscrypt.android)

    // QUIC client pour DoQ (DNS over QUIC)
    implementation(libs.tech.kwik.kwik)
    implementation(libs.tech.kwik.agent15) // Android RSA-PSS algorithm mapping

    // Argon2id KDF (JNI sur libargon2) — partage E2EE, remplace PBKDF2
    implementation(libs.com.lambdapioneer.argon2kt.argon2kt)

    // Coroutines + lifecycleScope pour Activity-bound IO (replaces Thread { }.start())
    implementation(libs.org.jetbrains.kotlinx.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.lifecycle.runtime.ktx)

    // Core library desugaring (java.time.Duration requis par kwik, API 26+)
    coreLibraryDesugaring(libs.com.android.tools.desugar.jdk.libs)

    testImplementation(libs.junit.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.espresso.core)
}
