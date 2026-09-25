package com.pdfchemy.desktop.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.util.Locale

object DesktopStaging {
    val stagingDir: File by lazy {
        val path = Paths.get(System.getProperty("user.home"), ".pdfchemy", "staging")
        
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
            throw SecurityException("Security violation: ~/.pdfchemy/staging is a symbolic link.")
        }
        
        val isWindows = System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("win")
        if (isWindows) {
            Files.createDirectories(path)
        } else {
            val base = path.parent
            if (base != null && !Files.exists(base)) {
                Files.createDirectories(base)
            }
            if (!Files.exists(path)) {
                Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            } else {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
            }
        }
        path.toFile()
    }

    fun createTempFile(prefix: String, suffix: String): File {
        val f = File.createTempFile(prefix, suffix, stagingDir)
        f.deleteOnExit()
        return f
    }
    
    fun createTempDir(prefix: String): File {
        val dir = File(stagingDir, prefix + "_" + java.util.UUID.randomUUID().toString())
        dir.mkdirs()
        return dir
    }
}
