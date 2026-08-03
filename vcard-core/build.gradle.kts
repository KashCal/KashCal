plugins {
    kotlin("jvm")
}

dependencies {
    // vCard parsing (RFC 2426 3.0 / RFC 6350 4.0). Keeps vinnie (the underlying
    // reader) and freemarker (class-loaded on the text/vcard read path), and
    // excludes the artifacts only the alternate serializations need: jsoup for
    // hCard and jackson for jCard. CardDAV only ever exchanges text/vcard, so
    // neither is reachable, and dropping them keeps the shipped footprint down.
    implementation("com.googlecode.ez-vcard:ez-vcard:0.12.2") {
        exclude(group = "org.jsoup", module = "jsoup")
        exclude(group = "com.fasterxml.jackson.core")
    }

    // Kotlin coroutines (aligned with KashCal's version catalog)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Testing (JUnit 5)
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.14.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
