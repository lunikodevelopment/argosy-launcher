package com.nendo.argosy.data.upload.transfer

import com.nendo.argosy.domain.model.upload.DuplicatePolicy
import com.nendo.argosy.domain.model.upload.HostKeyFingerprint
import com.nendo.argosy.domain.model.upload.TransferProtocol
import com.nendo.argosy.domain.model.upload.UploadErrorKind
import com.nendo.argosy.domain.model.upload.UploadFileTask
import com.nendo.argosy.domain.model.upload.UploadItemState
import com.nendo.argosy.domain.upload.CancellationCheck
import com.nendo.argosy.domain.upload.ExecutionContext
import com.nendo.argosy.domain.upload.TransferException
import com.nendo.argosy.domain.upload.UploadExecutor
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.random.Random

class SftpTransferClientTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: SshServer
    private lateinit var root: File
    private lateinit var hostKey: KeyPair
    private lateinit var userKey: KeyPair
    private var port = 0

    @Before
    fun startServer() {
        root = temp.newFolder("library")
        File(root, "roms/gba").mkdirs()
        hostKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        userKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = KeyPairProvider.wrap(hostKey)
            passwordAuthenticator = PasswordAuthenticator { user, password, _ -> user == USER && password == PASSWORD }
            publickeyAuthenticator = PublickeyAuthenticator { user, key, _ -> user == USER && key == userKey.public }
            subsystemFactories = listOf(SftpSubsystemFactory())
            fileSystemFactory = VirtualFileSystemFactory(root.toPath())
        }
        server.start()
        port = server.port
    }

    @After
    fun stopServer() {
        server.stop(true)
    }

    private fun trusted(): HostKeyFingerprint = SshFingerprints.of(hostKey.public)

    private fun client(
        auth: SftpAuth = SftpAuth.Password(PASSWORD.toCharArray()),
        trustedKey: HostKeyFingerprint? = trusted()
    ) = SftpTransferClient(SftpEndpoint("127.0.0.1", port, USER), auth, trustedKey)

    private fun expectKind(kind: UploadErrorKind, block: () -> Unit) {
        try {
            block()
            fail("expected $kind")
        } catch (e: TransferException) {
            assertEquals(kind, e.error.kind)
        }
    }

    @Test
    fun `an unknown host key is refused and its fingerprint offered for confirmation`() {
        val client = client(trustedKey = null)
        expectKind(UploadErrorKind.HOST_KEY_UNKNOWN) { client.connect() }
        assertEquals(trusted(), client.presentedHostKey)
        client.close()
    }

    @Test
    fun `a changed host key is refused`() {
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val client = client(trustedKey = SshFingerprints.of(other.public))
        expectKind(UploadErrorKind.HOST_KEY_CHANGED) { client.connect() }
        client.close()
    }

    @Test
    fun `a wrong password is an authentication failure`() {
        val client = client(auth = SftpAuth.Password("nope".toCharArray()))
        expectKind(UploadErrorKind.AUTH_FAILED) { client.connect() }
        client.close()
    }

    @Test
    fun `a closed port is a connection failure`() {
        val closedPort = port
        server.stop(true)
        val client = SftpTransferClient(SftpEndpoint("127.0.0.1", closedPort, USER), SftpAuth.Password(PASSWORD.toCharArray()), trusted())
        expectKind(UploadErrorKind.CONNECTION_FAILED) { client.connect() }
    }

    @Test
    fun `private key authentication connects`() {
        val pem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(userKey.private.encoded) +
            "\n-----END PRIVATE KEY-----\n"
        client(auth = SftpAuth.PrivateKey(pem, null)).use { c ->
            c.connect()
            assertTrue(c.stat("roms/gba")!!.isDirectory)
        }
    }

    @Test
    fun `the full safe procedure uploads a rom through sftp`() {
        val bytes = Random(7).nextBytes(3 * 1024 * 1024 + 17)
        client().use { c ->
            c.connect()
            val executor = UploadExecutor(c, { ByteArrayInputStream(bytes) })
            val task = UploadFileTask("f", "/local/Emerald.gba", "Emerald.gba", "roms/gba/Emerald.gba", bytes.size.toLong())
            var lastProgress = 0L
            val outcome = executor.execute(task, context(), { lastProgress = it }, CancellationCheck.NEVER)
            assertEquals(UploadItemState.COMPLETED, outcome.state)
            assertEquals(bytes.size.toLong(), lastProgress)
        }
        assertArrayEquals(bytes, File(root, "roms/gba/Emerald.gba").readBytes())
        assertTrue(File(root, "roms/gba").listFiles()!!.none { it.name.endsWith(UploadExecutor.TEMP_EXTENSION) })
    }

    @Test
    fun `an existing remote rom is skipped and left byte identical`() {
        File(root, "roms/gba/Emerald.gba").writeText("original")
        client().use { c ->
            c.connect()
            val executor = UploadExecutor(c, { ByteArrayInputStream("new".toByteArray()) })
            val task = UploadFileTask("f", "/local/Emerald.gba", "Emerald.gba", "roms/gba/Emerald.gba", 3)
            assertEquals(UploadItemState.SKIPPED, executor.execute(task, context(), {}, CancellationCheck.NEVER).state)
        }
        assertEquals("original", File(root, "roms/gba/Emerald.gba").readText())
    }

    @Test
    fun `a source read failure mid transfer leaves no final file and no temp file`() {
        val failing = object : InputStream() {
            var count = 0
            override fun read(): Int = if (count++ < 100_000) 1 else throw IOException("sd card removed")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (count > 100_000) throw IOException("sd card removed")
                val n = minOf(len, 4096)
                count += n
                return n
            }
        }
        client().use { c ->
            c.connect()
            val executor = UploadExecutor(c, { failing })
            val task = UploadFileTask("f", "/local/Big.gba", "Big.gba", "roms/gba/Big.gba", 10_000_000)
            val outcome = executor.execute(task, context(), {}, CancellationCheck.NEVER)
            assertEquals(UploadItemState.FAILED, outcome.state)
        }
        assertFalse(File(root, "roms/gba/Big.gba").exists())
        assertTrue(File(root, "roms/gba").listFiles()!!.none { it.name.endsWith(UploadExecutor.TEMP_EXTENSION) })
    }

    @Test
    fun `a server disconnect mid transfer never produces the final file`() {
        val size = 32L * 1024 * 1024
        val stream = object : InputStream() {
            var sent = 0L
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (sent >= size) return -1
                if (sent > 2 * 1024 * 1024) server.stop(true)
                val n = minOf(len.toLong(), size - sent).toInt()
                sent += n
                return n
            }
        }
        val c = client()
        c.connect()
        val executor = UploadExecutor(c, { stream })
        val task = UploadFileTask("f", "/local/Big.iso", "Big.iso", "roms/gba/Big.iso", size)
        val outcome = executor.execute(task, context(), {}, CancellationCheck.NEVER)
        c.close()
        assertEquals(UploadItemState.FAILED, outcome.state)
        assertTrue(outcome.error!!.error.kind.retryable)
        assertFalse(File(root, "roms/gba/Big.iso").exists())
    }

    @Test
    fun `large files stream without being held in memory`() {
        val size = 96L * 1024 * 1024
        val stream = object : InputStream() {
            var sent = 0L
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (sent >= size) return -1
                val n = minOf(len.toLong(), size - sent).toInt()
                sent += n
                return n
            }
        }
        var maxReadAhead = 0L
        var callbacks = 0
        client().use { c ->
            c.connect()
            val executor = UploadExecutor(c, { stream })
            val task = UploadFileTask("f", "/local/Huge.iso", "Huge.iso", "roms/gba/Huge.iso", size)
            val outcome = executor.execute(task, context(), { totalSent ->
                callbacks++
                maxReadAhead = maxOf(maxReadAhead, stream.sent - totalSent)
            }, CancellationCheck.NEVER)
            assertEquals(UploadItemState.COMPLETED, outcome.state)
        }
        assertEquals(size, File(root, "roms/gba/Huge.iso").length())
        assertTrue("only $callbacks progress callbacks", callbacks > 1)
        assertTrue("read ${maxReadAhead / 1024 / 1024} MB ahead of the wire", maxReadAhead < MAX_READ_AHEAD)
    }

    @Test
    fun `listing returns names and sizes`() {
        File(root, "roms/gba/A.gba").writeBytes(ByteArray(10))
        client().use { c ->
            c.connect()
            val entries = c.list("roms/gba")
            val a = entries.firstOrNull { it.name == "A.gba" }
            assertNotNull(a)
            assertEquals(10L, a!!.size)
        }
    }

    private fun context() = ExecutionContext(
        root = "",
        protocol = TransferProtocol.SFTP,
        policy = DuplicatePolicy.SKIP,
        overwriteConfirmed = false,
        allowCreateDirectories = false,
        platformDirectory = "roms/gba",
        tempSuffixSeed = "test1234abcd"
    )

    companion object {
        private const val USER = "romm"
        private const val PASSWORD = "secret"
        private const val MAX_READ_AHEAD = 16L * 1024 * 1024
    }
}
