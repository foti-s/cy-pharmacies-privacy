plugins {
    kotlin("jvm") version "2.1.0"
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Android's org.json, which the shared files from the app use.
    implementation("org.json:json:20240303")
}

application {
    mainClass.set("feed.MainKt")
}

tasks.named<JavaExec>("run") {
    // The repository root, where feed/ lives.
    args(rootProject.projectDir.parentFile.absolutePath)
    environment("MAPTILER_KEY", System.getenv("MAPTILER_KEY") ?: "")
}
