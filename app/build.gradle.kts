plugins {
    id("com.android.application")
}

android {
    namespace = "com.nemu.updateguard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nemu.windstop"
        minSdk = 30
        targetSdk = 36
        versionCode = 9
        versionName = "1.3.3"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly(files("libs/api-82.jar"))
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
