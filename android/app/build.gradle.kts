import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val signingPropertiesFile = rootProject.file("signing.properties")
val signingProperties = Properties()
if (signingPropertiesFile.isFile) {
    signingPropertiesFile.inputStream().use { signingProperties.load(it) }
}

fun signingValue(propertyName: String, environmentName: String): String? =
    signingProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }
        ?: System.getenv(environmentName)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "ZEROVPN_KEYSTORE_FILE")
val releaseStorePassword = signingValue("storePassword", "ZEROVPN_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "ZEROVPN_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "ZEROVPN_KEY_PASSWORD")
val hasReleaseSigningConfig = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }
val enableHevNative = providers.gradleProperty("enableHevNative")
    .map { it.equals("true", ignoreCase = true) }
    .getOrElse(false)
val enableVolunteerDebug = providers.gradleProperty("enableVolunteerDebug")
    .map { it.equals("true", ignoreCase = true) }
    .getOrElse(false)

android {
    namespace = "com.zerovpn.app"
    compileSdk = 36
    if (enableHevNative) {
        ndkVersion = "27.0.12077973"
    }

    defaultConfig {
        applicationId = "com.zerovpn.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 4
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "HEV_NATIVE_ENABLED", enableHevNative.toString())

        if (enableHevNative) {
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }

            externalNativeBuild {
                ndkBuild {
                    arguments += listOf(
                        "NDK_APPLICATION_MK=${file("src/main/jni/hev/Application.mk").absolutePath}",
                        "APP_CFLAGS+=-DPKGNAME=com/zerovpn/app/volunteer/tun2socks -DCLSNAME=HevNativeLoader",
                    )
                }
            }
        }
    }

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            buildConfigField("boolean", "VOLUNTEER_DEBUG_ENABLED", enableVolunteerDebug.toString())
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            buildConfigField("boolean", "VOLUNTEER_DEBUG_ENABLED", "false")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,DEPENDENCIES,LICENSE,LICENSE.txt,NOTICE,NOTICE.txt,*.kotlin_module,/*.properties}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
            excludes += "/META-INF/versions/**"
            excludes += "/META-INF/maven/**"
            excludes += "/THIRD_PARTY_LICENSES.txt"
        }
    }

    if (enableHevNative) {
        externalNativeBuild {
            ndkBuild {
                path = file("../native/hev-socks5-tunnel/Android.mk")
            }
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.okhttp)
    implementation(libs.nanohttpd)
    implementation(libs.jsch)
    implementation(libs.androidx.browser)
    implementation(libs.wireguard.tunnel)
    implementation(libs.tor.android)
    implementation(libs.zxing.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
