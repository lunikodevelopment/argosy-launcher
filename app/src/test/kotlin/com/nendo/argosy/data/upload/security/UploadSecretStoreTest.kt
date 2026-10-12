package com.nendo.argosy.data.upload.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class UploadSecretStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class SoftwareGcm : SecretCipher {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        override fun encrypt(plain: ByteArray): ByteArray {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            return iv + cipher.doFinal(plain)
        }

        override fun decrypt(sealed: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12))
            return cipher.doFinal(sealed, 12, sealed.size - 12)
        }
    }

    @Test
    fun `a stored password reads back and is not on disk in plain text`() {
        val dir = File(temp.root, "secrets")
        val store = UploadSecretStore(dir, SoftwareGcm())
        store.put("p1", UploadSecretKind.PASSWORD, "hunter2-ünïcode".toCharArray())
        assertArrayEquals("hunter2-ünïcode".toCharArray(), store.get("p1", UploadSecretKind.PASSWORD))
        val raw = dir.listFiles()!!.single().readBytes()
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("hunter2"))
    }

    @Test
    fun `secrets are separated by profile and kind and removed per profile`() {
        val store = UploadSecretStore(File(temp.root, "secrets"), SoftwareGcm())
        store.put("p1", UploadSecretKind.PASSWORD, "a".toCharArray())
        store.put("p1", UploadSecretKind.SCAN_TOKEN, "rmm_x".toCharArray())
        store.put("p2", UploadSecretKind.PASSWORD, "b".toCharArray())
        assertArrayEquals("b".toCharArray(), store.get("p2", UploadSecretKind.PASSWORD))
        store.removeProfile("p1")
        assertNull(store.get("p1", UploadSecretKind.PASSWORD))
        assertNull(store.get("p1", UploadSecretKind.SCAN_TOKEN))
        assertTrue(store.has("p2", UploadSecretKind.PASSWORD))
    }

    @Test
    fun `a value sealed under a lost key reads as absent`() {
        val dir = File(temp.root, "secrets")
        UploadSecretStore(dir, SoftwareGcm()).put("p1", UploadSecretKind.PASSWORD, "a".toCharArray())
        assertNull(UploadSecretStore(dir, SoftwareGcm()).get("p1", UploadSecretKind.PASSWORD))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a profile id that could escape the directory is refused`() {
        UploadSecretStore(File(temp.root, "secrets"), SoftwareGcm()).get("../x", UploadSecretKind.PASSWORD)
    }
}
