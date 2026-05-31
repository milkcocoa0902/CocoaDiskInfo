import org.gradle.kotlin.dsl.kotlin



plugins {
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.jetbrainsCompose) apply false
    alias(libs.plugins.composeCompiler) apply false
    id("com.gradleup.shadow") version "9.4.1" apply false
}

group = "com.milkcocoa.info.saphire"
version = "1.0-SNAPSHOT"

dependencies {
}
