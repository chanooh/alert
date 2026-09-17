import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val releaseKeystoreFile = System.getenv("ANDROID_KEYSTORE_FILE")
val releaseKeystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseKeystoreFile,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }
val miPushAppId = System.getenv("MIPUSH_APP_ID")?.trim().orEmpty()
val miPushAppKey = System.getenv("MIPUSH_APP_KEY")?.trim().orEmpty()

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.chanooh.alert"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.chanooh.alert"
        minSdk = 28
        targetSdk = 36
        versionCode = 9
        versionName = "0.2.0"
        buildConfigField("String", "MIPUSH_APP_ID", "\"$miPushAppId\"")
        buildConfigField("String", "MIPUSH_APP_KEY", "\"$miPushAppKey\"")
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(requireNotNull(releaseKeystoreFile))
                storePassword = requireNotNull(releaseKeystorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
        buildTypes {
            getByName("release") {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    if (file("libs/MiPush_SDK_Client.aar").isFile) {
        sourceSets.getByName("main").java.srcDir("src/mipush/java")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Official mainland Mi Push AAR, intentionally supplied by the owner from
    // Xiaomi's developer portal. The dedicated SDK receiver source set is
    // included only when this official AAR is present.
    implementation(files("libs/MiPush_SDK_Client.aar"))
    implementation(platform("androidx.compose:compose-bom:2025.12.00"))
    implementation("androidx.activity:activity-compose:1.12.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")
    implementation("androidx.datastore:datastore-preferences:1.2.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("com.hivemq:hivemq-mqtt-client-shaded:1.3.17")

    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
