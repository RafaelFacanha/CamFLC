package com.camflc.app

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AlbumInfo(
    val name: String,
    val relativePath: String,
    val fullPath: String,
    val fileCount: Int,
    val subFolderCount: Int,
    val sizeBytes: Long
) {
    fun formattedSize(): String {
        val kb = sizeBytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> String.format(Locale.US, "%.2f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.0f KB", kb)
            else -> "$sizeBytes B"
        }
    }
}

object StorageEngine {

    // Resolve o diretorio base sem criar pastas acidentais durante digitacao
    fun resolveRootDir(baseRelativePath: String, createIfMissing: Boolean = true): File {
        val rootStorage = Environment.getExternalStorageDirectory()
        val cleaned = sanitizeRelativePath(baseRelativePath).ifEmpty { "DCIM/CamFLC" }
        val target = File(rootStorage, cleaned)
        if (createIfMissing && !target.exists()) {
            target.mkdirs()
        }
        return target
    }

    // Garante as pastas padrao apenas na raiz inicial caso esteja totalmente vazia
    fun ensureDefaultAlbumsIfEmpty(baseRelativePath: String, defaultNames: List<String>) {
        val root = resolveRootDir(baseRelativePath, createIfMissing = true)
        val existingDirs = root.listFiles { f -> f.isDirectory }.orEmpty()
        if (existingDirs.isEmpty()) {
            defaultNames.forEach { name ->
                File(root, sanitizeSingleName(name)).mkdirs()
            }
        }
    }

    // Lista todas as pastas e subpastas recursivamente para troca em 1 toque
    fun listAllAlbumsRecursively(baseRelativePath: String): List<AlbumInfo> {
        val root = resolveRootDir(baseRelativePath, createIfMissing = true)
        val result = mutableListOf<AlbumInfo>()

        fun scanDir(dir: File, currentRelative: String) {
            val childrenDirs = dir.listFiles { f -> f.isDirectory }
                ?.sortedBy { it.name.lowercase(Locale.getDefault()) }
                .orEmpty()

            for (folder in childrenDirs) {
                val relPath = if (currentRelative.isEmpty()) folder.name else "$currentRelative/${folder.name}"
                val mediaFiles = folder.listFiles { f ->
                    f.isFile && (f.name.endsWith(".jpg", true) ||
                            f.name.endsWith(".jpeg", true) ||
                            f.name.endsWith(".png", true) ||
                            f.name.endsWith(".mp4", true))
                }.orEmpty()
                val subDirs = folder.listFiles { f -> f.isDirectory }.orEmpty()

                result.add(
                    AlbumInfo(
                        name = folder.name,
                        relativePath = relPath,
                        fullPath = folder.absolutePath,
                        fileCount = mediaFiles.size,
                        subFolderCount = subDirs.size,
                        sizeBytes = mediaFiles.sumOf { it.length() }
                    )
                )
                // Varre subpastas ate 3 niveis de profundidade
                if (relPath.count { it == '/' } < 3) {
                    scanDir(folder, relPath)
                }
            }
        }

        scanDir(root, "")
        return result
    }

