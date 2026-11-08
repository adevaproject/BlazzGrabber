import java.util.Properties

plugins {
    id("com.android.application")
}

val appConfigFile = rootProject.file("app-config.properties")
val appConfig = Properties().apply {
    appConfigFile.inputStream().use { input -> load(input) }
}

fun requiredConfig(key: String): String = appConfig.getProperty(key)
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: error("Missing or empty '$key' in app-config.properties")

fun optionalConfig(key: String): String = appConfig.getProperty(key)?.trim().orEmpty()

val configuredAppName = requiredConfig("app_name")
val configuredDownloadFolder = requiredConfig("download_folder")
val configuredApkFileName = requiredConfig("apk_file_name")
val iconSource = layout.projectDirectory.file(requiredConfig("app_icon"))
val downloadSoundSource = layout.projectDirectory.file(requiredConfig("notification_sound"))
val successSoundSource = layout.projectDirectory.file(requiredConfig("notification_sound_success"))
val errorSoundSource = layout.projectDirectory.file(requiredConfig("notification_sound_error"))
val configuredResources = layout.buildDirectory.dir("generated/configured-resources/main")

if (!iconSource.asFile.isFile) error("Configured app icon not found: ${iconSource.asFile}")
if (!downloadSoundSource.asFile.isFile) error("Configured download sound not found: ${downloadSoundSource.asFile}")
if (!successSoundSource.asFile.isFile) error("Configured success sound not found: ${successSoundSource.asFile}")
if (!errorSoundSource.asFile.isFile) error("Configured error sound not found: ${errorSoundSource.asFile}")

val copyConfiguredResources = tasks.register<Copy>("copyConfiguredResources") {
    from(iconSource) {
        into("drawable-nodpi")
        rename { "blazzgrabber_icon.png" }
    }
    from(downloadSoundSource) {
        into("raw")
        rename { "blazz_app_sfx.mp3" }
    }
    from(successSoundSource) {
        into("raw")
        rename { "blazz_app_sfx_success.mp3" }
    }
    from(errorSoundSource) {
        into("raw")
        rename { "blazz_app_sfx_error.mp3" }
    }
    into(configuredResources)
}

android {
    namespace = "com.blazzgrabber"
    compileSdk = 36

    buildFeatures {
        resValues = true
    }

    sourceSets.getByName("main").res.setSrcDirs(listOf(configuredResources))

    defaultConfig {
        applicationId = "com.blazzgrabber"
        minSdk = 23
        targetSdk = 35
        versionCode = 3
        versionName = "1.2.0"
        resValue("string", "app_name", configuredAppName)
        resValue("string", "download_folder", configuredDownloadFolder)
        resValue(
            "string",
            "notification_silent_channel_id",
            requiredConfig("notification_silent_channel_id")
        )
        resValue(
            "string",
            "notification_sound_channel_id",
            requiredConfig("notification_sound_channel_id")
        )
        resValue("string", "notification_silent_channel_name", "$configuredAppName status")
        resValue("string", "notification_sound_channel_name", "$configuredAppName alerts")
        resValue("string", "notification_success_channel_name", "$configuredAppName success")
        resValue("string", "notification_error_channel_name", "$configuredAppName errors")
        resValue("bool", "allow_http", requiredConfig("allow_http").toBooleanStrict().toString())
        resValue("string", "allowed_hosts", optionalConfig("allowed_hosts"))
    }
}

tasks.configureEach {
    if (name == "preBuild") {
        dependsOn(copyConfiguredResources)
    }
}

tasks.register<Copy>("copyDebugApk") {
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(layout.buildDirectory.dir("outputs/named-apk"))
    rename { configuredApkFileName }
}

tasks.configureEach {
    if (name == "assembleDebug") {
        finalizedBy("copyDebugApk")
    }
}