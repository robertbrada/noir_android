plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    id("de.undercouch.download") version "5.6.0"
}

android {
    namespace = "com.noirandroid.lib"
    compileSdk = 34

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {

    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.8.0")
    implementation("com.google.code.gson:gson:2.8.9")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.madztheo"
                artifactId = "noir_android"
                version = "v1.0.0-beta.22-1"
            }
        }
    }
}

val rustLibName = "noir_java" // Adjust based on your library name
val rustLibPath = "src/main/java/$rustLibName" // Adjust based on your library name

// Find NDK toolchain bin directory to make cross-compilation tools available to cargo
val ndkToolchainBin: String by lazy {
    val sdkDir = android.sdkDirectory
    val ndkDir = file("$sdkDir/ndk").listFiles()
        ?.filter { it.isDirectory }
        ?.maxByOrNull { it.name }
        ?: error("No Android NDK found in $sdkDir/ndk")
    val hostTag = if (System.getProperty("os.name").lowercase().contains("mac")) "darwin-x86_64" else "linux-x86_64"
    "$ndkDir/toolchains/llvm/prebuilt/$hostTag/bin"
}

// Must match the barretenberg-rs version pinned through the noir_rs tag in
// noir_java/Cargo.toml, or the Rust and C++ sides disagree on the msgpack API.
val barretenbergVersion = "5.0.0"

// barretenberg links against standard LLVM libc++ (std::__1), not the NDK's __ndk1
// variant. Placing libc++.so next to the static library makes the binary record
// NEEDED:libc++.so, avoiding conflicts with libc++_shared.so in consuming apps.
tasks.register("fetchBarretenbergAndroidLibs") {
    doLast {
        // Fail early if the pin above drifts from what Cargo actually resolves.
        val cargoLock = file("$rustLibPath/Cargo.lock")
        if (!cargoLock.exists()) {
            exec {
                workingDir(file(rustLibPath))
                commandLine("cargo", "generate-lockfile")
            }
        }
        val lockedVersion = Regex("name = \"barretenberg-rs\"\\s+version = \"([^\"]+)\"")
            .find(cargoLock.readText())?.groupValues?.get(1)
            ?: error("barretenberg-rs not found in ${cargoLock.path}")
        if (lockedVersion != barretenbergVersion) {
            error(
                "barretenbergVersion is $barretenbergVersion but Cargo.lock resolves " +
                "barretenberg-rs $lockedVersion. Update barretenbergVersion in lib/build.gradle.kts."
            )
        }

        val bbAndroidDir = file("$rustLibPath/bb-android")
        val libcppUrl = "https://github.com/madztheo/noir_android/releases/download/v1.0.0-beta.22-1"
        mapOf(
            "arm64" to Pair("arm64-android", "libc++_arm64-v8a.so"),
            "x86_64" to Pair("x86_64-android", "libc++_x86_64.so"),
        ).forEach { (abiDir, bbArchAndLibcpp) ->
            val (bbArch, libcppAsset) = bbArchAndLibcpp
            val destDir = file("$bbAndroidDir/$abiDir")
            destDir.mkdirs()
            val tarFile = file("$destDir/barretenberg-static-$bbArch-v$barretenbergVersion.tar.gz")
            download.run {
                src("https://github.com/AztecProtocol/barretenberg/releases/download/v$barretenbergVersion/barretenberg-static-$bbArch.tar.gz")
                dest(tarFile)
                overwrite(false)
            }
            copy {
                from(tarTree(tarFile))
                into(destDir)
            }
            download.run {
                src("$libcppUrl/$libcppAsset")
                dest(file("$destDir/libc++.so"))
                overwrite(false)
            }
        }
    }
}

tasks.register("buildRust") {
    dependsOn("fetchBarretenbergAndroidLibs")
    doLast {
        val pathWithNdk = "$ndkToolchainBin:${System.getenv("PATH")}"
        val bbAndroidDir = file("$rustLibPath/bb-android")
        // Android arm64
        exec {
            workingDir(file(rustLibPath))
            environment("PATH", pathWithNdk)
            environment("BB_LIB_DIR", file("$bbAndroidDir/arm64").absolutePath)
            commandLine("cargo", "build", "--release", "--target", "aarch64-linux-android")
        }
        // Android x86_64
        exec {
            workingDir(file(rustLibPath))
            environment("PATH", pathWithNdk)
            environment("BB_LIB_DIR", file("$bbAndroidDir/x86_64").absolutePath)
            commandLine("cargo", "build", "--release", "--target", "x86_64-linux-android")
        }
    }
}

tasks.register("copyRustLibs") {
    if (System.getenv("BUILD_TYPE") == "MANUAL") {
        dependsOn("buildRust")
    }
    doLast {
        val buildType = System.getenv("BUILD_TYPE")
        if (buildType == "MANUAL") {
            // Copy the compiled library (.so file) to the appropriate JNI folder
            copy {
                from("$rustLibPath/target/aarch64-linux-android/release")
                include("lib${rustLibName}.so")
                into("src/main/jniLibs/arm64-v8a")
            }
            copy {
                from("$rustLibPath/target/x86_64-linux-android/release")
                include("lib${rustLibName}.so")
                into("src/main/jniLibs/x86_64")
            }
        } else {
            // Download the .so files from the GitHub release
            val releaseUrl = "https://github.com/madztheo/noir_android/releases/download/v1.0.0-beta.22-1"
            download.run {
                src("$releaseUrl/libnoir_java_arm64-v8a.so")
                dest("src/main/jniLibs/arm64-v8a/libnoir_java.so")
                overwrite(false)
            }
            download.run {
                src("$releaseUrl/libnoir_java_x86_64.so")
                dest("src/main/jniLibs/x86_64/libnoir_java.so")
                overwrite(false)
            }
        }
        // Copy or download libc++.so (standard LLVM libc++ with std::__1 namespace)
        // for each ABI. The pre-built barretenberg uses standard LLVM libc++ (not
        // the NDK's __ndk1 variant), so we ship it as libc++.so (matching its
        // SONAME) to avoid conflicts with the NDK's libc++_shared.so in consuming apps.
        if (buildType == "MANUAL") {
            val bbAndroidDir = file("$rustLibPath/bb-android")
            copy {
                from(file("$bbAndroidDir/arm64/libc++.so"))
                into("src/main/jniLibs/arm64-v8a")
            }
            copy {
                from(file("$bbAndroidDir/x86_64/libc++.so"))
                into("src/main/jniLibs/x86_64")
            }
        } else {
            val libcppUrl = "https://github.com/madztheo/noir_android/releases/download/v1.0.0-beta.22-1"
            download.run {
                src("$libcppUrl/libc++_arm64-v8a.so")
                dest("src/main/jniLibs/arm64-v8a/libc++.so")
                overwrite(false)
            }
            download.run {
                src("$libcppUrl/libc++_x86_64.so")
                dest("src/main/jniLibs/x86_64/libc++.so")
                overwrite(false)
            }
        }
    }
}

tasks.whenTaskAdded {
    if (name.matches(Regex("merge.*JniLibFolders"))) {
        dependsOn("copyRustLibs")
    }
}
