// The CurlewSQLite Android library: the JNI shim and its Kotlin binding over sqlite.org's
// prebuilt engine, which this build fetches, verifies and packages unchanged.

import java.io.IOException
import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.library")
}

// sqlite.org publishes its Android build as a standalone AAR, not through a Maven
// repository, so the pin is a URL and a digest rather than a dependency coordinate. Move
// the two together, along with the version floor and source ID the guards hold.
val sqliteAndroidUrl = "https://www.sqlite.org/2026/sqlite-android-3540100.aar"
val sqliteAndroidSha256 = "45982177674e6fde0f5ec5c9387335a1a5c3e351ee986cbb8a42fc6d721660de"

/**
 * Downloads [url] to [output] and fails unless its SHA-256 is [sha256], so no byte the pin
 * does not name reaches the linker or the package. A cached file is re-verified rather
 * than trusted.
 */
abstract class FetchVerifiedFile : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun fetch() {
        val target = output.get().asFile
        if (target.isFile && digest(target.readBytes()) == sha256.get()) {
            logger.lifecycle("fetchSqliteAndroid: ${target.name} already verified")
            return
        }
        val bytes = download(url.get())
        val actual = digest(bytes)
        if (actual != sha256.get()) {
            throw GradleException(
                "fetchSqliteAndroid: ${url.get()} has SHA-256 $actual, pinned ${sha256.get()}",
            )
        }
        target.parentFile.mkdirs()
        target.writeBytes(bytes)
        logger.lifecycle("fetchSqliteAndroid: verified ${sha256.get()} for ${url.get()}")
    }

    // sqlite.org is one host behind a CDN, so a dropped connection or a server error is
    // worth a bounded retry. A wrong digest is never retried. The timeouts bound each
    // attempt, so a stalled transfer fails into the next one instead of hanging the build;
    // unset, both are infinite.
    private fun download(source: String): ByteArray {
        var lastFailure: IOException? = null
        for (attempt in 1..3) {
            try {
                val connection = URI(source).toURL().openConnection()
                connection.connectTimeout = 20_000
                connection.readTimeout = 60_000
                return connection.getInputStream().use { it.readBytes() }
            } catch (failure: IOException) {
                lastFailure = failure
                logger.warn("fetchSqliteAndroid: attempt $attempt of 3 failed for $source", failure)
                if (attempt < 3) Thread.sleep(2000L * attempt)
            }
        }
        throw GradleException("fetchSqliteAndroid: could not download $source", lastFailure)
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

val fetchSqliteAndroid =
    tasks.register<FetchVerifiedFile>("fetchSqliteAndroid") {
        url.set(sqliteAndroidUrl)
        sha256.set(sqliteAndroidSha256)
        output.set(layout.buildDirectory.file("sqlite-android/${sqliteAndroidUrl.substringAfterLast('/')}"))
    }

// Only the AAR's jni/<abi>/libsqliteX.so files are used. Its AOSP Java bindings are what
// this library replaces, so they are never put on the classpath.
val sqliteJniDir = layout.buildDirectory.dir("sqlite-jni")

val extractSqliteJniLibs =
    tasks.register<Sync>("extractSqliteJniLibs") {
        from(fetchSqliteAndroid.map { zipTree(it.output) }) {
            include("jni/**")
            eachFile { path = path.removePrefix("jni/") }
            includeEmptyDirs = false
        }
        into(sqliteJniDir)
    }

android {
    namespace = "com.curlewlabs.sqlite"
    compileSdk = 36
    // Pinned rather than left to AGP's default, so the compiler and linker behind the JNI
    // shim change only in a reviewed edit.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                arguments += listOf("-DSQLITE_JNI_DIR=${sqliteJniDir.get().asFile.absolutePath}")
            }
        }
    }

    // CMake resolves from the SDK's installed set; unpinned, it would change the build
    // tool behind the shim without a reviewed edit.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

// Packaged as the library's own JNI libraries. The directory is a fixed path the CMake
// configure also reads, so it is added as a static directory and its producing task is
// wired by name below.
androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(sqliteJniDir.get().asFile.absolutePath)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        allWarningsAsErrors.set(true)
    }
}

// AGP creates its CMake and JNI-merge tasks per variant and per ABI, and none of them is
// a documented dependency target, so this wires by name. Without it a clean build races:
// CMake's EXISTS check on libsqliteX.so runs before the AAR is unpacked and fails the
// configure, or the merge packages an empty directory.
tasks.configureEach {
    if (name.startsWith("configureCMake") ||
        name.startsWith("buildCMake") ||
        (name.startsWith("merge") && name.endsWith("JniLibFolders"))
    ) {
        dependsOn(extractSqliteJniLibs)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
