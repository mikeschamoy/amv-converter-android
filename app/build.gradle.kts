plugins { id("com.android.application") }

android {
    namespace = "com.mikeschamoy.amvconverter"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.mikeschamoy.amvconverter"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.6.1"
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
dependencies { implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.9") }
