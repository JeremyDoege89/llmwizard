plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    runtimeOnly(libs.slf4j.nop)
}

application {
    mainClass.set("dev.dreamz.llmwizard.cli.MainKt")
    applicationName = "llmwizard"
}

tasks.named<JavaExec>("run") {
    // Relative paths (targets.local.properties, fixtures/) resolve from the repo root,
    // and the terminal is wired through so `chat` can read lines.
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}
