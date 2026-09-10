plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.haseltonmediagroup.realdice3d"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.haseltonmediagroup.realdice3d"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.0.3"
    }

    val uploadKeystore = providers.environmentVariable("REALDICE_KEYSTORE_PATH").orNull
    if (uploadKeystore != null) {
        signingConfigs.create("upload") {
            storeFile = file(uploadKeystore)
            storePassword = providers.environmentVariable("REALDICE_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("REALDICE_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("REALDICE_KEY_PASSWORD").get()
            storeType = "JKS"
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("upload")
    }

    buildFeatures { viewBinding = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.android.gms:play-services-ads:23.6.0")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")
}
