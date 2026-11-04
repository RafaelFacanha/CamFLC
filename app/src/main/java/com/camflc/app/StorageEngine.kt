package com.camflc.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AlbumInfo(
    val name: String,
    val path: String,
    val fileCount: Int,
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

    // Resolve o diretorio base real dentro do armazenamento interno (/storage/emulated/0/...)
    fun resolveRootDir(baseRelativePath: String): File {
        val rootStorage = Environment.getExternalStorageDirectory()
        val cleaned = baseRelativePath.trim().trimStart('/').ifEmpty { "DCIM/CamFLC" }
        val target = File(rootStorage, cleaned)
        if (!target.exists()) {
            target.mkdirs()
        }
        return target
    }

    // Garante que as pastas iniciais existam fisicamente no disco
    fun ensureDefaultAlbums(baseRelativePath: String, defaultNames: List<String>) {
        val root = resolveRootDir(baseRelativePath)
        defaultNames.forEach { name ->
            val clean = sanitizeFolderName(name)
            if (clean.isNotEmpty()) {
                val folder = File(root, clean)
                if (!folder.exists()) {
                    folder.mkdirs()
                }
            }
        }
    }

    // Lista todas as pastas reais dentro do diretorio base com contagem de arquivos e tamanho
    fun listAlbums(baseRelativePath: String): List<AlbumInfo> {
        val root = resolveRootDir(baseRelativePath)
        val subDirs = root.listFiles { file -> file.isDirectory }?.toList().orEmpty()

        return subDirs.sortedBy { it.name.lowercase(Locale.getDefault()) }.map { folder ->
            val mediaFiles = folder.listFiles { f ->
                f.isFile && (f.name.endsWith(".jpg", true) ||
                        f.name.endsWith(".jpeg", true) ||
                        f.name.endsWith(".png", true) ||
                        f.name.endsWith(".mp4", true))
            }.orEmpty()

            val totalBytes = mediaFiles.sumOf { it.length() }
            AlbumInfo(
                name = folder.name,
                path = folder.absolutePath,
                fileCount = mediaFiles.size,
                sizeBytes = totalBytes
            )
        }
    }

    // Lista os nomes dos ultimos arquivos gravados dentro da pasta ativa
    fun listRecentFilesInAlbum(baseRelativePath: String, albumName: String, limit: Int = 8): List<File> {
        val albumDir = File(resolveRootDir(baseRelativePath), sanitizeFolderName(albumName))
        if (!albumDir.exists()) return emptyList()
        return albumDir.listFiles { f -> f.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.take(limit)
            .orEmpty()
    }

    // Cria uma nova pasta real no armazenamento
    fun createAlbum(baseRelativePath: String, albumName: String): Boolean {
        val clean = sanitizeFolderName(albumName)
        if (clean.isEmpty()) return false
        val folder = File(resolveRootDir(baseRelativePath), clean)
        return if (folder.exists()) true else folder.mkdirs()
    }

    // Renomeia uma pasta existente no disco
    fun renameAlbum(baseRelativePath: String, oldName: String, newName: String): Boolean {
        val cleanNew = sanitizeFolderName(newName)
        if (cleanNew.isEmpty() || oldName == cleanNew) return false
        val root = resolveRootDir(baseRelativePath)
        val oldFolder = File(root, oldName)
        val newFolder = File(root, cleanNew)
        if (!oldFolder.exists() || newFolder.exists()) return false
        return oldFolder.renameTo(newFolder)
    }

    // Exclui uma pasta e seus arquivos (apenas quando solicitado pelo usuario)
    fun deleteAlbum(baseRelativePath: String, albumName: String): Boolean {
        val root = resolveRootDir(baseRelativePath)
        val target = File(root, albumName)
        if (!target.exists()) return false
        return target.deleteRecursively()
    }

    // Cria o arquivo de midia de destino com Prefixo + DataHora (ex: OBRA_01_20261104_113022_120.jpg)
    fun createTargetMediaFile(
        baseRelativePath: String,
        albumName: String,
        prefix: String,
        extension: String = "jpg"
    ): File {
        val root = resolveRootDir(baseRelativePath)
        val cleanAlbum = sanitizeFolderName(albumName).ifEmpty { "Geral" }
        val albumDir = File(root, cleanAlbum)
        if (!albumDir.exists()) {
            albumDir.mkdirs()
        }

        val cleanPrefix = sanitizePrefix(prefix)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val fileName = "${cleanPrefix}_${timestamp}.$extension"
        return File(albumDir, fileName)
    }

    // Gera um arquivo JPEG real no armazenamento (para validar escrita instantanea com Volume -)
    fun generateQuickTestPhoto(
        context: Context,
        baseRelativePath: String,
        albumName: String,
        prefix: String
    ): Result<File> {
        return try {
            val targetFile = createTargetMediaFile(baseRelativePath, albumName, prefix, "jpg")

            val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(AndroidColor.BLACK)

            val paintTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.rgb(255, 214, 0) // Amarelo Solar
                textSize = 48f
                isFakeBoldText = true
            }
            val paintBody = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.WHITE
                textSize = 34f
            }
            val paintGreen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.rgb(0, 230, 118)
                textSize = 32f
                isFakeBoldText = true
            }

            val nowStr = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
            canvas.drawText("CamFLC - TESTE DE ESCRITA FISICA", 60f, 140f, paintTitle)
            canvas.drawText("Pasta / Album: $albumName", 60f, 240f, paintBody)
            canvas.drawText("Arquivo: ${targetFile.name}", 60f, 320f, paintBody)
            canvas.drawText("Data/Hora: $nowStr", 60f, 400f, paintBody)
            canvas.drawText("Caminho: ${targetFile.parent}", 60f, 480f, paintGreen)

            FileOutputStream(targetFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                out.flush()
            }
            bitmap.recycle()

            indexFileInGallery(context, targetFile)
            Result.success(targetFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Forca o Android a exibir a foto imediatamente na Galeria do celular
    fun indexFileInGallery(context: Context, file: File) {
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf("image/jpeg")
        ) { _, _ -> }
    }

    fun sanitizeFolderName(input: String): String {
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
