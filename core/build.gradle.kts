import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :core is pure Kotlin/JVM: no android.* or androidx.* imports. Everything about ink (compiling a
// story, playing it, what the editor needs to know of its source) lives here, so it is tested on
// the JVM. It runs on Android, so it only uses the parts of java.* that Android has.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // built with the CI's JDK 21 (the Robolectric tests of :app need it), held to the Java 17 API
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

dependencies {
    // Kept out of :core's own API: the app sees ink through the types of this module.
    implementation(libs.blade.ink)
    implementation(libs.blade.ink.compiler)
    implementation(libs.coroutines.core)
    // the project settings file (settings package), read with the JSON tree API
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

tasks.test {
    useJUnit()
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
