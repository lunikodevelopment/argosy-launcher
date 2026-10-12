package com.nendo.argosy.data.sftpserver

import org.apache.sshd.sftp.server.FileHandle
import org.apache.sshd.sftp.server.SftpFileSystemAccessor
import org.apache.sshd.sftp.server.SftpSubsystemProxy
import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessDeniedException
import java.nio.file.CopyOption
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission
import java.security.Principal

internal class ReadOnlySftpAccessor : SftpFileSystemAccessor {

    private val mutating = setOf(
        StandardOpenOption.WRITE,
        StandardOpenOption.APPEND,
        StandardOpenOption.CREATE,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.DELETE_ON_CLOSE
    )

    private fun deny(path: Path?): Nothing = throw AccessDeniedException(path?.toString(), null, "read-only")

    override fun openFile(
        subsystem: SftpSubsystemProxy,
        fileHandle: FileHandle?,
        file: Path,
        handle: String?,
        options: MutableSet<out OpenOption>,
        vararg attrs: FileAttribute<*>
    ): SeekableByteChannel {
        if (options.any { it in mutating }) deny(file)
        return super.openFile(subsystem, fileHandle, file, handle, options, *attrs)
    }

    override fun createDirectory(subsystem: SftpSubsystemProxy, path: Path) = deny(path)

    override fun createLink(subsystem: SftpSubsystemProxy, link: Path, existing: Path, symLink: Boolean) = deny(link)

    override fun renameFile(subsystem: SftpSubsystemProxy, oldPath: Path, newPath: Path, opts: MutableCollection<CopyOption>) =
        deny(oldPath)

    override fun copyFile(subsystem: SftpSubsystemProxy, src: Path, dst: Path, opts: MutableCollection<CopyOption>) = deny(dst)

    override fun removeFile(subsystem: SftpSubsystemProxy, path: Path, isDirectory: Boolean) = deny(path)

    override fun setFileAttribute(
        subsystem: SftpSubsystemProxy,
        file: Path,
        view: String?,
        attribute: String?,
        value: Any?,
        vararg options: LinkOption
    ) = deny(file)

    override fun setFilePermissions(
        subsystem: SftpSubsystemProxy,
        file: Path,
        perms: MutableSet<PosixFilePermission>,
        vararg options: LinkOption
    ) = deny(file)

    override fun setFileOwner(subsystem: SftpSubsystemProxy, file: Path, value: Principal, vararg options: LinkOption) = deny(file)

    override fun setGroupOwner(subsystem: SftpSubsystemProxy, file: Path, value: Principal, vararg options: LinkOption) = deny(file)

    override fun setFileAccessControl(
        subsystem: SftpSubsystemProxy,
        file: Path,
        acl: MutableList<AclEntry>,
        vararg options: LinkOption
    ) = deny(file)
}
