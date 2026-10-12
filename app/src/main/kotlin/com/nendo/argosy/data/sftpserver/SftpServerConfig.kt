package com.nendo.argosy.data.sftpserver

import java.io.File
import java.net.InetAddress

data class SftpServerConfig(
    val port: Int,
    val rootDir: File,
    val username: String,
    val password: CharArray,
    val hostKeyFile: File,
    val readOnly: Boolean = true,
    val bindHost: String = "0.0.0.0",
    val peerAllowed: (InetAddress) -> Boolean = LanAddressPolicy::isLan
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
    override fun toString(): String = "SftpServerConfig(port=$port, readOnly=$readOnly)"
}
