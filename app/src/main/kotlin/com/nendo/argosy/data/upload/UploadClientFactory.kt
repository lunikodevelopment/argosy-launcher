package com.nendo.argosy.data.upload

import com.nendo.argosy.data.upload.security.HostKeyStore
import com.nendo.argosy.data.upload.security.UploadSecretKind
import com.nendo.argosy.data.upload.security.UploadSecretStore
import com.nendo.argosy.data.upload.transfer.SftpAuth
import com.nendo.argosy.data.upload.transfer.SftpEndpoint
import com.nendo.argosy.data.upload.transfer.SftpTransferClient
import com.nendo.argosy.data.upload.transfer.SmbEndpoint
import com.nendo.argosy.data.upload.transfer.SmbTransferClient
import com.nendo.argosy.data.upload.transfer.SshjSecurity
import com.nendo.argosy.domain.model.upload.SftpAuthMethod
import com.nendo.argosy.domain.model.upload.TransferProtocol
import com.nendo.argosy.domain.model.upload.UploadErrorKind
import com.nendo.argosy.domain.model.upload.UploadProfile
import com.nendo.argosy.domain.upload.RemoteTransferClient
import com.nendo.argosy.domain.upload.TransferException

/**
 * Builds a connected-on-demand client for a profile from its stored secrets and pinned host key.
 * A missing credential fails as AUTH_FAILED and an SFTP host without a confirmed key fails as
 * HOST_KEY_UNKNOWN, both before any byte is sent.
 */
class UploadClientFactory(
    private val profiles: () -> List<UploadProfile>,
    private val secrets: UploadSecretStore,
    private val hostKeys: HostKeyStore
) : TransferClientFactory {

    override fun create(profileId: String): RemoteTransferClient {
        val profile = profiles().firstOrNull { it.id == profileId }
            ?: throw TransferException(UploadErrorKind.DESTINATION_INVALID, "profile removed")
        return clientFor(profile, trustUnknownHostForProbe = false)
    }

    fun clientFor(profile: UploadProfile, trustUnknownHostForProbe: Boolean): RemoteTransferClient =
        when (profile.protocol) {
            TransferProtocol.SFTP -> sftp(profile, trustUnknownHostForProbe)
            TransferProtocol.SMB -> smb(profile)
        }

    private fun sftp(profile: UploadProfile, probe: Boolean): SftpTransferClient {
        SshjSecurity.install()
        val trusted = hostKeys.trusted(profile.host, profile.port)
        if (trusted == null && !probe) {
            throw TransferException(UploadErrorKind.HOST_KEY_UNKNOWN, profile.host)
        }
        val auth = when (profile.sftpAuthMethod) {
            SftpAuthMethod.PASSWORD -> SftpAuth.Password(require(profile, UploadSecretKind.PASSWORD))
            SftpAuthMethod.PRIVATE_KEY -> SftpAuth.PrivateKey(
                pem = String(require(profile, UploadSecretKind.PRIVATE_KEY)),
                passphrase = secrets.get(profile.id, UploadSecretKind.KEY_PASSPHRASE)
            )
        }
        return SftpTransferClient(SftpEndpoint(profile.host.trim(), profile.port, profile.username), auth, trusted)
    }

    private fun smb(profile: UploadProfile): SmbTransferClient = SmbTransferClient(
        SmbEndpoint(
            host = profile.host.trim(),
            port = profile.port,
            share = profile.smbShare.trim().trim('/', '\\'),
            username = profile.username,
            domain = profile.smbDomain.ifBlank { null },
            requireEncryption = profile.smbRequireEncryption
        ),
        require(profile, UploadSecretKind.PASSWORD)
    )

    private fun require(profile: UploadProfile, kind: UploadSecretKind): CharArray =
        secrets.get(profile.id, kind) ?: throw TransferException(UploadErrorKind.AUTH_FAILED, "credential missing")
}
