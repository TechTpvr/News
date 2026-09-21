android {
    namespace = "com.nabz.news"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nabz.news"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "1.9.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
