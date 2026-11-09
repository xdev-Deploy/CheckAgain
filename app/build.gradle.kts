import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// API 키는 저장소에 올라가지 않는 local.properties에서 읽어 BuildConfig로 앱에 넣는다.
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun localProp(name: String, default: String = ""): String =
    localProps.getProperty(name, default).trim().replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "dev.hjun.checkagain"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.hjun.checkagain"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "OPENAI_API_KEY", "\"${localProp("OPENAI_API_KEY")}\"")
        buildConfigField("String", "OPENAI_MODEL", "\"${localProp("OPENAI_MODEL", "gpt-4o-mini")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
}