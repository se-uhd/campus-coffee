import info.solidsoft.gradle.pitest.PitestPluginExtension

plugins {
    id("campuscoffee.java-conventions")
    id("campuscoffee.kotlin-conventions")
    id("campuscoffee.kotlin-kapt-conventions")
    id("campuscoffee.jacoco-conventions")
    id("campuscoffee.pitest-conventions")
}

dependencies {
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.tx)
    implementation(libs.commons.lang3)

    // Generate Spring configuration metadata for the @ConfigurationProperties classes (ApprovalConfiguration);
    // under Kotlin the processor runs via kapt, not the Java annotation processor.
    kapt(libs.spring.boot.configuration.processor)
}

configure<PitestPluginExtension> {
    targetClasses.set(listOf("de.seuhd.campuscoffee.domain.*"))
}
