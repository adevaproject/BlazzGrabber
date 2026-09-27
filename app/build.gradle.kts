plugins {
    id("com.android.application")
}

android {
    namespace = "com.blazzgrabber"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.blazzgrabber"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
}

tasks.register<Copy>("copyDebugApk") {
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(layout.buildDirectory.dir("outputs/named-apk"))
    rename { "blazz-grabber.apk" }
}

tasks.configureEach {
    if (name == "assembleDebug") {
        finalizedBy("copyDebugApk")
    }
}