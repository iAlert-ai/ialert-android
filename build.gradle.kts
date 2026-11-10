import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    `java-library`
    `maven-publish`
}

group = "ir.ialert"
version = "0.2.0"

repositories {
    mavenCentral()
}

dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")
    // org.json ships with Android; the JVM artifact is only needed off-device.
    compileOnly("org.json:json:20250517")

    testImplementation(kotlin("test"))
    testImplementation("org.json:json:20250517")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("release") {
            artifactId = "ialert-android"
            from(components["java"])

            pom {
                name = "ialert-android"
                description = "iAlert Android SDK: device registration, Gateway WebSocket and Inbox sync."
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

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

tasks.test {
    useJUnitPlatform()
}
