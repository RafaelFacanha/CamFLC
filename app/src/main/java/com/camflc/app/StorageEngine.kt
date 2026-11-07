package com.camflc.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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
    fun formattedSize(): String = StorageEngine.formatBytes(sizeBytes)
}

data class LocalMediaItem(
    val file: File,
    val name: String,
    val albumRelativePath: String,
    val sizeBytes: Long,
    val lastModified: Long
) {
    val uniquePath: String get() = file.absolutePath

    val isVideo: Boolean get() = StorageEngine.isVideoFile(file)

    fun formattedSize(): String = StorageEngine.formatBytes(sizeBytes)

    fun formattedDate(): String {
        return SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(lastModified))
    }
}

object StorageEngine {

    fun formatBytes(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> String.format(Locale.US, "%.2f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.0f KB", kb)
            else -> "$bytes B"
        }
    }

    fun isVideoFile(f: File): Boolean {
        return f.isFile && f.name.endsWith(".mp4", ignoreCase = true)
    }

    fun isMediaFile(f: File): Boolean {
        return f.isFile && (f.name.endsWith(".jpg", true) ||
                f.name.endsWith(".jpeg", true) ||
                f.name.endsWith(".png", true) ||
                f.name.endsWith(".mp4", true))
    }

    // Extrai miniatura rapida tanto de fotos (.jpg/.png) quanto de videos (.mp4)
    fun loadMediaThumbnail(file: File, sampleSize: Int = 4): Bitmap? {
        if (!file.exists() || !file.isFile) return null
        return if (isVideoFile(file)) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.frameAtTime
                if (frame != null && frame.width > 480) {
                    val ratio = 480f / frame.width.toFloat()
                    val targetH = (frame.height * ratio).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(frame, 480, targetH, true)
                } else {
                    frame
                }
            } catch (_: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        } else {
            try {
                val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                BitmapFactory.decodeFile(file.absolutePath, opts)
            } catch (_: Exception) {
                null
            }
        }
    }

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

    fun ensureDefaultAlbumsIfEmpty(baseRelativePath: String, defaultNames: List<String>) {
        val root = resolveRootDir(baseRelativePath, createIfMissing = true)
        val existingDirs = root.listFiles { f -> f.isDirectory }.orEmpty()
        if (existingDirs.isEmpty()) {
            defaultNames.forEach { name ->
                File(root, sanitizeSingleName(name)).mkdirs()
            }
        }
    }

    // Lista todas as pastas e subpastas recursivamente (com suporte a pesquisa de pastas)
    fun listAllAlbumsRecursively(baseRelativePath: String, searchQuery: String = ""): List<AlbumInfo> {
        val root = resolveRootDir(baseRelativePath, createIfMissing = true)
        val result = mutableListOf<AlbumInfo>()
        val cleanQuery = searchQuery.trim().lowercase(Locale.getDefault())

        fun scanDir(dir: File, currentRelative: String) {
            val childrenDirs = dir.listFiles { f -> f.isDirectory }
                ?.sortedBy { it.name.lowercase(Locale.getDefault()) }
                .orEmpty()

            for (folder in childrenDirs) {
                val relPath = if (currentRelative.isEmpty()) folder.name else "$currentRelative/${folder.name}"
                val mediaFiles = folder.listFiles { f -> isMediaFile(f) }.orEmpty()
                val subDirs = folder.listFiles { f -> f.isDirectory }.orEmpty()

                if (cleanQuery.isEmpty() || relPath.lowercase(Locale.getDefault()).contains(cleanQuery)) {
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
                }

                if (relPath.count { it == '/' } < 4) {
                    scanDir(folder, relPath)
                }
            }
        }

        scanDir(root, "")
        return result
    }

    // Busca e filtra fotos/videos na Galeria Local (por pasta/subpasta e por termo de pesquisa)
    fun queryLocalGallery(
        baseRelativePath: String,
        albumFilter: String? = null,
        searchQuery: String = ""
    ): List<LocalMediaItem> {
        val root = resolveRootDir(baseRelativePath, createIfMissing = false)
        if (!root.exists()) return emptyList()

        val allAlbums = listAllAlbumsRecursively(baseRelativePath)
        val targetAlbums = if (albumFilter.isNullOrBlank()) {
            allAlbums
        } else {
            allAlbums.filter { it.relativePath == albumFilter }
        }

        val cleanQuery = searchQuery.trim().lowercase(Locale.getDefault())
        val items = mutableListOf<LocalMediaItem>()

        for (alb in targetAlbums) {
            val dir = File(root, sanitizeRelativePath(alb.relativePath))
            val files = dir.listFiles { f -> isMediaFile(f) }.orEmpty()
            for (f in files) {
                val matchesSearch = cleanQuery.isEmpty() ||
                        f.name.lowercase(Locale.getDefault()).contains(cleanQuery) ||
                        alb.relativePath.lowercase(Locale.getDefault()).contains(cleanQuery)

                if (matchesSearch) {
                    items.add(
                        LocalMediaItem(
                            file = f,
                            name = f.name,
                            albumRelativePath = alb.relativePath,
                            sizeBytes = f.length(),
                            lastModified = f.lastModified()
                        )
                    )
                }
            }
        }

        return items.sortedByDescending { it.lastModified }
    }

    // Obtem a foto/video mais recente da pasta ativa (ou geral se a pasta ativa estiver vazia)
    fun getLatestMediaFile(baseRelativePath: String, activeFolder: String? = null): LocalMediaItem? {
        if (!activeFolder.isNullOrBlank()) {
            val activeItems = queryLocalGallery(baseRelativePath, albumFilter = activeFolder)
            if (activeItems.isNotEmpty()) return activeItems.first()
        }
        return queryLocalGallery(baseRelativePath, albumFilter = null).firstOrNull()
    }

    fun listRecentFilesInAlbum(baseRelativePath: String, albumRelativePath: String, limit: Int = 8): List<File> {
        val root = resolveRootDir(baseRelativePath, createIfMissing = false)
        val albumDir = File(root, sanitizeRelativePath(albumRelativePath))
        if (!albumDir.exists()) return emptyList()
        return albumDir.listFiles { f -> isMediaFile(f) }
            ?.sortedByDescending { it.lastModified() }
            ?.take(limit)
            .orEmpty()
    }

    // Cria uma pasta na raiz ou uma subpasta dentro da pasta ativa
    fun createAlbum(baseRelativePath: String, albumRelativePath: String): String? {
        val cleanRel = sanitizeRelativePath(albumRelativePath)
        if (cleanRel.isEmpty()) return null
        val root = resolveRootDir(baseRelativePath, createIfMissing = true)
        val folder = File(root, cleanRel)
        val ok = if (folder.exists()) true else folder.mkdirs()
        return if (ok) cleanRel else null
    }

    // Renomeia uma pasta/subpasta existente
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

    // Exclui uma pasta inteira do disco
    fun deleteAlbum(baseRelativePath: String, albumRelativePath: String): Boolean {
        val root = resolveRootDir(baseRelativePath, createIfMissing = false)
        val target = File(root, sanitizeRelativePath(albumRelativePath))
        if (!target.exists()) return false
        return target.deleteRecursively()
    }

    // Renomeia um arquivo individual (foto ou video) mantendo a extensao original
    fun renameMediaFile(context: Context, sourceFile: File, newBaseName: String): File? {
        if (!sourceFile.exists()) return null
        val ext = sourceFile.extension.ifEmpty { "jpg" }
        val cleanName = sanitizeSingleName(newBaseName.substringBeforeLast("."))
        if (cleanName.isEmpty()) return null

        val destFile = File(sourceFile.parentFile, "$cleanName.$ext")
        if (destFile.exists()) return null

        return if (sourceFile.renameTo(destFile)) {
            indexFileInGallery(context, destFile)
            destFile
        } else null
    }

    // Move um ou varios arquivos selecionados na Galeria para outra pasta/subpasta
    fun moveMediaFilesToAlbum(
        context: Context,
        baseRelativePath: String,
        filesToMove: List<File>,
        targetAlbumRelativePath: String
    ): Int {
        val root = resolveRootDir(baseRelativePath, createIfMissing = true)
        val destDir = File(root, sanitizeRelativePath(targetAlbumRelativePath))
        if (!destDir.exists()) destDir.mkdirs()

        var movedCount = 0
        for (src in filesToMove) {
            if (!src.exists() || src.parentFile?.absolutePath == destDir.absolutePath) continue
            val destFile = File(destDir, src.name)
            val finalDest = if (destFile.exists()) {
                val stamp = SimpleDateFormat("HHmmss", Locale.US).format(Date())
                File(destDir, "${src.nameWithoutExtension}_$stamp.${src.extension}")
            } else {
                destFile
            }

            val moved = if (src.renameTo(finalDest)) {
                true
            } else {
                try {
                    FileInputStream(src).use { input ->
                        FileOutputStream(finalDest).use { output ->
                            input.copyTo(output)
                        }
                    }
                    src.delete()
                    true
                } catch (_: Exception) {
                    false
                }
            }

            if (moved) {
                indexFileInGallery(context, finalDest)
                movedCount++
            }
        }
        return movedCount
    }

    // Exclui uma lista de arquivos selecionados na Galeria Local
    fun deleteMediaFiles(files: List<File>): Int {
        var deletedCount = 0
        for (f in files) {
            if (f.exists() && f.delete()) {
                deletedCount++
            }
        }
        return deletedCount
    }

    // Faxina inteligente: remove pastas vazias criadas acidentalmente
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

        purgeEmptyRecursively(activeRoot, isProtectedRoot = true)
        if (dcimDir.exists()) {
            purgeEmptyRecursively(dcimDir, isProtectedRoot = true)
        }
        return removedCount
    }

    // Cria o arquivo final (Foto .jpg ou Video .mp4) com Prefixo + DataHora
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
        val mime = when {
            file.name.endsWith(".mp4", true) -> "video/mp4"
            file.name.endsWith(".png", true) -> "image/png"
            else -> "image/jpeg"
        }
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf(mime)
        ) { _, _ -> }
    }

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
