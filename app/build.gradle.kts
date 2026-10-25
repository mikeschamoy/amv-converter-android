plugins { id("com.android.application") }

android {
    namespace = "com.mikeschamoy.amvconverter"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.mikeschamoy.amvconverter"
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 21
        versionName = "0.7.3"
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
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.9") }
