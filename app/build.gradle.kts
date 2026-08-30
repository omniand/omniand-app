import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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

val webProject = rootProject.layout.projectDirectory.dir("../omniAndStore")
val platformShellSource = rootProject.layout.projectDirectory.dir("../omniAndStore/apps/shell")
val platformShellOutput =
    rootProject.layout.projectDirectory.dir("../omniAndStore/build/embedded/shell")
val platformPairingSource =
    rootProject.layout.projectDirectory.dir("../omniAndStore/platform/pairing")
val platformHost =
    providers
        .gradleProperty("omniandPlatformHost")
        .orElse(providers.environmentVariable("OMNIAND_PLATFORM_HOST"))
        .orElse("phone.example.org")
        .get()
        .lowercase()
val relayUrl =
    providers
        .gradleProperty("omniandRelayUrl")
        .orElse(providers.environmentVariable("OMNIAND_RELAY_URL"))
        .orElse("wss://relay.$platformHost/_omniand/tunnel/v1")
        .get()

check(
    platformHost.length <= 253 &&
        platformHost.matches(
            Regex(
                "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+"
            )
        )
) {
    "omniandPlatformHost must be a lowercase DNS hostname"
}

check(relayUrl.startsWith("wss://") || relayUrl.startsWith("ws://")) {
    "omniandRelayUrl must use wss:// or ws://"
}

check(relayUrl.matches(Regex("wss?://[^\\s\\\"]+"))) {
    "omniandRelayUrl must be a single valid URL value"
}

val buildPlatformShell by
    tasks.registering(Exec::class) {
        workingDir(webProject)
        commandLine("npm", "run", "build:shell")
        inputs.dir(platformShellSource)
        inputs.dir(webProject.dir("src"))
        inputs.file(webProject.file("package-lock.json"))
        inputs.file(webProject.file("vite.app.config.js"))
        outputs.dir(platformShellOutput)
    }

val syncEmbeddedWeb by
    tasks.registering(Sync::class) {
        dependsOn(buildPlatformShell)
        inputs.dir(platformShellOutput)
        inputs.dir(platformPairingSource)
        from(platformShellOutput) { into("web/shell") }
        from(platformPairingSource) { into("web/pairing") }
        into(layout.buildDirectory.dir("generated/embeddedWebAssets"))
        doFirst {
            check(
                platformShellSource.asFile.isDirectory && platformPairingSource.asFile.isDirectory
            ) {
                "Platform Home source is missing at ${platformShellSource.asFile}"
            }
        }
    }

android {
    namespace = "dev.omniand.hub"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.omniand.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "PLATFORM_HOST", "\"$platformHost\"")
        buildConfigField("String", "RELAY_URL", "\"${relayUrl.replace("\"", "\\\"")}\"")
        buildConfigField("String", "CATALOG_URL", "\"http://192.168.1.11:5173/\"")
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
    kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

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
    implementation("io.ktor:ktor-server-cio:3.3.1")
    implementation("io.ktor:ktor-server-core:3.3.1")
    implementation("io.ktor:ktor-client-cio:3.3.1")
    implementation("io.ktor:ktor-client-websockets:3.3.1")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.ktor:ktor-server-test-host:3.3.1")
    testImplementation("org.json:json:20240303")
    testImplementation("org.mockito:mockito-core:5.21.0")
}
