package com.nendo.argosy.data.upload.transfer

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Installs the full BouncyCastle provider under the "BC" name sshj looks up. Android ships a reduced
 * provider with that name that lacks the curve and cipher suites sshj negotiates; it is replaced in
 * place, keeping its position in the provider list.
 */
object SshjSecurity {
    @Volatile
    private var installed = false

    @Synchronized
    fun install() {
        if (installed) return
        val existing = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        if (existing?.javaClass != BouncyCastleProvider::class.java) {
            val position = Security.getProviders().indexOfFirst { it.name == BouncyCastleProvider.PROVIDER_NAME }
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            if (position >= 0) {
                Security.insertProviderAt(BouncyCastleProvider(), position + 1)
            } else {
                Security.addProvider(BouncyCastleProvider())
            }
        }
        installed = true
    }
}
