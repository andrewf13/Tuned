package com.andrewf13.tuned.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class BadPassphraseException : Exception("Wrong passphrase, or the backup file is damaged")

/**
 * Passphrase encryption for backup files: PBKDF2-HMAC-SHA256 (random salt) → AES-256-GCM.
 * GCM authenticates the data, so a wrong passphrase or a tampered file fails cleanly.
 * The file is a small JSON envelope, so it's recognisable and versioned.
 */
@OptIn(ExperimentalEncodingApi::class)
object BackupCrypto {
    private const val ITERATIONS = 150_000
    private const val FORMAT = "tuned-backup"

    fun encrypt(plain: ByteArray, passphrase: CharArray): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt, ITERATIONS), GCMParameterSpec(128, iv))
        cipher.updateAAD(FORMAT.toByteArray())
        val data = cipher.doFinal(plain)
        val env = JsonObject(
            mapOf(
                "format" to JsonPrimitive(FORMAT),
                "version" to JsonPrimitive(1),
                "kdf" to JsonPrimitive("PBKDF2WithHmacSHA256"),
                "iterations" to JsonPrimitive(ITERATIONS),
                "cipher" to JsonPrimitive("AES-256-GCM"),
                "salt" to JsonPrimitive(Base64.encode(salt)),
                "iv" to JsonPrimitive(Base64.encode(iv)),
                "data" to JsonPrimitive(Base64.encode(data)),
            ),
        )
        return env.toString().toByteArray()
    }

    fun decrypt(file: ByteArray, passphrase: CharArray): ByteArray {
        val env = runCatching { XtreamParse.json.parseToJsonElement(file.decodeToString()).jsonObject }.getOrNull()
        if (env == null || env["format"]?.jsonPrimitive?.content != FORMAT) {
            throw IllegalArgumentException("This isn't a Tuned backup file")
        }
        fun b64(k: String) = Base64.decode(env[k]!!.jsonPrimitive.content)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iterations = env["iterations"]!!.jsonPrimitive.int
        cipher.init(Cipher.DECRYPT_MODE, key(passphrase, b64("salt"), iterations), GCMParameterSpec(128, b64("iv")))
        cipher.updateAAD(FORMAT.toByteArray())
        return try {
            cipher.doFinal(b64("data"))
        } catch (e: AEADBadTagException) {
            throw BadPassphraseException()
        }
    }

    private fun key(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }
}
