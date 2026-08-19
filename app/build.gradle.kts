import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    id("kotlin-parcelize")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kover)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlin.serialization)
}

composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("stability_config.conf"))
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    metricsDestination = layout.buildDirectory.dir("compose_compiler")
    // Kotlin 2.3's compose compiler plugin attempts to resolve a
    // `compose-group-mapping` artifact whose version is derived at runtime
    // from getKotlinPluginVersion(project) — a stale value ends up being
    // "2.2.10" in our build environment, which isn't published. This mapping
    // file only improves release-build stack traces; disabling is safe.
    includeComposeMappingFile = false
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
    compileSdk = 37

    // Disable encrypted dependency metadata (only Google can read it)
    // Required for F-Droid/IzzyOnDroid transparency
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    defaultConfig {
        applicationId = "org.onekash.kashcal"
        minSdk = 31
        targetSdk = 37
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

    // Expose Room's exported schemas as a debug-only asset so
    // `MigrationTestHelper` can load `<dbClass>/<version>.json` at
    // runtime and validate identityHash equivalence after each
    // migration. The Robolectric unit test (`MigrationHashValidationTest`)
    // reads `mergeDebugAssets`, which AGP sources from the `main` and
    // `debug` build-type source sets but NOT from `test`. Wiring schemas
    // into `debug` keeps them out of release APKs.
    sourceSets {
        getByName("androidTest") {
            assets.srcDirs("$projectDir/schemas")
        }
        getByName("debug") {
            assets.srcDirs("$projectDir/schemas")
        }
    }

    signingConfigs {
        // Release signing config - check env vars (CI) first, then local.properties
        val keystorePath = System.getenv("KEYSTORE_FILE") ?: localProps.getProperty("KEYSTORE_FILE")
        val keystorePassword = System.getenv("KEYSTORE_PASSWORD") ?: localProps.getProperty("KEYSTORE_PASSWORD")
        val keyAliasValue = System.getenv("KEY_ALIAS") ?: localProps.getProperty("KEY_ALIAS")
        val keyPasswordValue = System.getenv("KEY_PASSWORD") ?: localProps.getProperty("KEY_PASSWORD")

        if (keystorePath != null && keystorePassword != null &&
            keyAliasValue != null && keyPasswordValue != null &&
            file(keystorePath).exists()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
                // minSdk 31 is well above v3's API-28 floor; keep v1/v2 on for
                // belt-and-suspenders. v3 is the prerequisite for future key rotation.
                enableV3Signing = true
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
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
            // Opt into Kotlin 2.4's default annotation target for constructor
            // params: annotations now apply to both the param and the backing
            // property/field, not just the param. Matches the intent for
            // @Inject, @Volatile, @Immutable etc. at ~36 sites in this project.
            // (Warning KT-73255.)
            freeCompilerArgs.add("-Xannotation-default-target=param-property")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // Auto-generate the LocaleConfig from the values-* resource folders and
        // wire it into the merged manifest, so all supported languages appear in
        // the Android 13+ system per-app language picker. The default
        // (unqualified) locale is declared in app/src/main/res/resources.properties.
        generateLocaleConfig = true
    }

    packaging {
        dex {
            useLegacyPackaging = true
        }
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
            // ical4j brings duplicate service files - use pickFirst instead of excluding
            // This preserves ServiceLoader functionality needed by ical4j 4.x
            pickFirsts += "META-INF/services/net.fortuna.ical4j.model.ComponentFactory"
            pickFirsts += "META-INF/services/net.fortuna.ical4j.model.PropertyFactory"
            pickFirsts += "META-INF/services/net.fortuna.ical4j.model.ParameterFactory"
            pickFirsts += "META-INF/services/net.fortuna.ical4j.validate.CalendarValidatorFactory"
            pickFirsts += "META-INF/services/java.time.zone.ZoneRulesProvider"
        }
    }

    lint {
        // Workaround: NonNullableMutableLiveDataDetector crashes with NoClassDefFoundError
        // in lifecycle-runtime-ktx 2.8.7 lint. This project doesn't use LiveData at all.
        disable += "NullSafeMutableLiveData"
        // Bare-label plurals (e.g., "Calendar"/"Calendars") intentionally omit %d
        disable += "ImpliedQuantity"
        // Strings with %d that could be plurals — most are always >1 or use
        // adjectives that don't inflect ("new", "updated", "more")
        disable += "PluralsCandidate"
        // Fail the build on missing content descriptions on interactive/image
        // elements. Compose coverage is limited (these checks mostly target
        // View/XML), but promoting them to error guards the widgets and any
        // future XML layouts against unlabeled controls.
        error += "ContentDescription"
        error += "ClickableViewAccessibility"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                // Disable C2 JIT compiler - crashes on Robolectric's SQLite shadow bytecode
                // See: https://github.com/corretto/corretto-17/issues
                it.jvmArgs("-XX:TieredStopAtLevel=1", "-XX:ReservedCodeCacheSize=512m")
                it.maxHeapSize = "1g"

                // Exclude integration tests (real servers) by default.
                // Run with: ./gradlew testDebugUnitTest -Pintegration
                if (!project.hasProperty("integration")) {
                    it.exclude("**/integration/**")
                    it.maxParallelForks =
                        (Runtime.getRuntime().availableProcessors() - 2).coerceAtLeast(1)
                } else {
                    it.maxParallelForks =
                        (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
                }
            }
        }
    }
}

// Disable release unit tests — identical to debug and doubles test time
tasks.matching { it.name == "testReleaseUnitTest" }.configureEach {
    enabled = false
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Biometric / device-credential auth for the optional app lock
    implementation(libs.androidx.biometric)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Material (XML themes for edge-to-edge)
    implementation(libs.google.material)

    // Room (room-ktx merged into room-runtime in 2.7.0)
    implementation(libs.androidx.room.runtime)
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
    implementation(libs.kotlinx.serialization.json)

    // iCal Parsing (RFC 5545) — in-tree subproject that wraps ical4j 4.3.0
    implementation(project(":icaldav-core"))

    // vCard Parsing (RFC 2426 / RFC 6350) — in-tree pure-JVM subproject that
    // quarantines the vCard library behind a compile boundary
    implementation(project(":vcard-core"))

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

    // MaterialKolor (seed -> M3 ColorScheme).
    // Exclude its Compose-Multiplatform transitives — the app already supplies the
    // androidx Compose equivalents, and MaterialKolor's CMP graph otherwise drags
    // androidx.compose.material3 up to an alpha (1.5.0-alpha08) past the pinned BOM.
    implementation(libs.materialkolor) {
        exclude(group = "org.jetbrains.compose.material3")
        exclude(group = "org.jetbrains.compose.runtime")
        exclude(group = "org.jetbrains.compose.ui")
        exclude(group = "org.jetbrains.compose.foundation")
        exclude(group = "org.jetbrains.compose.animation")
    }

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
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation("net.sf.kxml:kxml2:2.3.0")  // XmlPullParser for JVM tests
    testImplementation(libs.androidx.glance.testing)
    testImplementation(libs.androidx.glance.appwidget.testing)
    // lib-recur is retained as a test-only RRULE cross-engine oracle
    // (drives LibRecurParityEngine against ical4j in the parity harness)
    // after the production migration to icaldav-core in v23.6.20.
    testImplementation(libs.lib.recur)
    // Compose UI tests under Robolectric — used by ShareCardComposable layout
    // tests. Test-only; not shipped. Mirrors androidTest counterparts below.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation(libs.androidx.ui.test.manifest)

    // Testing - Instrumented
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.ui.test.junit4.accessibility)

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
