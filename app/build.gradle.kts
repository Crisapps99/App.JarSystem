import java.util.Properties
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.protobuf") version "0.9.4"
    kotlin("kapt")
}

android {
    namespace = "com.example.myapplication"
    compileSdk = 36

    // ─── 1. CARGAR API KEYS DESDE apikeys.properties ───
    val apiKeysFile = rootProject.file("apikeys.properties")
    val apiProperties = Properties()
    if (apiKeysFile.exists()) {
        apiProperties.load(apiKeysFile.inputStream())
        println("apikeys.properties cargado correctamente.")
    } else {
        println(" apikeys.properties no encontrado. Usando valores por defecto (NO usar en producción).")
    }

    // Función helper para obtener propiedades con fallback seguro
    fun getApiKey(key: String, defaultValue: String = ""): String {
        return apiProperties.getProperty(key) ?: defaultValue
    }

    sourceSets {
        named("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    defaultConfig {
        applicationId = "com.example.myapplication"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        manifestPlaceholders.putAll(mapOf(
            "redirectSchemeName" to "nexus",
            "redirectHostName" to "auth"
        ))

        // ─── 2. BUILD CONFIG FIELDS (TODOS DESDE apikeys.properties) ───
        buildConfigField("String", "GEMINI_API_KEY", "\"${getApiKey("GEMINI_API_KEY")}\"")
        buildConfigField("String", "YOUTUBE_API_KEY", "\"${getApiKey("YOUTUBE_API_KEY", "AIzaSyBeZ8o6YTLkR_x4QJYXw9SAxiFL2xoL6zA")}\"")
        buildConfigField("String", "TAVILY_API_KEY", "\"${getApiKey("TAVILY_API_KEY", "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")}\"")
        buildConfigField("String", "ELEVENLABS_API_KEY", "\"${getApiKey("ELEVENLABS_API_KEY", "9dcae6842f3e53c4f885e4dcf30bf5635e8284c41df98d93f8b432b5f4383e90")}\"")
        buildConfigField("String", "ACRCLOUD_ACCESS_KEY", "\"${getApiKey("ACRCLOUD_ACCESS_KEY", "1d2662be97a95de33ae5a111a6a895ff")}\"")
        buildConfigField("String", "ACRCLOUD_ACCESS_SECRET", "\"${getApiKey("ACRCLOUD_ACCESS_SECRET", "BFf6SmDETpRrJck7Gw75HUAQTuT2bUhWF4rUkRjM")}\"")
        buildConfigField("String", "NEXUS_BASE_URL", "\"${getApiKey("NEXUS_BASE_URL", "https://mausand2499--jarvoice-nexus-api-nexusserver-serve-dev.modal.run")}\"")
        buildConfigField("String", "NEXUS_WS_HOST", "\"${getApiKey("NEXUS_WS_HOST", "mausand2499--jarvoice-nexus-api-fastapi-server-dev.modal.run")}\"")

        multiDexEnabled = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
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
        resources {
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/META-INF/ASL2.0"
        }
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
    // Room Database
    val roomVersion = "2.6.1" //
    implementation("androidx.room:room-runtime:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion") // Ya no dará error gracias al plugin kapt
    implementation("androidx.room:room-ktx:$roomVersion")


        // ... otras dependencias
    debugImplementation ("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.4.0")
    implementation("androidx.multidex:multidex:2.0.1")
    implementation("androidx.window:window:1.3.0")
    // gRPC
    implementation("io.grpc:grpc-android:1.60.0")
    implementation("io.grpc:grpc-stub:1.60.0")
    implementation("io.grpc:grpc-protobuf-lite:1.60.0")
    implementation("io.grpc:grpc-okhttp:1.60.0")

    implementation("com.google.protobuf:protobuf-java:3.25.5")

    implementation("com.google.api:gax-grpc:2.25.0") {
        exclude(group = "com.google.protobuf", module = "protobuf-java")
        exclude(group = "io.grpc", module = "grpc-netty-shaded")
    }
    implementation("com.google.cloud:google-cloud-speech:4.28.0") {
        exclude(group = "com.google.protobuf", module = "protobuf-java")
        exclude(group = "io.grpc", module = "grpc-netty-shaded")
    }

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.google.android.gms:play-services-location:21.0.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.05.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("com.airbnb.android:lottie-compose:6.3.0")

    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.ncorti:slidetoact:0.9.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
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

// ═══════════════════════════════════════════════════════════════════
// PROTOBUF - KOTLIN DSL SYNTAX (CORRECTO)
// ═══════════════════════════════════════════════════════════════════

protobuf {
    protoc {
        // Alineado con tu dependencia com.google.protobuf:protobuf-java:3.25.5
        artifact = "com.google.protobuf:protoc:3.25.5"
    }
    plugins {
        create("grpc") {
            // Alineado perfectamente con tus dependencias gRPC (1.60.0)
            artifact = "io.grpc:protoc-gen-grpc-java:1.60.0"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite") // Genera los mensajes en versión ligera para Android
                }
            }
            task.plugins {
                create("grpc") {
                    option("lite") // Genera los stubs de servicios gRPC en versión ligera
                }
            }
        }
    }
}

configurations.all {
    exclude(group = "com.google.protobuf", module = "protobuf-javalite")

    resolutionStrategy {
        force("io.grpc:grpc-core:1.60.0")
        force("io.grpc:grpc-api:1.60.0")
    }
}