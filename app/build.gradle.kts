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
    val properties = Properties()
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.exists()) {
        properties.load(propertiesFile.inputStream())
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
        val props = Properties()
        val localPropsFile = rootProject.file("local.properties")
        if (localPropsFile.exists()) {
            props.load(localPropsFile.inputStream())
        }
        val geminiKey = props.getProperty("GEMINI_API_KEY") ?: ""
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiKey\"")

        val youtubeKey = props.getProperty("YOUTUBE_API_KEY") ?: ""
        buildConfigField("String", "YOUTUBE_API_KEY", "\"$youtubeKey\"")

        val tavilyKey = props.getProperty("TAVILY_API_KEY") ?: ""
        buildConfigField("String", "TAVILY_API_KEY", "\"$tavilyKey\"")

        val elevenLabsKey = props.getProperty("ELEVENLABS_API_KEY") ?: ""
        buildConfigField("String", "ELEVENLABS_API_KEY", "\"$elevenLabsKey\"")

        val acrCloudAccessKey = props.getProperty("ACRCLOUD_ACCESS_KEY") ?: ""
        buildConfigField("String", "ACRCLOUD_ACCESS_KEY", "\"$acrCloudAccessKey\"")

        val acrCloudAccessSecret = props.getProperty("ACRCLOUD_ACCESS_SECRET") ?: ""
        buildConfigField("String", "ACRCLOUD_ACCESS_SECRET", "\"$acrCloudAccessSecret\"")

        val nexusBaseUrl = props.getProperty("NEXUS_BASE_URL") ?: "https://mausand2499--jarvoice-nexus-api-nexusserver-serve-dev.modal.run"
        buildConfigField("String", "NEXUS_BASE_URL", "\"$nexusBaseUrl\"")

        val nexusWsHost = props.getProperty("NEXUS_WS_HOST") ?: "mausand2499--jarvoice-nexus-api-fastapi-server-dev.modal.run"
        buildConfigField("String", "NEXUS_WS_HOST", "\"$nexusWsHost\"")

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