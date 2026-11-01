package com.lfmpixel.lfm.memory

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals what Wren learned before it is stored, and opens it again; [forget] makes everything sealed unreadable. */
interface Sealer {
    fun seal(plain: String): String
    /** The text, or null when it can't be opened (the key is gone). */
    fun open(sealed: String): String?
    fun forget()
}

/**
 * AES-GCM under a key that lives in Android's keystore (hardware-backed on the Pixel) and never leaves it, as EdgeKey
 * does for the server's key: the `learned` table is useless without this phone, and Forget deletes the key.
 */
object KeystoreSealer : Sealer {
    private const val ALIAS = "inkling-memory-key"
    private const val STORE = "AndroidKeyStore"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(STORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return gen.generateKey()
    }

    override fun seal(plain: String): String = GcmSealer.seal(key(), plain)
    override fun open(sealed: String): String? = runCatching { GcmSealer.open(key(), sealed) }.getOrNull()
    override fun forget() { runCatching { KeyStore.getInstance(STORE).apply { load(null) }.deleteEntry(ALIAS) } }
}

/** "iv:ciphertext" in Base64, AES-GCM with a 128-bit tag. */
object GcmSealer {
    fun seal(key: SecretKey, plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val out = c.doFinal(plain.toByteArray())
        return b64(c.iv) + ":" + b64(out)
    }

    fun open(key: SecretKey, sealed: String): String {
        val (iv, data) = sealed.split(':', limit = 2)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, unb64(iv))) }
        return String(c.doFinal(unb64(data)))
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = Base64.getDecoder().decode(s)
}
