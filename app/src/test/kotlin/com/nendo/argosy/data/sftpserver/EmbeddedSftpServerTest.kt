package com.nendo.argosy.data.sftpserver

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.UserAuthException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress

class EmbeddedSftpServerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private var server: EmbeddedSftpServer? = null

    @After
    fun stop() {
        server?.stop()
    }

    private fun start(
        readOnly: Boolean = true,
        peerAllowed: (InetAddress) -> Boolean = LanAddressPolicy::isLan
    ): Pair<Int, File> {
        val root = temp.newFolder("share")
        File(root, "roms").mkdirs()
        File(root, "roms/game.gba").writeBytes(ByteArray(2048) { it.toByte() })
        File(temp.root, "secret.txt").writeText("outside")
        val config = SftpServerConfig(
            port = 0,
            rootDir = root,
            username = "argosy",
            password = "correct-horse".toCharArray(),
            hostKeyFile = File(temp.newFolder("keys"), "host.key"),
            readOnly = readOnly,
            bindHost = "127.0.0.1",
            peerAllowed = peerAllowed
        )
        val created = EmbeddedSftpServer(config)
        server = created
        return created.start() to root
    }

    private fun connect(port: Int, password: String = "correct-horse", user: String = "argosy"): SSHClient {
        val ssh = SSHClient()
        ssh.addHostKeyVerifier(PromiscuousVerifier())
        ssh.connect("127.0.0.1", port)
        ssh.authPassword(user, password)
        return ssh
    }

    private fun <T> withSftp(port: Int, block: (SFTPClient) -> T): T {
        connect(port).use { ssh -> ssh.newSFTPClient().use { return block(it) } }
    }

    @Test
    fun `correct credentials list and download`() {
        val (port, root) = start()
        val local = File(temp.root, "pulled.gba")
        withSftp(port) { sftp ->
            assertTrue(sftp.ls("/roms").any { it.name == "game.gba" })
            sftp.get("/roms/game.gba", local.absolutePath)
        }
        assertArrayEquals(File(root, "roms/game.gba").readBytes(), local.readBytes())
    }

    @Test
    fun `wrong password is rejected`() {
        val (port, _) = start()
        try {
            connect(port, password = "nope").close()
            fail("expected auth failure")
        } catch (_: UserAuthException) {
        }
    }

    @Test
    fun `wrong username is rejected`() {
        val (port, _) = start()
        try {
            connect(port, user = "root").close()
            fail("expected auth failure")
        } catch (_: UserAuthException) {
        }
    }

    @Test
    fun `read only mode refuses upload delete mkdir and rename`() {
        val (port, root) = start(readOnly = true)
        val source = File(temp.root, "up.bin").apply { writeBytes(ByteArray(64)) }
        withSftp(port) { sftp ->
            assertFailsIo { sftp.put(source.absolutePath, "/roms/up.bin") }
            assertFailsIo { sftp.rm("/roms/game.gba") }
            assertFailsIo { sftp.mkdir("/roms/new") }
            assertFailsIo { sftp.rename("/roms/game.gba", "/roms/moved.gba") }
        }
        assertFalse(File(root, "roms/up.bin").exists())
        assertTrue(File(root, "roms/game.gba").exists())
        assertFalse(File(root, "roms/new").exists())
    }

    @Test
    fun `writable mode accepts upload`() {
        val (port, root) = start(readOnly = false)
        val payload = ByteArray(4096) { (it * 7).toByte() }
        val source = File(temp.root, "up.bin").apply { writeBytes(payload) }
        withSftp(port) { it.put(source.absolutePath, "/roms/up.bin") }
        assertArrayEquals(payload, File(root, "roms/up.bin").readBytes())
    }

    @Test
    fun `path traversal cannot leave the share root`() {
        val (port, _) = start(readOnly = false)
        val local = File(temp.root, "stolen.txt")
        withSftp(port) { sftp ->
            assertFailsIo { sftp.get("/../secret.txt", local.absolutePath) }
            assertFailsIo { sftp.get("/roms/../../secret.txt", local.absolutePath) }
        }
        assertFalse(local.exists() && local.readText() == "outside")
    }

    @Test
    fun `peer outside the LAN policy is dropped before login`() {
        val (port, _) = start(peerAllowed = { false })
        try {
            connect(port).close()
            fail("expected the connection to be refused")
        } catch (_: java.io.IOException) {
        }
    }

    @Test
    fun `too many failed attempts disconnects the client`() {
        val (port, _) = start()
        val ssh = SSHClient()
        ssh.addHostKeyVerifier(PromiscuousVerifier())
        ssh.connect("127.0.0.1", port)
        var failures = 0
        repeat(6) {
            try {
                ssh.authPassword("argosy", "bad-$it")
            } catch (_: Exception) {
                failures++
            }
        }
        assertEquals(6, failures)
        assertFalse(ssh.isAuthenticated)
        runCatching { ssh.disconnect() }
    }

    @Test
    fun `stop closes the listener`() {
        val (port, _) = start()
        server!!.stop()
        assertFalse(server!!.isRunning)
        try {
            connect(port).close()
            fail("expected connection failure")
        } catch (_: java.io.IOException) {
        }
    }

    @Test
    fun `lan policy accepts private ranges and rejects public`() {
        assertTrue(LanAddressPolicy.isLan(InetAddress.getByName("192.168.1.20")))
        assertTrue(LanAddressPolicy.isLan(InetAddress.getByName("10.0.0.5")))
        assertTrue(LanAddressPolicy.isLan(InetAddress.getByName("172.16.4.4")))
        assertTrue(LanAddressPolicy.isLan(InetAddress.getByName("127.0.0.1")))
        assertTrue(LanAddressPolicy.isLan(InetAddress.getByName("fd12:3456:789a::1")))
        assertTrue(LanAddressPolicy.isLan(InetAddress.getByName("fe80::1")))
        assertFalse(LanAddressPolicy.isLan(InetAddress.getByName("8.8.8.8")))
        assertFalse(LanAddressPolicy.isLan(InetAddress.getByName("100.64.1.1")))
        assertFalse(LanAddressPolicy.isLan(InetAddress.getByName("2606:4700::1111")))
    }

    private fun assertFailsIo(block: () -> Unit) {
        try {
            block()
            fail("expected the operation to fail")
        } catch (_: java.io.IOException) {
        }
    }
}
