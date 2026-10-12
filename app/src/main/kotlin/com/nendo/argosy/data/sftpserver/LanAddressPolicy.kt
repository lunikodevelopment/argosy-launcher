package com.nendo.argosy.data.sftpserver

import java.net.Inet6Address
import java.net.InetAddress

object LanAddressPolicy {

    fun isLan(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress) return true
        if (address is Inet6Address) {
            val first = address.address[0].toInt() and 0xFE
            return first == 0xFC
        }
        return false
    }
}
