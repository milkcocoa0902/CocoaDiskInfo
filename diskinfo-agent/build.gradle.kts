plugins {
    alias(libs.plugins.kotlinJvm)
    application
    kotlin("plugin.serialization") version "2.3.21"
    id("com.gradleup.shadow")
}

group = "com.milkcocoa.info.saphire"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":diskinfo-core"))
    implementation("io.github.milkcocoa0902:colotok:0.4.2")
    implementation("io.github.milkcocoa0902:colotok-coroutines:0.4.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.10.0")
    implementation("com.github.ajalt.clikt:clikt:5.1.0")

    implementation("io.ktor:ktor-server-core:3.5.0")
    implementation("io.ktor:ktor-server-cio:3.5.0")
    implementation("io.ktor:ktor-server-resources:3.5.0")
    implementation("io.ktor:ktor-server-content-negotiation:3.5.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.0")

    implementation("org.jetbrains.exposed:exposed-core:1.2.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:1.2.0")
    implementation("org.jetbrains.exposed:exposed-json:1.2.0")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:1.2.0")
    implementation("org.jetbrains.exposed:exposed-migration-core:1.2.0")
    implementation("org.jetbrains.exposed:exposed-migration-jdbc:1.2.0")
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:3.5.0")
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

tasks.shadowJar {
    archiveClassifier.set("all")
    manifest {
        attributes["Main-Class"] = "com.milkcocoa.info.sapphire.agent.MainKt"
    }
}
