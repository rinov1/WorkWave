package com.workwave.workwave.security

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PasswordHasher {
    private const val ITERATIONS = 120_000
    private const val KEY_LENGTH = 256 // bits

    fun generateSalt(): ByteArray =
        ByteArray(16).apply { SecureRandom().nextBytes(this) }

    fun hash(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH)
        val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return skf.generateSecret(spec).encoded
    }

    fun toB64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun fromB64(b64: String): ByteArray =
        Base64.decode(b64, Base64.NO_WRAP)

    fun verify(password: CharArray, saltB64: String, hashB64: String): Boolean {
        val salt = fromB64(saltB64)
        val expected = fromB64(hashB64)
        val actual = hash(password, salt)
        Arrays.fill(password, '\u0000')
        return MessageDigest.isEqual(expected, actual)
    }
}