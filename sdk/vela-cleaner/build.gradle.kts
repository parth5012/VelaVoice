import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "com.velavoice.sdk.cleaner"
    compileSdk = 35

    defaultConfig {
        // onnxruntime-genai-android 0.15.0 requires minSdk 24 (its manifest enforces it)
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
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
    implementation(project(":sdk:vela-core"))
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

val verifyGenAiAarChecksum by tasks.registering {
    description = "Verifies SHA-256 checksum of onnxruntime-genai-android dependency"
    doLast {
        val expectedSha256 = "a4aeadcd4d70b877c56a74ece7778324a5ee4686f395ef29e4d2a83908b83a6c"
        val m2Aar = File(System.getProperty("user.home"), ".m2/repository/com/microsoft/onnxruntime/onnxruntime-genai-android/0.15.0/onnxruntime-genai-android-0.15.0.aar")
        val localAar = file("libs/onnxruntime-genai-android-0.15.0.aar")

        if (!m2Aar.exists()) {
            throw GradleException(
                "onnxruntime-genai-android AAR not found in mavenLocal (~/.m2/repository).\n" +
                "Run ./scripts/bootstrap-onnx-aar.sh to download, verify, and publish it."
            )
        }

        val filesToVerify = mutableListOf(m2Aar)
        if (localAar.exists()) {
            filesToVerify.add(localAar)
        }

        fun computeSha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead = input.read(buffer)
                while (bytesRead != -1) {
                    digest.update(buffer, 0, bytesRead)
                    bytesRead = input.read(buffer)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        for (file in filesToVerify) {
            val actualSha256 = computeSha256(file)
            if (actualSha256 != expectedSha256) {
                throw GradleException(
                    "Security violation: SHA-256 mismatch for ${file.absolutePath}!\n" +
                    "Expected: $expectedSha256\n" +
                    "Actual:   $actualSha256"
                )
            }
            logger.lifecycle("Verified SHA-256 for ${file.name} (${file.parentFile.name}): $actualSha256")
        }
    }
}

tasks.named("preBuild") {
    dependsOn(verifyGenAiAarChecksum)
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
