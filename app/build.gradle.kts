plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/** The build number is the commit count, so every pushed commit installs over the last. */
val commitCount: Int = runCatching {
    ProcessBuilder("git", "rev-list", "--count", "HEAD").directory(rootDir).start()
        .inputStream.bufferedReader().readText().trim().toInt()
}.getOrDefault(1)

android {
    namespace = "io.github.alan_morford.prekeyboard"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.alan_morford.prekeyboard"
        minSdk = 21
        // The newest Android: no "built for an older version of Android" warning on install.
        targetSdk = 35
        versionCode = commitCount
        versionName = "1.0.1"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // The word list and the fonts are read straight out of the APK.
    androidResources { noCompress += listOf("ttf") }
    // Robolectric draws the real keyboard on the JVM, for screenshots and typing checks.
    testOptions { unitTests { isIncludeAndroidResources = true } }
    dependenciesInfo { includeInApk = false }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

tasks.withType<Test>().configureEach {
    systemProperty("robolectric.graphicsMode", "NATIVE")
    systemProperty("prekeyboard.shots", layout.buildDirectory.dir("shots").get().asFile.absolutePath)
}
