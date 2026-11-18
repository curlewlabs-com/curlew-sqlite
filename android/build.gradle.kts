// Plugin versions for the standalone build. A consumer's own build supplies these when
// it includes the module.

plugins {
    id("com.android.library") version "9.5.0" apply false
    // AGP's built-in Kotlin compiles the module; the Kotlin Gradle plugin on the
    // classpath is what picks that compiler's version.
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
}
