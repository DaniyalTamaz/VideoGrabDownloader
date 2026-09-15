plugins {
    id("com.android.application")
}

android {
    namespace = "com.daniyal.videograb"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.daniyal.videograb"
        minSdk = 29
        targetSdk = 36
        versionCode = 8
        versionName = "5.1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.7")

    // 2.1.0-37 is the Android-tested line used by LibreTorrent's Android 15 release.
    // Avoid the newer master-tracking native build until the active Android crash reports settle.
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-37")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-37")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:2.1.0-37")
    implementation("org.libtorrent4j:libtorrent4j-android-x86:2.1.0-37")
    implementation("org.libtorrent4j:libtorrent4j-android-x86_64:2.1.0-37")
}
