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
        versionCode = 5
        versionName = "4.1.0"
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
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.7")
}
