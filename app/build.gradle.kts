import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    // namespace stays com.jarvis.ai so no Kotlin source has to move.
    // applicationId is the AURIX brand id, which also avoids any signature
    // clash with an older com.jarvis.ai build still known to the device.
    namespace = "com.jarvis.ai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aurix.ai"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "1.13"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            val storePath = keystoreProps.getProperty("jarvis.storeFile")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = keystoreProps.getProperty("jarvis.storePassword")
                keyAlias = keystoreProps.getProperty("jarvis.keyAlias")
                keyPassword = keystoreProps.getProperty("jarvis.keyPassword")
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
            if (keystoreProps.getProperty("jarvis.storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Google Gemini SDK (vision / text generation via core.router)
    implementation("com.google.ai.client.generativeai:generativeai:0.9.0")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // Open-Jarvis integrations
    implementation(libs.androidx.security.crypto)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.androidx.work.runtime.ktx)

    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("org.robolectric:robolectric:4.14.1") {
        // NOTE: the uber jar stays on the classpath on purpose. Its JNI lib
        // for linux-aarch_64 does not exist, so installing the provider would
        // crash on ARM64 hosts — but the tests below opt out explicitly via
        // @ConscryptMode(Mode.OFF), which makes Robolectric skip the install
        // entirely while keeping the classes resolvable.
        isTransitive = true
    }
}
