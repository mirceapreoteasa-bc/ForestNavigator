plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}
kotlin { jvmToolchain(21) }

dependencies {
    implementation("org.json:json:20231013")
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // the real-data suite builds a network from several thousand real polylines
    maxHeapSize = "3g"
    testLogging { showStandardStreams = true }
}
