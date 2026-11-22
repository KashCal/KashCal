plugins {
    kotlin("jvm")
}

dependencies {
    // iCalendar parsing (RFC 5545)
    implementation(libs.ical4j)

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
