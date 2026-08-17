plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val bundleWrapperTemplate by
    tasks.registering(Copy::class) {
        dependsOn(":wrappers:template:assembleDebug")
        from(
            rootProject.layout.projectDirectory.file(
                "wrappers/template/build/outputs/apk/debug/template-debug.apk"
            )
        )
        into(layout.buildDirectory.dir("generated/wrapperAssets/wrappers"))
        rename { "template.apk" }
    }

val embeddedStoreSource = rootProject.layout.projectDirectory.dir("../omniAndStore/apps/store")
val platformShellSource = rootProject.layout.projectDirectory.dir("../omniAndStore/platform/shell")
val syncEmbeddedWeb by
    tasks.registering(Sync::class) {
        inputs.dir(embeddedStoreSource)
        inputs.dir(platformShellSource)
        from(embeddedStoreSource) { into("web/apps/store") }
        from(platformShellSource) { into("web/shell") }
        into(layout.buildDirectory.dir("generated/embeddedWebAssets"))
        doFirst {
            check(embeddedStoreSource.asFile.isDirectory) {
                "Embedded Store source is missing at ${embeddedStoreSource.asFile}"
            }
            check(platformShellSource.asFile.isDirectory) {
                "Platform Home source is missing at ${platformShellSource.asFile}"
            }
        }
    }

android {
    namespace = "dev.omniand.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.omniand.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "PLATFORM_HOST", "\"phone.example.org\"")
        buildConfigField("String", "STORE_URL", "\"http://192.168.1.11:5173/\"")
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    sourceSets
        .getByName("main")
        .assets
        .srcDirs(
            layout.buildDirectory.dir("generated/wrapperAssets"),
            layout.buildDirectory.dir("generated/embeddedWebAssets"),
        )
}

tasks.named("preBuild").configure { dependsOn(bundleWrapperTemplate, syncEmbeddedWeb) }

dependencies {
    implementation("androidx.core:core:1.15.0")
    implementation("com.android.tools.build:apksig:8.7.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
