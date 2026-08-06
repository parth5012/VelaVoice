package com.velavoice.app

import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.Promise
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.text.TextUtils

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

    @ReactMethod
    fun openInputMethodSettings() {
        val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        reactApplicationContext.startActivity(intent)
    }

    @ReactMethod
    fun showInputMethodPicker() {
        val imm = reactApplicationContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showInputMethodPicker()
    }

    @ReactMethod
    fun isImeEnabled(promise: Promise) {
        val imm = reactApplicationContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabledMethods = imm.enabledInputMethodList
        val packageName = reactApplicationContext.packageName
        val isEnabled = enabledMethods.any { it.packageName == packageName }
        promise.resolve(isEnabled)
    }

    @ReactMethod
    fun isImeSelected(promise: Promise) {
        val currentInputMethodId = Settings.Secure.getString(
            reactApplicationContext.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        )
        val packageName = reactApplicationContext.packageName
        val isSelected = currentInputMethodId != null && currentInputMethodId.startsWith(packageName)
        promise.resolve(isSelected)
    }

    @ReactMethod
    fun getUseLlmCleaner(promise: Promise) {
        val prefs = reactApplicationContext.getSharedPreferences("com.velavoice.app_preferences", Context.MODE_PRIVATE)
        promise.resolve(prefs.getBoolean("useLlmCleaner", false))
    }

    @ReactMethod
    fun setUseLlmCleaner(value: Boolean, promise: Promise) {
        val prefs = reactApplicationContext.getSharedPreferences("com.velavoice.app_preferences", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("useLlmCleaner", value).apply()
        promise.resolve(true)
    }

    @ReactMethod
    fun getStringPreference(key: String, defaultValue: String, promise: Promise) {
        val prefs = reactApplicationContext.getSharedPreferences("com.velavoice.app_preferences", Context.MODE_PRIVATE)
        promise.resolve(prefs.getString(key, defaultValue))
    }

    @ReactMethod
    fun setStringPreference(key: String, value: String, promise: Promise) {
        val prefs = reactApplicationContext.getSharedPreferences("com.velavoice.app_preferences", Context.MODE_PRIVATE)
        prefs.edit().putString(key, value).apply()
        promise.resolve(true)
    }

    @ReactMethod
    fun isAccessibilityServiceEnabled(promise: Promise) {
        val context = reactApplicationContext
        val expectedService = "${context.packageName}/${context.packageName}.VoiceAccessibilityService"
        var enabled = false
        try {
            val settingValue = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (settingValue != null) {
                val splitter = TextUtils.SimpleStringSplitter(':')
                splitter.setString(settingValue)
                while (splitter.hasNext()) {
                    val accessService = splitter.next()
                    if (accessService.equals(expectedService, ignoreCase = true)) {
                        enabled = true
                        break
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        promise.resolve(enabled)
    }

    @ReactMethod
    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        reactApplicationContext.startActivity(intent)
    }

    /**
     * Batch load all preferences in a single native call.
     * Returns a JSON string containing all Vela settings at once.
     */
    @ReactMethod
    fun getAllPreferences(promise: Promise) {
        try {
            val prefs = reactApplicationContext.getSharedPreferences("com.velavoice.app_preferences", Context.MODE_PRIVATE)
            val json = org.json.JSONObject()
            json.put("useLlmCleaner", prefs.getBoolean("useLlmCleaner", false))
            json.put("transcriptionMode", prefs.getString("transcriptionMode", "local") ?: "local")
            json.put("groqApiKey", prefs.getString("groqApiKey", "") ?: "")
            json.put("groqModel", prefs.getString("groqModel", "whisper-large-v3") ?: "whisper-large-v3")
        json.put("openaiApiKey", prefs.getString("openaiApiKey", "") ?: "")
        json.put("openaiModel", prefs.getString("openaiModel", "whisper-1") ?: "whisper-1")
        json.put("openaiEndpoint", prefs.getString("openaiEndpoint", "https://api.openai.com/v1") ?: "https://api.openai.com/v1")
        json.put("customApiKey", prefs.getString("customApiKey", "") ?: "")
        json.put("customModel", prefs.getString("customModel", "whisper-1") ?: "whisper-1")
        json.put("customEndpoint", prefs.getString("customEndpoint", "") ?: "")
            promise.resolve(json.toString())
        } catch (e: Exception) {
            promise.reject("PREF_ERROR", e.message)
        }
    }
}
