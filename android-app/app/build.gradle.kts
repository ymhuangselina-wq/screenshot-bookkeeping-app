import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val releaseSigningFile = rootProject.file(".signing/release.properties")
val releaseSigning = Properties().apply {
    if (releaseSigningFile.exists()) releaseSigningFile.inputStream().use { load(it) }
}
val localPropertiesFile = rootProject.file("local.properties")
val localProperties = Properties().apply {
    if (localPropertiesFile.exists()) localPropertiesFile.inputStream().use { load(it) }
}
val serviceUrl = providers.gradleProperty("serviceUrl")
    .orElse(localProperties.getProperty("serviceUrl", "https://example.com"))
    .get()
    .trimEnd('/')
val personalMode = providers.gradleProperty("personalMode").orElse("false").get().toBoolean()
val personalAccessKey = providers.environmentVariable("PERSONAL_ACCESS_KEY").orElse("").get()
require(serviceUrl.startsWith("https://") && !serviceUrl.contains('"')) { "serviceUrl must be an HTTPS URL" }

android {
    namespace = "com.example.screenshotbookkeeping"
    compileSdk = 35

    defaultConfig {
        applicationId = if (personalMode) "com.example.screenshotbookkeeping" else "com.example.screenshotbookkeeping.invite"
        minSdk = 26
        targetSdk = 35
        versionCode = if (personalMode) 12 else 36
        versionName = if (personalMode) "1.0.0" else "1.1.0-invite"
        buildConfigField("String", "SERVICE_URL", "\"$serviceUrl\"")
        buildConfigField("boolean", "PERSONAL_MODE", personalMode.toString())
        buildConfigField("String", "PERSONAL_ACCESS_KEY", "\"$personalAccessKey\"")
    }
    signingConfigs {
        if (releaseSigningFile.exists()) create("invitationRelease") {
            storeFile = rootProject.file(releaseSigning.getProperty("storeFile"))
            storePassword = releaseSigning.getProperty("storePassword")
            keyAlias = releaseSigning.getProperty("keyAlias")
            keyPassword = releaseSigning.getProperty("keyPassword")
        }
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            if (releaseSigningFile.exists()) signingConfig = signingConfigs.getByName("invitationRelease")
        }
        getByName("debug") { if (!personalMode) applicationIdSuffix = ".debug" }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
