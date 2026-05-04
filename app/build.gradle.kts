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
    namespace = "net.appstorefr.perfectdnsmanager"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.appstorefr.perfectdnsmanager"
        minSdk = 21
        targetSdk = 34
        versionCode = computedVersionCode
        versionName = computedVersionName

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
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.kittinunf.fuel:fuel:2.3.1")
    implementation("com.github.kittinunf.fuel:fuel-android:2.3.1")
    implementation("com.github.kittinunf.result:result:3.1.0")

    // ─── ADB pairing (Android 11+ Wireless Debugging) ───
    // Vendored from Shizuku (Apache 2.0) : voir app/src/main/java/moe/shizuku/manager/adb/.
    // BouncyCastle pour cert X509 (AdbKey signe son cert avec sa propre RSA).
    // Conscrypt pour exportKeyingMaterial (RFC 5705) requis par SPAKE2 pairing.
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.conscrypt:conscrypt-android:2.5.2")

    // QUIC client pour DoQ (DNS over QUIC)
    implementation("tech.kwik:kwik:0.10.8")

    // Argon2id KDF (JNI sur libargon2) — partage E2EE, remplace PBKDF2
    implementation("com.lambdapioneer.argon2kt:argon2kt:1.5.0")

    // Core library desugaring (java.time.Duration requis par kwik, API 26+)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
