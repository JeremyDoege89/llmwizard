plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "dev.dreamz.llmwizard"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.dreamz.llmwizard"
        // The assistant role API needs Android 10 (PLAN.md section 3).
        minSdk = 29
        // 36 is Play's floor for new apps. 37 adds the ACCESS_LOCAL_NETWORK runtime permission,
        // whose flow is a Phase 2 task; until then 36 keeps LAN connections implicit.
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // Spike B: the SSH stack has to survive R8 in a minified release build.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Throwaway spike builds use the debug key; the real upload key is Jay's (Phase 5).
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures { compose = true; buildConfig = true }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // ./gradlew :app:testDebugUnitTest -Ptarget=wiz-debian runs InstallFlowTest against that target.
            all { t ->
                project.findProperty("target")?.let { t.systemProperty("llmwizard.target", it.toString()) }
                t.testLogging { showStandardStreams = true }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST")
        }
    }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(project(":core"))
    implementation(libs.bcprov)
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.material.icons)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    // QR codes for the connect screen. Pure Java, no network code.
    implementation(libs.zxing.core)
    // The one-time Pro unlock (PLAN.md Phase 5). Behind the Entitlements interface.
    implementation(libs.billing.ktx)

    // Screenshot tests: screens rendered to PNG on the JVM (Robolectric + Roborazzi).
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
