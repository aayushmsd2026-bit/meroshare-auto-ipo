// Pure-Kotlin logic shared by the Android app: MeroShare client, rules and scheduling.
// It has no Android dependencies, so it can be built and tested on any computer.
plugins {
    kotlin("jvm") version "2.0.21"
}

group = "np.ipohelper"
version = "1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    compileOnly("org.json:json:20240303")          // Android ships org.json itself
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
