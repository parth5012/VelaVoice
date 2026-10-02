import java.security.MessageDigest

plugins {
    `maven-publish`
}

val aarFile = file("$projectDir/../../sdk/vela-cleaner/libs/onnxruntime-genai-android-0.15.0.aar")
val expectedSha256 = "a4aeadcd4d70b877c56a74ece7778324a5ee4686f395ef29e4d2a83908b83a6c"

val verifyChecksum by tasks.registering {
    description = "Verifies SHA-256 checksum of onnxruntime-genai-android-0.15.0.aar before publishing"
    doLast {
        if (!aarFile.exists()) {
            throw GradleException(
                "AAR file not found: ${aarFile.absolutePath}\n" +
                "Please download it first or run scripts/bootstrap-onnx-aar.sh"
            )
        }
        val digest = MessageDigest.getInstance("SHA-256")
        aarFile.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead = input.read(buffer)
            while (bytesRead != -1) {
                digest.update(buffer, 0, bytesRead)
                bytesRead = input.read(buffer)
            }
        }
        val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
        if (actualSha256 != expectedSha256) {
            throw GradleException(
                "Security violation: SHA-256 checksum mismatch for ${aarFile.name}!\n" +
                "Expected: $expectedSha256\n" +
                "Actual:   $actualSha256"
            )
        }
        logger.lifecycle("SHA-256 checksum verified for ${aarFile.name}: $actualSha256")
    }
}

publishing {
    publications {
        create<MavenPublication>("genaiAar") {
            groupId = "com.microsoft.onnxruntime"
            artifactId = "onnxruntime-genai-android"
            version = "0.15.0"
            artifact(aarFile)
            pom {
                packaging = "aar"
            }
        }
    }
}

// Guard every repository publish path, not only the named task: a future
// remote-repository target would otherwise copy the artifact unverified.
tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(verifyChecksum)
}

// The publication task must wait for verification: publishToMavenLocal only lists
// verifyChecksum as a sibling, and Gradle does not guarantee sibling ordering.
tasks.named("publishGenaiAarPublicationToMavenLocal") {
    dependsOn(verifyChecksum)
}

tasks.named("publishToMavenLocal") {
    dependsOn(verifyChecksum)
}
