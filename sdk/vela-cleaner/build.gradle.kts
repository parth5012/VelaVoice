plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "com.velavoice.sdk.cleaner"
    compileSdk = 34

    defaultConfig {
        // onnxruntime-genai-android 0.15.0 requires minSdk 24 (its manifest enforces it)
        minSdk = 24
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // On-device LLM inference for Scribe / standard cleanup (Ticket 003).
    // onnxruntime-genai-android is NOT on Maven Central; it is published to
    // mavenLocal from the GitHub-release AAR (see README / commit message).
    // Its native libonnxruntime-genai.so dlopens libonnxruntime.so at runtime,
    // which is provided by onnxruntime-android (Maven Central).
    implementation(libs.onnxruntime.genai.android)
    implementation(libs.onnxruntime.android)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.velavoice.sdk"
                artifactId = "vela-cleaner"
                version = "1.0.0"
            }
        }
    }
}
