plugins {
    alias(libs.plugins.kotlinJvm)
    application
    kotlin("plugin.serialization") version "2.3.21"
}

group = "com.milkcocoa.info.saphire"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":diskinfo-core"))
    implementation("io.github.milkcocoa0902:colotok:0.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.10.0")
    implementation("com.github.ajalt.clikt:clikt:5.1.0")

    implementation("io.ktor:ktor-server-core:3.4.3")
    implementation("io.ktor:ktor-server-cio:3.4.3")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.milkcocoa.info.sapphire.agent.MainKt")
}