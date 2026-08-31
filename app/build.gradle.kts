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
val debugCaCertificate =
    providers
        .gradleProperty("omniandDebugCaCert")
        .orElse(providers.environmentVariable("OMNIAND_DEBUG_CA_CERT"))
val debugTurnHostAlias =
    providers
        .gradleProperty("omniandDebugTurnHostAlias")
        .orElse(providers.environmentVariable("OMNIAND_DEBUG_TURN_HOST_ALIAS"))
        .orElse("")
        .get()
val debugIceRelayOnly =
    providers
        .gradleProperty("omniandDebugIceRelayOnly")
        .orElse(providers.environmentVariable("OMNIAND_DEBUG_ICE_RELAY_ONLY"))
        .orElse("false")
        .map(String::toBooleanStrict)
        .get()
val debugNetworkSecurityResources =
    layout.buildDirectory.dir("generated/debugNetworkSecurityResources")
val generateDebugNetworkSecurityResources by tasks.registering {
    val certificatePath = debugCaCertificate.orNull.orEmpty()
    inputs.property("certificatePath", certificatePath)
    if (certificatePath.isNotEmpty()) inputs.file(certificatePath)
    outputs.dir(debugNetworkSecurityResources)
    doLast {
        val resourceRoot = debugNetworkSecurityResources.get().asFile
        val xmlDirectory = resourceRoot.resolve("xml").apply { mkdirs() }
        val rawDirectory = resourceRoot.resolve("raw").apply { mkdirs() }
        val certificateOutput = rawDirectory.resolve("omniand_debug_ca.pem")
        val certificate = certificatePath.takeIf(String::isNotEmpty)?.let(::file)
        if (certificate != null) {
            check(certificate.isFile) { "OMNIAND_DEBUG_CA_CERT does not point to a file" }
            certificate.copyTo(certificateOutput, overwrite = true)
        } else {
            certificateOutput.delete()
        }
        val anchors =
            if (certificate != null) {
                "<certificates src=\"@raw/omniand_debug_ca\" />"
            } else {
                "<certificates src=\"system\" />"
            }
        xmlDirectory
            .resolve("debug_network_security_config.xml")
            .writeText(
                """<?xml version="1.0" encoding="utf-8"?>
                <network-security-config>
                    <base-config cleartextTrafficPermitted="true">
                        <trust-anchors><certificates src="system" /></trust-anchors>
                    </base-config>
                    <debug-overrides>
                        <trust-anchors>$anchors</trust-anchors>
                    </debug-overrides>
                </network-security-config>
                """
                    .trimIndent()
            )
    }
}

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
        from(platformShellOutput) { into("web/shell") }
        into(layout.buildDirectory.dir("generated/embeddedWebAssets"))
        doFirst {
            check(platformShellSource.asFile.isDirectory) {
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
        debug {
            buildConfigField(
                "String",
                "DEBUG_TURN_HOST_ALIAS",
                "\"${debugTurnHostAlias.replace("\"", "\\\"")}\"",
            )
            buildConfigField("boolean", "DEBUG_ICE_RELAY_ONLY", "$debugIceRelayOnly")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "DEBUG_TURN_HOST_ALIAS", "\"\"")
            buildConfigField("boolean", "DEBUG_ICE_RELAY_ONLY", "false")
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
    sourceSets.getByName("debug").res.srcDir(debugNetworkSecurityResources)
}

tasks.named("preBuild").configure { dependsOn(bundleWrapperTemplate, syncEmbeddedWeb) }

tasks
    .matching { it.name == "preDebugBuild" }
    .configureEach {
        dependsOn(generateDebugNetworkSecurityResources)
    }

dependencies {
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("io.github.webrtc-sdk:android:144.7559.14")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.android.tools.build:apksig:8.7.3")
    implementation("io.ktor:ktor-server-cio:3.3.1")
    implementation("io.ktor:ktor-server-core:3.3.1")
    implementation("io.ktor:ktor-server-websockets:3.3.1")
    implementation("io.ktor:ktor-client-cio:3.3.1")
    implementation("io.ktor:ktor-client-okhttp:3.3.1")
    implementation("io.ktor:ktor-client-websockets:3.3.1")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.ktor:ktor-server-test-host:3.3.1")
    testImplementation("org.json:json:20240303")
    testImplementation("org.mockito:mockito-core:5.21.0")
}
