plugins {
    id("com.android.application")
}

android {
    namespace = "com.pocket.storage"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pocket.storage"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
