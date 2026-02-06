package com.anatdx.rei.core.auth

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 超级密钥存储：Keystore 加密，不可用时明文回退。 */
object ReiKeyHelper {
    private const val TAG = "ReiKeyHelper"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "ReiSuperKey"
    private const val ENCRYPT_MODE = "AES/GCM/NoPadding"
    private const val SUPER_KEY_ENC = "super_key_enc"
    private const val SUPER_KEY_IV = "super_key_iv"
    private const val SUPER_KEY_PLAIN = "super_key_plain"
    private const val SKIP_STORE_SUPER_KEY = "skip_store_super_key"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun setSharedPreferences(sp: SharedPreferences) {
        prefs = sp
    }

    fun setSharedPreferences(context: Context, name: String) {
        prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        ensureKeyExists()
    }

    private fun ensureKeyExists() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                generateSecretKey()
            }
        } catch (e: Exception) {
            Log.e(TAG, "ensureKeyExists", e)
        }
    }

    private fun generateSecretKey() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                val spec: AlgorithmParameterSpec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(false)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            }
        } catch (e: Exception) {
            Log.e(TAG, "generateSecretKey", e)
        }
    }

    private fun getRandomIV(): ByteArray {
        val p = prefs ?: return ByteArray(12).also { SecureRandom().nextBytes(it) }
        var ivB64 = p.getString(SUPER_KEY_IV, null)
        if (ivB64 == null) {
            val bytes = ByteArray(12)
            SecureRandom().nextBytes(bytes)
            ivB64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            p.edit().putString(SUPER_KEY_IV, ivB64).apply()
        }
        return Base64.decode(ivB64, Base64.NO_WRAP)
    }

    private fun encrypt(plain: String): String? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            val secretKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey ?: return null
            val cipher = Cipher.getInstance(ENCRYPT_MODE)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, getRandomIV()))
            Base64.encodeToString(cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "encrypt", e)
            null
        }
    }

    private fun decrypt(encrypted: String): String? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            val secretKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey ?: return null
            val cipher = Cipher.getInstance(ENCRYPT_MODE)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, getRandomIV()))
            String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "decrypt", e)
            null
        }
    }

    fun shouldSkipStoreSuperKey(): Boolean {
        return prefs?.getInt(SKIP_STORE_SUPER_KEY, 0) != 0
    }

    fun clearSuperKey() {
        prefs?.edit()?.apply {
            remove(SUPER_KEY_ENC)
            remove(SUPER_KEY_IV)
            remove(SUPER_KEY_PLAIN)
            apply()
        }
    }

    fun setShouldSkipStoreSuperKey(skip: Boolean) {
        clearSuperKey()
        prefs?.edit()?.putInt(SKIP_STORE_SUPER_KEY, if (skip) 1 else 0)?.apply()
    }

    fun readSuperKey(): String {
        val p = prefs ?: return ""
        val enc = p.getString(SUPER_KEY_ENC, "") ?: ""
        if (enc.isNotEmpty()) {
            decrypt(enc)?.let { return it }
        }
        return p.getString(SUPER_KEY_PLAIN, "") ?: ""
    }

    /** 持久化密钥；Keystore 可用则加密，否则明文。 */
    fun writeSuperKey(key: String): Boolean {
        if (shouldSkipStoreSuperKey()) return false
        val p = prefs ?: return false
        encrypt(key)?.let { enc ->
            p.edit()
                .putString(SUPER_KEY_ENC, enc)
                .remove(SUPER_KEY_PLAIN)
                .apply()
            return true
        }
        p.edit()
            .putString(SUPER_KEY_PLAIN, key)
            .remove(SUPER_KEY_ENC)
            .apply()
        return true
    }

    /** 8–63 位，且同时包含字母和数字 */
    fun isValidSuperKey(key: String): Boolean {
        return key.length in 8..63 &&
            key.any { it.isDigit() } &&
            key.any { it.isLetter() }
    }
}
