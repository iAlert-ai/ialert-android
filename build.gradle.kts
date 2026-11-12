plugins {
    id("com.android.library") version "9.1.0"
    `maven-publish`
}

group = "ir.ialert"
version = "0.7.0"

android {
    namespace = "ir.ialert.sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main").kotlin.directories += "src/main/kotlin"
        getByName("test").kotlin.directories += "src/test/kotlin"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        // Readable by apps and plugins on Kotlin 2.0 or later.
        languageVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0
        apiVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0
    }
}

dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.firebase:firebase-messaging:25.0.1")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.work:work-runtime:2.10.0")

    testImplementation(kotlin("test-junit"))
    // The Android stub of org.json throws in unit tests; use the real one off-device.
    testImplementation("org.json:json:20250517")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                artifactId = "ialert-android"
                from(components["release"])

                pom {
                    name = "ialert-android"
                    description = "iAlert Android SDK: one-line setup with FCM, Gateway WebSocket, Inbox sync and background sync."
                    url = "https://github.com/iAlert-ai/ialert-android"
                    licenses {
                        license {
                            name = "Apache License, Version 2.0"
                            url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                        }
                    }
                    scm {
                        url = "https://github.com/iAlert-ai/ialert-android"
                    }
                }
            }
        }
    }
}
