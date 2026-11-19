import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val releaseVersion = Properties()
rootProject.file("version.properties").inputStream().use { releaseVersion.load(it) }
val publicVersion = releaseVersion.getProperty("versionName")
if (!Regex("""\d+\.\d+\.\d+""").matches(publicVersion)) {
    throw GradleException("version.properties must contain a semantic version")
}

// Actions restores the secrets into temporary files and supplies only this path.
// There is no retrievable keystore or password outside GitHub secrets.
val releaseSigningPropertiesFile = providers.gradleProperty("trove.keystore.properties")
    .orNull?.let(::File)
val releaseSigningProperties = Properties()
val releaseSigningError = when {
    releaseSigningPropertiesFile == null -> "No release signing properties supplied."
    !releaseSigningPropertiesFile.isFile -> "Release signing properties file is missing."
    else -> try {
        releaseSigningPropertiesFile.inputStream().use(releaseSigningProperties::load)
        val missing = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
            .filter { releaseSigningProperties.getProperty(it).isNullOrBlank() }
        when {
            missing.isNotEmpty() -> "Release signing properties missing: ${missing.joinToString()}."
            !file(releaseSigningProperties.getProperty("storeFile")).isFile ->
                "Release signing keystore file is missing."
            else -> null
        }
    } catch (_: Exception) {
        "Release signing properties could not be read."
    }
}

val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "Reject release packaging without Actions-provided signing material."
    inputs.property("signingError", releaseSigningError.orEmpty())
    doLast {
        val signingError = inputs.properties.getValue("signingError") as String
        if (signingError.isNotEmpty()) {
            throw GradleException(
                "$signingError Release signing requires GitHub Actions secrets " +
                    "TROVE_KEYSTORE_BASE64 / TROVE_KEYSTORE_PROPERTIES and " +
                    "-Ptrove.keystore.properties=<temporary properties file>. " +
                    "There is no local key recovery and no debug-signing fallback."
            )
        }
    }
}

// Guard actual APK/AAB production, including indirect aggregate task requests.
// Do not guard release analysis/compilation: full lint must remain keyless.
tasks.configureEach {
    if (name in setOf("validateSigningRelease", "packageRelease", "packageReleaseBundle", "signReleaseBundle")) {
        dependsOn(verifyReleaseSigning)
    }
}

android {
    namespace = "app.trove.debrid"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.trove.debrid"
        minSdk = 26
        targetSdk = 34
        versionCode = releaseVersion.getProperty("versionCode").toInt()
        versionName = publicVersion
    }

    testOptions.unitTests.isIncludeAndroidResources = true

    buildFeatures {
        buildConfig = true
        compose = true
    }
    kotlin { jvmToolchain(17) }

    signingConfigs {
        create("release") {
            // Keep a dedicated release config even when keyless; never use debug signing.
            if (releaseSigningError == null) {
                storeFile = file(releaseSigningProperties.getProperty("storeFile"))
                storePassword = releaseSigningProperties.getProperty("storePassword")
                keyAlias = releaseSigningProperties.getProperty("keyAlias")
                keyPassword = releaseSigningProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"
        }
        release {
            // Rotated 2026-11-16; signing material exists only in GitHub secrets.
            // First signed installation must be clean; subsequent updates keep this signer.
            signingConfig = signingConfigs.getByName("release")
            // R8 strips and optimises: smaller APK, faster start.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    packaging {
        resources {
            excludes.addAll(
                listOf("kotlin/**", "META-INF/*.version", "META-INF/*.kotlin_module", "DebugProbesKt.bin"),
            )
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.material3)
    implementation(libs.material.icons.core)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.gson)
    implementation(libs.security.crypto)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.coroutines.core)
    testImplementation(libs.coroutines.test)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
