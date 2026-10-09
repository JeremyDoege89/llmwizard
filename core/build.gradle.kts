plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    implementation(libs.sshj)
    implementation(libs.bcprov)
    implementation(libs.bcpkix)
    implementation(libs.okhttp)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.sshd.core)
    testImplementation(libs.mockwebserver)
    testRuntimeOnly(libs.slf4j.nop)
}

tasks.test {
    useJUnitPlatform()
    // Fixtures, recipes and the catalog live at the repo root, shared with the app.
    systemProperty("llmwizard.root", rootProject.projectDir.absolutePath)
}

// The catalog and recipes live at the repo root as plain data (SPEC.md layout) and ship
// inside :core as resources, so the CLI and the app read the same bundled copy.
val bundleData by tasks.registering(Sync::class) {
    from(rootProject.file("catalog")) { into("llmwizard/catalog") }
    from(rootProject.file("recipes")) { into("llmwizard/recipes") }
    into(layout.buildDirectory.dir("generated/bundle"))
}
sourceSets.main { resources.srcDir(bundleData) }
