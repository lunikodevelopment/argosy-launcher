package com.nendo.argosy.ui.screens.settings

import com.nendo.argosy.data.preferences.DEFAULT_SFTP_SERVER_PORT
import com.nendo.argosy.data.sftpserver.SftpServerFailure

data class SftpServerUiState(
    val running: Boolean = false,
    val address: String? = null,
    val activePort: Int = 0,
    val password: String? = null,
    val failure: SftpServerFailure? = null,
    val port: Int = DEFAULT_SFTP_SERVER_PORT,
    val writable: Boolean = false
) {
    companion object {
        val PORT_OPTIONS = listOf(DEFAULT_SFTP_SERVER_PORT, 2022, 8022, 22222)
    }
}
