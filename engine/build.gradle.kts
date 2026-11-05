plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("dev.airtouch64.engine.MainKt")
}

dependencies {
    implementation("com.android.tools.build:apksig:8.9.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("transformSwf") {
    group = "application"
    description = "Apply the audited local SWF transformation"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.airtouch64.engine.SwfTransformMainKt")
    val input = providers.gradleProperty("swfInput")
    val output = providers.gradleProperty("swfOutput")
    doFirst {
        require(input.isPresent && output.isPresent) { "set -PswfInput and -PswfOutput" }
        args(input.get(), output.get())
    }
}
