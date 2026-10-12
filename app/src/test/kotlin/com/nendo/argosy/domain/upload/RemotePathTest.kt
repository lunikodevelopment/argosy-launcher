package com.nendo.argosy.domain.upload

import com.nendo.argosy.domain.model.upload.TransferProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RemotePathTest {

    private fun assertRejected(reason: InvalidRemotePathException.Reason, block: () -> Unit) {
        try {
            block()
            fail("expected $reason")
        } catch (e: InvalidRemotePathException) {
            assertEquals(reason, e.reason)
        }
    }

    @Test
    fun `parent traversal is rejected in a relative path`() {
        assertRejected(InvalidRemotePathException.Reason.TRAVERSAL) {
            RemotePath.resolve("/srv/romm/library", "roms/../../etc/passwd", TransferProtocol.SFTP)
        }
    }

    @Test
    fun `an absolute relative path is rejected`() {
        assertRejected(InvalidRemotePathException.Reason.ABSOLUTE) {
            RemotePath.resolve("/srv/romm", "/etc/passwd", TransferProtocol.SFTP)
        }
    }

    @Test
    fun `a backslash path on smb cannot smuggle a separator`() {
        assertRejected(InvalidRemotePathException.Reason.RESERVED_CHARACTER) {
            RemotePath.resolve("library", "roms/gba/..\\..\\secret.gba", TransferProtocol.SMB)
        }
    }

    @Test
    fun `control characters are rejected`() {
        assertRejected(InvalidRemotePathException.Reason.CONTROL_CHARACTER) {
            RemotePath.validateSegment("game\u0000.gba", TransferProtocol.SFTP)
        }
    }

    @Test
    fun `smb reserved device names and trailing dots are rejected`() {
        assertRejected(InvalidRemotePathException.Reason.RESERVED_NAME) {
            RemotePath.validateSegment("CON.gba", TransferProtocol.SMB)
        }
        assertRejected(InvalidRemotePathException.Reason.TRAILING_DOT_OR_SPACE) {
            RemotePath.validateSegment("Game.", TransferProtocol.SMB)
        }
    }

    @Test
    fun `sftp keeps characters smb forbids`() {
        RemotePath.validateSegment("Zelda: Minish Cap.gba", TransferProtocol.SFTP)
    }

    @Test
    fun `roots are normalised per protocol`() {
        assertEquals("/srv/romm/library", RemotePath.normalizeRoot(" /srv//romm/library/ ", TransferProtocol.SFTP))
        assertEquals("romm/library", RemotePath.normalizeRoot("\\romm\\library\\", TransferProtocol.SMB))
        assertEquals("", RemotePath.normalizeRoot("", TransferProtocol.SMB))
        assertEquals("/", RemotePath.normalizeRoot("/", TransferProtocol.SFTP).ifEmpty { "/" })
    }

    @Test
    fun `resolved paths stay within the root`() {
        val path = RemotePath.resolve("/srv/romm/library", "roms/gba/Pokemon Emerald.gba", TransferProtocol.SFTP)
        assertEquals("/srv/romm/library/roms/gba/Pokemon Emerald.gba", path)
        assertTrue(RemotePath.isWithin("/srv/romm/library", path))
        assertFalse(RemotePath.isWithin("/srv/romm/library", "/srv/romm/library-other/x"))
        assertFalse(RemotePath.isWithin("/srv/romm/library", "/srv/romm/library/../x"))
        assertTrue(RemotePath.isWithin("", "roms/gba/a.gba"))
        assertFalse(RemotePath.isWithin("", "/etc/passwd"))
    }

    @Test
    fun `parent and name split on the last separator`() {
        assertEquals("/a/b", RemotePath.parent("/a/b/c.gba"))
        assertEquals("c.gba", RemotePath.name("/a/b/c.gba"))
        assertEquals("/", RemotePath.parent("/c.gba"))
        assertEquals("", RemotePath.parent("c.gba"))
    }
}
