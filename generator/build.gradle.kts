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

// Daily health check (see .github/workflows/health.yml): ./gradlew health
// Fails (exit 1) when a check fails; the report is written to health-report.md either way.
tasks.register<JavaExec>("health") {
    mainClass.set("feed.HealthKt")
    classpath = sourceSets["main"].runtimeClasspath
    args(rootProject.projectDir.parentFile.absolutePath)
    environment("MAPTILER_KEY", System.getenv("MAPTILER_KEY") ?: "")
}

tasks.named<JavaExec>("run") {
    // The repository root, where feed/ lives.
    args(rootProject.projectDir.parentFile.absolutePath)
    environment("MAPTILER_KEY", System.getenv("MAPTILER_KEY") ?: "")
}
