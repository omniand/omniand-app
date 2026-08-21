plugins {
    id("com.android.application")
}

android {
    namespace = "dev.omniand.wrapper.runtime"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.omniand.generated.placeholderxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName =
            "OMNIAND_VERSION_NAME_PLACEHOLDER_XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core:1.15.0")
}
