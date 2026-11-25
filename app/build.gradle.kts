plugins { id("com.android.application") }

android {
    namespace = "com.mikeschamoy.amvconverter"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.mikeschamoy.amvconverter"
        minSdk = 26
        if (providers.gradleProperty("clipamvArm32").orNull == "true") {
            ndk { abiFilters += listOf("armeabi-v7a") }
        }
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 28
        versionName = "0.7.10"
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
    implementation(files("libs/ffmpeg-kit-morph-full-16kb.aar"))
    implementation("com.arthenica:smart-exception-java:0.2.1") }
