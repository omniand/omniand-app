plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("com.diffplug.spotless") version "8.9.0"
}

spotless {
    kotlin {
        target("app/src/main/**/*.kt", "app/src/test/**/*.kt")
        ktfmt().kotlinlangStyle()
    }
    kotlinGradle {
        target("*.gradle.kts", "app/*.gradle.kts", "wrappers/template/*.gradle.kts")
        ktfmt().kotlinlangStyle()
    }
    java {
        target("wrappers/template/src/**/*.java")
        googleJavaFormat().aosp()
    }
    format("projectFiles") {
        target(".editorconfig")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

subprojects {
    tasks
        .matching { it.name == "check" }
        .configureEach {
            dependsOn(rootProject.tasks.named("spotlessCheck"))
        }
}
