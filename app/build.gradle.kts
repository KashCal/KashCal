import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("kotlin-parcelize")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kover)
}

// Load version from version.properties
val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}

// Load local.properties for signing config (optional)
val localProps = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "org.onekash.kashcal"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.onekash.kashcal"
        minSdk = 26
        targetSdk = 35
        versionCode = versionProps.getProperty("VERSION_CODE").toInt()
        versionName = versionProps.getProperty("VERSION_NAME")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Room schema export for migrations
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
            arg("room.incremental", "true")
            arg("room.generateKotlin", "true")
        }
    }

    signingConfigs {
        // Release signing config - only configure if credentials are in local.properties
        val keystorePath = localProps.getProperty("KEYSTORE_FILE")
        val keystorePassword = localProps.getProperty("KEYSTORE_PASSWORD")
        val keyAliasValue = localProps.getProperty("KEY_ALIAS")
        val keyPasswordValue = localProps.getProperty("KEY_PASSWORD")

        if (keystorePath != null && keystorePassword != null &&
            keyAliasValue != null && keyPasswordValue != null &&
            file(keystorePath).exists()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Use release signing if available (F-Droid builds unsigned)
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // ical4j brings duplicate service files
            excludes += "/META-INF/services/*"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                // Disable C2 JIT compiler - crashes on Robolectric's SQLite shadow bytecode
                // See: https://github.com/corretto/corretto-17/issues
                it.jvmArgs("-XX:TieredStopAtLevel=1")
            }
        }
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Material (XML themes for edge-to-edge)
    implementation(libs.google.material)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Immutable Collections (Compose recommended)
    implementation(libs.kotlinx.collections.immutable)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // JSON
    implementation(libs.gson)

    // RFC 5545 Recurrence
    implementation(libs.lib.recur)

    // iCal Parsing (RFC 5545) - ical4j with Android-compatible config
    implementation(libs.ical4j) {
        // Exclude modules that conflict with Android
        exclude(group = "javax.cache")
        exclude(group = "org.codehaus.groovy")
    }

    // HTTP Client
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // DataStore & Security
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // WorkManager
    implementation(libs.androidx.work.runtime)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Glance (App Widgets)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // Testing - Unit
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.arch.core.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)

    // Testing - Instrumented
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    // Debug
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// Kover code coverage configuration
kover {
    reports {
        // Configure filters for coverage
        filters {
            excludes {
                // Exclude generated code
                classes(
                    // Hilt generated
                    "*_HiltModules*",
                    "*_Factory*",
                    "*_MembersInjector*",
                    "Hilt_*",
                    "*_GeneratedInjector",
                    // Room generated
                    "*_Impl",
                    "*_Impl\$*",
                    // BuildConfig and R classes
                    "*.BuildConfig",
                    "*.R",
                    "*.R\$*",
                    // Compose generated
                    "*ComposableSingletons*",
                    // Data classes (usually just POJOs)
                    "*.entity.*",
                    "*.model.*"
                )
                // Exclude specific packages
                packages(
                    "hilt_aggregated_deps",
                    "dagger.hilt.internal.aggregatedroot.codegen"
                )
            }
        }
    }
}
