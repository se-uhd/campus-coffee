import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Kotlin configuration for the modules: the Java 25 toolchain and target, the Spring all-open plugin
// (so @Component/@Transactional classes can be proxied), and the Kotlin reflection library that
// Spring requires at runtime.
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        // retain parameter names for Spring's @PathVariable/@RequestParam binding, as -parameters does for Java
        javaParameters.set(true)
    }
}

dependencies {
    implementation(kotlin("reflect"))
}
