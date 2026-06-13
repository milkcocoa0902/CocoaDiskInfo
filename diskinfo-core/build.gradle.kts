plugins {
    alias(libs.plugins.kotlinJvm)
    kotlin("plugin.serialization") version "2.3.21"
}

group = "com.milkcocoa.info.saphire"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.milkcocoa0902:colotok:0.4.2")
    testImplementation(kotlin("test"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}