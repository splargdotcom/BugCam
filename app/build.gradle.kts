plugins { id("com.android.application") }

// AGP 9 supplies Kotlin support; do not apply org.jetbrains.kotlin.android.
android {
    namespace = "com.splarg.bugcam"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "com.splarg.bugcam"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    lint { abortOnError = true }
}

dependencies { testImplementation("junit:junit:4.13.2") }
