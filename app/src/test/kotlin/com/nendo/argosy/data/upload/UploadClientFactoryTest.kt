package com.nendo.argosy.data.upload

import com.nendo.argosy.data.upload.security.HostKeyStore
import com.nendo.argosy.data.upload.security.SecretCipher
import com.nendo.argosy.data.upload.security.UploadSecretKind
import com.nendo.argosy.data.upload.security.UploadSecretStore
import com.nendo.argosy.data.upload.transfer.SftpTransferClient
import com.nendo.argosy.data.upload.transfer.SmbTransferClient
import com.nendo.argosy.domain.model.upload.HostKeyFingerprint
import com.nendo.argosy.domain.model.upload.TransferProtocol
import com.nendo.argosy.domain.model.upload.UploadErrorKind
import com.nendo.argosy.domain.model.upload.UploadProfile
import com.nendo.argosy.domain.upload.TransferException
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UploadClientFactoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private object Identity : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(sealed: ByteArray) = sealed.reversedArray()
    }

    private val sftp = UploadProfile("p1", "NAS", TransferProtocol.SFTP, "nas.local", 22, "romm", "/srv/romm/library")
    private val smb = sftp.copy(id = "p2", protocol = TransferProtocol.SMB, port = 445, smbShare = "romm", libraryRoot = "library")

    private fun factory(): Triple<UploadClientFactory, UploadSecretStore, HostKeyStore> {
        val secrets = UploadSecretStore(File(temp.root, "s"), Identity)
        val hosts = HostKeyStore(File(temp.root, "h.json"), Moshi.Builder().build())
        return Triple(UploadClientFactory({ listOf(sftp, smb) }, secrets, hosts), secrets, hosts)
    }

    private fun expect(kind: UploadErrorKind, block: () -> Unit) {
        try {
            block()
            fail("expected $kind")
        } catch (e: TransferException) {
            assertEquals(kind, e.error.kind)
        }
    }

    @Test
    fun `an sftp host without a confirmed key is refused before connecting`() {
        val (factory, secrets, _) = factory()
        secrets.put("p1", UploadSecretKind.PASSWORD, "pw".toCharArray())
        expect(UploadErrorKind.HOST_KEY_UNKNOWN) { factory.create("p1") }
    }

    @Test
    fun `a missing password fails as an authentication problem`() {
        val (factory, _, hosts) = factory()
        hosts.trust("nas.local", 22, HostKeyFingerprint("ssh-ed25519", "SHA256:x"))
        expect(UploadErrorKind.AUTH_FAILED) { factory.create("p1") }
        expect(UploadErrorKind.AUTH_FAILED) { factory.create("p2") }
    }

    @Test
    fun `complete profiles produce protocol clients and a removed profile is invalid`() {
        val (factory, secrets, hosts) = factory()
        hosts.trust("nas.local", 22, HostKeyFingerprint("ssh-ed25519", "SHA256:x"))
        secrets.put("p1", UploadSecretKind.PASSWORD, "pw".toCharArray())
        secrets.put("p2", UploadSecretKind.PASSWORD, "pw".toCharArray())
        assertTrue(factory.create("p1") is SftpTransferClient)
        assertTrue(factory.create("p2") is SmbTransferClient)
        expect(UploadErrorKind.DESTINATION_INVALID) { factory.create("gone") }
    }
}
