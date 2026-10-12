package com.nendo.argosy.data.sftpserver

import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.session.SessionListener
import org.apache.sshd.core.CoreModuleProperties
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration

class EmbeddedSftpServer(private val config: SftpServerConfig) {

    private var server: SshServer? = null

    val isRunning: Boolean get() = server?.isStarted == true

    @Synchronized
    fun start(): Int {
        server?.let { return it.port }
        config.rootDir.mkdirs()
        config.hostKeyFile.parentFile?.mkdirs()
        val expectedUser = config.username.toByteArray(StandardCharsets.UTF_8)
        val expectedPassword = String(config.password).toByteArray(StandardCharsets.UTF_8)
        val created = SshServer.setUpDefaultServer().apply {
            host = config.bindHost
            port = config.port
            keyPairProvider = SimpleGeneratorHostKeyProvider(config.hostKeyFile.toPath()).apply {
                setAlgorithm("EC")
                setKeySize(256)
            }
            passwordAuthenticator = PasswordAuthenticator { user, password, _ ->
                val userOk = MessageDigest.isEqual(user.toByteArray(StandardCharsets.UTF_8), expectedUser)
                val passwordOk = MessageDigest.isEqual(password.toByteArray(StandardCharsets.UTF_8), expectedPassword)
                userOk && passwordOk
            }
            publickeyAuthenticator = null
            subsystemFactories = listOf(buildSftpFactory())
            fileSystemFactory = VirtualFileSystemFactory(config.rootDir.toPath())
            shellFactory = null
            commandFactory = null
            addSessionListener(lanOnlyListener())
        }
        CoreModuleProperties.MAX_AUTH_REQUESTS.set(created, MAX_AUTH_ATTEMPTS)
        CoreModuleProperties.AUTH_TIMEOUT.set(created, Duration.ofSeconds(AUTH_TIMEOUT_SECONDS))
        CoreModuleProperties.IDLE_TIMEOUT.set(created, Duration.ofMinutes(IDLE_TIMEOUT_MINUTES))
        created.start()
        server = created
        return created.port
    }

    @Synchronized
    fun stop() {
        val running = server ?: return
        server = null
        running.stop(true)
    }

    private fun buildSftpFactory(): SftpSubsystemFactory {
        val builder = SftpSubsystemFactory.Builder()
        if (config.readOnly) builder.withFileSystemAccessor(ReadOnlySftpAccessor())
        return builder.build()
    }

    private fun lanOnlyListener() = object : SessionListener {
        override fun sessionCreated(session: Session) {
            val remote = session.remoteAddress as? InetSocketAddress
            val address = remote?.address
            if (address == null || !config.peerAllowed(address)) {
                session.close(true)
            }
        }
    }

    private companion object {
        const val MAX_AUTH_ATTEMPTS = 3
        const val AUTH_TIMEOUT_SECONDS = 30L
        const val IDLE_TIMEOUT_MINUTES = 15L
    }
}