    // Lista os arquivos recentes da pasta/subpasta ativa
    fun listRecentFilesInAlbum(baseRelativePath: String, albumRelativePath: String, limit: Int = 8): List<File> {
        val root = resolveRootDir(baseRelativePath, createIfMissing = false)
        val albumDir = File(root, sanitizeRelativePath(albumRelativePath))
        if (!albumDir.exists()) return emptyList()
        return albumDir.listFiles { f -> f.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.take(limit)
            .orEmpty()
    }

    // Cria uma pasta na raiz ou uma subpasta dentro da pasta ativa (suporta caminhos como DESC/JF22JS35)
    fun createAlbum(baseRelativePath: String, albumRelativePath: String): String? {
        val cleanRel = sanitizeRelativePath(albumRelativePath)
        if (cleanRel.isEmpty()) return null
        val root = resolveRootDir(baseRelativePath, createIfMissing = true)
        val folder = File(root, cleanRel)
        val ok = if (folder.exists()) true else folder.mkdirs()
        return if (ok) cleanRel else null
    }

    // Renomeia a pasta ou subpasta ativa mantendo o mesmo nivel hierarquico
    fun renameAlbum(baseRelativePath: String, oldRelativePath: String, newSingleName: String): String? {
        val cleanNewName = sanitizeSingleName(newSingleName)
        if (cleanNewName.isEmpty()) return null
        val root = resolveRootDir(baseRelativePath, createIfMissing = false)
        val oldFolder = File(root, sanitizeRelativePath(oldRelativePath))
        if (!oldFolder.exists()) return null

        val parentDir = oldFolder.parentFile ?: root
        val newFolder = File(parentDir, cleanNewName)
        if (newFolder.exists()) return null

        return if (oldFolder.renameTo(newFolder)) {
            val parentRel = oldRelativePath.substringBeforeLast("/", "")
            if (parentRel.isEmpty()) cleanNewName else "$parentRel/$cleanNewName"
        } else null
    }

    // Exclui uma pasta especifica
    fun deleteAlbum(baseRelativePath: String, albumRelativePath: String): Boolean {
        val root = resolveRootDir(baseRelativePath, createIfMissing = false)
        val target = File(root, sanitizeRelativePath(albumRelativePath))
        if (!target.exists()) return false
        return target.deleteRecursively()
    }

    // FAXINA INTELIGENTE: Remove todas as pastas vazias (sem fotos) criadas acidentalmente em DCIM e na raiz ativa
    fun cleanEmptyGhostFolders(baseRelativePath: String): Int {
        var removedCount = 0
        val dcimDir = File(Environment.getExternalStorageDirectory(), "DCIM")
        val activeRoot = resolveRootDir(baseRelativePath, createIfMissing = false)

        fun purgeEmptyRecursively(dir: File, isProtectedRoot: Boolean): Boolean {
            if (!dir.exists() || !dir.isDirectory) return false
            val children = dir.listFiles().orEmpty()
            for (child in children) {
                if (child.isDirectory) {
                    purgeEmptyRecursively(child, isProtectedRoot = false)
                }
            }
            val remaining = dir.listFiles().orEmpty()
            if (remaining.isEmpty() && !isProtectedRoot) {
                if (dir.delete()) {
                    removedCount++
                    return true
                }
            }
            return false
        }

        // Limpa pastas vazias na raiz configurada e no DCIM geral (removendo J, JF, D, De, C, Ca, etc. que estejam vazias)
        purgeEmptyRecursively(activeRoot, isProtectedRoot = true)
        if (dcimDir.exists()) {
            purgeEmptyRecursively(dcimDir, isProtectedRoot = true)
        }
        return removedCount
    }

    // Cria o arquivo final com Prefixo + DataHora dentro da pasta ou subpasta ativa
    fun createTargetMediaFile(
        baseRelativePath: String,
        albumRelativePath: String,
        prefix: String,
        extension: String = "jpg"
    ): File {
        val root = resolveRootDir(baseRelativePath, createIfMissing = true)
        val cleanAlbumRel = sanitizeRelativePath(albumRelativePath).ifEmpty { "Geral" }
        val albumDir = File(root, cleanAlbumRel)
        if (!albumDir.exists()) {
            albumDir.mkdirs()
        }

        val cleanPrefix = sanitizePrefix(prefix)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val fileName = "${cleanPrefix}_${timestamp}.$extension"
        return File(albumDir, fileName)
    }

    fun indexFileInGallery(context: Context, file: File) {
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf("image/jpeg")
        ) { _, _ -> }
    }

    // Permite barras '/' para suportar subpastas (ex: DESC/JF22JS35)
    fun sanitizeRelativePath(input: String): String {
        return input.trim()
            .replace("\\", "/")
            .replace(":", "_")
            .split("/")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")
    }

    fun sanitizeSingleName(input: String): String {
        return input.trim()
            .replace("/", "_")
            .replace("\\", "_")
            .replace(":", "_")
    }

    fun sanitizePrefix(input: String): String {
        return input.trim()
            .replace(" ", "_")
            .replace("/", "_")
            .replace("\\", "_")
            .ifEmpty { "FOTO" }
    }
}
