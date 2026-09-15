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
        versionCode = 6
        versionName = "5.0.0"
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
}
