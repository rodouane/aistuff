plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rod.hex"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rod.hex"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // Clé de signature fixe : chaque nouvelle version s'installe par-dessus l'ancienne
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module", "META-INF/DEPENDENCIES", "META-INF/LICENSE*",
                "META-INF/NOTICE*", "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }
}

dependencies {
    // Claude (SDK officiel Anthropic)
    implementation("com.anthropic:anthropic-java:3.6.0")
    // Mot d'activation "Hey Hex" (sur l'appareil)
    implementation("ai.picovoice:porcupine-android:4.0.2")
    // SDK Glyph de Nothing : téléchargé par la CI dans app/libs (utilisé par réflexion)
    val glyph = file("libs/glyph-sdk.aar")
    if (glyph.exists()) implementation(files(glyph))
}
