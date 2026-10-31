package com.lfmpixel.lfm.edge

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals the server's API key with AES-GCM under a key that lives in Android's keystore (hardware-backed on the
 * Pixel) and never leaves it: what Settings stores is useless without this phone. Delete everything drops the
 * keystore key too.
 */
object EdgeKey {
    private const val ALIAS = "inkling-edge-key"
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

    /** "iv:ciphertext" in Base64; empty stays empty. */
    fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = c.doFinal(plain.toByteArray())
        return b64(c.iv) + ":" + b64(out)
    }

    /** The key, or "" if there is none or it can't be opened (the keystore key was lost: enter it again). */
    fun open(sealed: String): String {
        if (sealed.isEmpty()) return ""
        return runCatching {
            val (iv, data) = sealed.split(':', limit = 2)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, unb64(iv))) }
            String(c.doFinal(unb64(data)))
        }.getOrDefault("")
    }

    fun forget() { runCatching { KeyStore.getInstance(STORE).apply { load(null) }.deleteEntry(ALIAS) } }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
