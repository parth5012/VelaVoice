package com.velavoice.app

import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.Promise
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

class ModelVerifierModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
    override fun getName(): String {
        return "ModelVerifier"
    }

    @ReactMethod
    fun verifySHA256(filePath: String, expectedHash: String, promise: Promise) {
        val file = File(filePath)
        if (!file.exists()) {
            promise.resolve(false)
            return
        }
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            val fis = FileInputStream(file)
            var bytesRead = fis.read(buffer)
            while (bytesRead != -1) {
                digest.update(buffer, 0, bytesRead)
                bytesRead = fis.read(buffer)
            }
            fis.close()
            
            val hashBytes = digest.digest()
            val sb = StringBuilder()
            for (b in hashBytes) {
                sb.append(String.format("%02x", b))
            }
            val computedHash = sb.toString()
            promise.resolve(computedHash.equals(expectedHash, ignoreCase = true))
        } catch (e: Exception) {
            promise.reject("HASH_ERROR", e.message)
        }
    }
}
