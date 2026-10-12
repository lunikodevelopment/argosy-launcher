package com.nendo.argosy.data.upload.security

import com.nendo.argosy.domain.model.upload.HostKeyFingerprint
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HostKeyStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val key = HostKeyFingerprint("ssh-ed25519", "SHA256:abc")

    @Test
    fun `an unconfirmed host has no trusted key`() {
        val store = HostKeyStore(File(temp.root, "hosts.json"), Moshi.Builder().build())
        assertNull(store.trusted("nas.local", 22))
    }

    @Test
    fun `a trusted key is scoped to host and port and survives a new instance`() {
        val file = File(temp.root, "hosts.json")
        HostKeyStore(file, Moshi.Builder().build()).trust("NAS.local", 22, key)
        val reopened = HostKeyStore(file, Moshi.Builder().build())
        assertEquals(key, reopened.trusted("nas.local", 22))
        assertNull(reopened.trusted("nas.local", 2222))
    }

    @Test
    fun `trusting a replacement key overwrites the old one and forget removes it`() {
        val store = HostKeyStore(File(temp.root, "hosts.json"), Moshi.Builder().build())
        store.trust("nas", 22, key)
        val rotated = HostKeyFingerprint("ssh-ed25519", "SHA256:new")
        store.trust("nas", 22, rotated)
        assertEquals(rotated, store.trusted("nas", 22))
        store.forget("nas", 22)
        assertNull(store.trusted("nas", 22))
    }
}
