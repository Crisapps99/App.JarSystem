import org.gradle.kotlin.dsl.implementation
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}
//repositories {
//    mavenCentral()
//}
android {
    namespace = "com.example.myapplication" +
            ""
    compileSdk = 36

    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
        }
    }
    defaultConfig {
        applicationId = "com.example.myapplication"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
        // Cargar API KEY desde local.properties
        val props = Properties()
        val localPropsFile = rootProject.file("local.properties")

        if (localPropsFile.exists()) {
            props.load(localPropsFile.inputStream())
        }

        val geminiKey = props.getProperty("GEMINI_API_KEY") ?: ""

        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiKey\"")

    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")  // necesita = y file()
            version = "3.22.1"
        }
    }
    buildFeatures{
        viewBinding = true
        buildConfig = true
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
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
//    implementation ("edu.cmu.pocketsphinx:pocketsphinx-android:5.0.0")
//    implementation("ai.picovoice:porcupine-android:4.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    //vosk
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    // También es recomendable tener esta para ViewModels si los usas:
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
//    //picovoice
//    implementation ("ai.picovoice:porcupine-android:4.0.0")
    implementation("com.google.code.gson:gson:2.10.1")
    //botn deslizale
    implementation("com.ncorti:slidetoact:0.9.0")
    //dependencia lottie
    implementation("com.airbnb.android:lottie:6.3.0")
    // En la sección dependencies { ... } de app/build.gradle.kts
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.google.code.gson:gson:2.10.1")
// Para llamadas HTTP/Red
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.ai.client.generativeai:generativeai:latest.release")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}