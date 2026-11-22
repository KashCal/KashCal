plugins {
    kotlin("jvm")
}

dependencies {
    // vCard parsing (RFC 2426 3.0 / RFC 6350 4.0). Keeps vinnie (the underlying
    // reader) and freemarker (class-loaded on the text/vcard read path), and
    // excludes the artifacts only the alternate serializations need: jsoup for
    // hCard and jackson for jCard. CardDAV only ever exchanges text/vcard, so
    // neither is reachable, and dropping them keeps the shipped footprint down.
    implementation(libs.ez.vcard) {
        exclude(group = "org.jsoup", module = "jsoup")
        exclude(group = "com.fasterxml.jackson.core")
    }

    implementation(libs.kotlinx.coroutines.core)

    // Testing (JUnit Jupiter 6)
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
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
