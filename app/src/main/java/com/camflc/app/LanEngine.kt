package com.camflc.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.*
import java.util.Collections
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class PeerDevice(
    val name: String,
    val ip: String,
    val port: Int = 8080,
    val isPaired: Boolean = false
)

data class RemoteFileInfo(
    val albumRelativePath: String,
    val name: String,
    val sizeBytes: Long
) {
    val uniqueKey: String get() = "$albumRelativePath/$name"

    fun formattedSize(): String = StorageEngine.formatBytes(sizeBytes)
}

class LanEngine(
    private val context: Context,
    private val getBaseDirectory: () -> String,
    private val getDeviceName: () -> String,
    private val isRemoteDeleteAllowed: () -> Boolean,
    private val onStorageChanged: () -> Unit
) {
    companion object {
        const val HTTP_PORT = 8080
        const val UDP_PORT = 45455
    }

    private var serverJob: Job? = null
    private var udpListenerJob: Job? = null
    private var udpBroadcasterJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var isRunning: Boolean = false
        private set

    private fun notifyStorageChangedMainThread() {
        engineScope.launch(Dispatchers.Main) {
            onStorageChanged()
        }
    }

    fun startServer(onPeerDiscovered: (PeerDevice) -> Unit) {
        if (isRunning) return
        isRunning = true

        // 1. Servidor HTTP Multithread na porta 8080
        serverJob = engineScope.launch {
            try {
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(HTTP_PORT))
                serverSocket = socket

                while (isActive && isRunning) {
                    val client = socket.accept()
                    launch { handleHttpClient(client) }
                }
            } catch (_: Exception) {
                isRunning = false
            }
        }

        // 2. Radar UDP para descobrir outros dispositivos CamFLC na mesma rede
        udpListenerJob = engineScope.launch {
            try {
                val dSocket = DatagramSocket(null)
                dSocket.reuseAddress = true
                dSocket.broadcast = true
                dSocket.bind(InetSocketAddress(UDP_PORT))
                udpSocket = dSocket

                val buffer = ByteArray(512)

                while (isActive && isRunning) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    dSocket.receive(packet)
                    val senderIp = packet.address.hostAddress ?: continue
                    val myIp = getLocalIpAddress()
                    if (senderIp == myIp || senderIp == "127.0.0.1") continue

                    val msg = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    if (msg.startsWith("CAMFLC_PEER|")) {
                        val parts = msg.split("|")
                        if (parts.size >= 3) {
                            val peerName = parts[1]
                            val peerPort = parts[2].toIntOrNull() ?: HTTP_PORT
                            withContext(Dispatchers.Main) {
                                onPeerDiscovered(
                                    PeerDevice(
                                        name = peerName,
                                        ip = senderIp,
                                        port = peerPort
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 3. Emissor automatico de presenca na rede a cada 4 segundos
        udpBroadcasterJob = engineScope.launch {
            while (isActive && isRunning) {
                sendDiscoveryBroadcast()
                delay(4000)
            }
        }
    }

    fun sendDiscoveryBroadcast() {
        engineScope.launch {
            try {
                val payload = "CAMFLC_PEER|${getDeviceName()}|$HTTP_PORT"
                val bytes = payload.toByteArray(Charsets.UTF_8)
                DatagramSocket().use { sender ->
                    sender.broadcast = true
                    val targets = mutableSetOf("255.255.255.255")
                    val myIp = getLocalIpAddress()
                    if (myIp.count { it == '.' } == 3) {
                        targets.add(myIp.substringBeforeLast(".") + ".255")
                    }
                    for (targetIp in targets) {
                        val packet = DatagramPacket(
                            bytes,
                            bytes.size,
                            InetAddress.getByName(targetIp),
                            UDP_PORT
                        )
                        sender.send(packet)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun stopServer() {
        isRunning = false
        runCatching { serverSocket?.close() }
        runCatching { udpSocket?.close() }
        serverJob?.cancel()
        udpListenerJob?.cancel()
        udpBroadcasterJob?.cancel()
    }

    private fun handleHttpClient(client: Socket) {
        client.use { sock ->
            try {
                sock.soTimeout = 15000
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                val out = BufferedOutputStream(sock.getOutputStream())

                val requestLine = reader.readLine() ?: return
                var headerLine = reader.readLine()
                while (!headerLine.isNullOrEmpty()) {
                    headerLine = reader.readLine()
                }

                val parts = requestLine.split(" ")
                if (parts.size < 2) return

                val fullUri = parts[1]
                val path = fullUri.substringBefore("?")
                val query = fullUri.substringAfter("?", "")
                val params = parseQueryParams(query)

                val baseDir = getBaseDirectory()

                when (path) {
                    "/", "/index.html" -> {
                        val selectedAlbum = params["album"]
                        val html = buildPcWebPortalHtml(baseDir, selectedAlbum)
                        sendTextResponse(out, "200 OK", "text/html; charset=utf-8", html)
                    }

                    "/api/info" -> {
                        val albums = StorageEngine.listAllAlbumsRecursively(baseDir)
                        val arr = JSONArray()
                        albums.forEach { a ->
                            arr.put(
                                JSONObject().apply {
                                    put("name", a.name)
                                    put("relativePath", a.relativePath)
                                    put("fileCount", a.fileCount)
                                    put("sizeBytes", a.sizeBytes)
                                }
                            )
                        }
                        val rootJson = JSONObject().apply {
                            put("deviceName", getDeviceName())
                            put("baseDirectory", baseDir)
                            put("allowRemoteDelete", isRemoteDeleteAllowed())
                            put("albums", arr)
                        }
                        sendTextResponse(out, "200 OK", "application/json; charset=utf-8", rootJson.toString())
                    }

                    "/api/files" -> {
                        val albumRel = params["album"].orEmpty()
                        val rootDir = StorageEngine.resolveRootDir(baseDir, createIfMissing = false)
                        val albumDir = File(rootDir, StorageEngine.sanitizeRelativePath(albumRel))
                        val files = albumDir.listFiles { f -> StorageEngine.isMediaFile(f) }
                            ?.sortedByDescending { it.lastModified() }
                            .orEmpty()

                        val arr = JSONArray()
                        files.forEach { f ->
                            arr.put(
                                JSONObject().apply {
                                    put("album", albumRel)
                                    put("name", f.name)
                                    put("sizeBytes", f.length())
                                }
                            )
                        }
                        val json = JSONObject().apply {
                            put("album", albumRel)
                            put("files", arr)
                        }
                        sendTextResponse(out, "200 OK", "application/json; charset=utf-8", json.toString())
                    }

                    "/api/all_files" -> {
                        val albums = StorageEngine.listAllAlbumsRecursively(baseDir)
                        val rootDir = StorageEngine.resolveRootDir(baseDir, createIfMissing = false)
                        val arr = JSONArray()

                        for (album in albums) {
                            val albumDir = File(rootDir, StorageEngine.sanitizeRelativePath(album.relativePath))
                            val files = albumDir.listFiles { f -> StorageEngine.isMediaFile(f) }
                                ?.sortedByDescending { it.lastModified() }
                                .orEmpty()
                            for (f in files) {
                                arr.put(
                                    JSONObject().apply {
                                        put("album", album.relativePath)
                                        put("name", f.name)
                                        put("sizeBytes", f.length())
                                    }
                                )
                            }
                        }
                        val json = JSONObject().apply {
                            put("files", arr)
                        }
                        sendTextResponse(out, "200 OK", "application/json; charset=utf-8", json.toString())
                    }

                    "/api/thumb" -> {
                        val albumRel = params["album"].orEmpty()
                        val fileName = params["name"].orEmpty()
                        val rootDir = StorageEngine.resolveRootDir(baseDir, createIfMissing = false)
                        val targetFile = File(
                            File(rootDir, StorageEngine.sanitizeRelativePath(albumRel)),
                            StorageEngine.sanitizeSingleName(fileName)
                        )
                        if (targetFile.exists() && targetFile.isFile) {
                            sendThumbnailResponse(out, targetFile)
                        } else {
                            sendTextResponse(out, "404 Not Found", "text/plain", "Miniatura nao encontrada")
                        }
                    }

                    "/api/file" -> {
                        val albumRel = params["album"].orEmpty()
                        val fileName = params["name"].orEmpty()
                        val rootDir = StorageEngine.resolveRootDir(baseDir, createIfMissing = false)
                        val targetFile = File(
                            File(rootDir, StorageEngine.sanitizeRelativePath(albumRel)),
                            StorageEngine.sanitizeSingleName(fileName)
                        )
                        if (targetFile.exists() && targetFile.isFile) {
                            sendFileResponse(out, targetFile)
                        } else {
                            sendTextResponse(out, "404 Not Found", "text/plain", "Arquivo nao encontrado")
                        }
                    }

                    "/api/zip" -> {
                        val albumRel = params["album"].orEmpty()
                        val rootDir = StorageEngine.resolveRootDir(baseDir, createIfMissing = false)
                        val targetDir = if (albumRel.isEmpty()) {
                            rootDir
                        } else {
                            File(rootDir, StorageEngine.sanitizeRelativePath(albumRel))
                        }
                        if (targetDir.exists() && targetDir.isDirectory) {
                            val zipName = (albumRel.replace("/", "_").ifEmpty { "CamFLC_Completo" }) + ".zip"
                            sendZipStreamResponse(out, targetDir, zipName)
                        } else {
                            sendTextResponse(out, "404 Not Found", "text/plain", "Pasta nao encontrada")
                        }
                    }

                    "/api/zip_selected" -> {
                        val itemsParam = params["items"].orEmpty()
                        val rootDir = StorageEngine.resolveRootDir(baseDir, createIfMissing = false)
                        val selectedPairs = itemsParam.split("|").filter { it.contains("/") }
                        sendSelectedZipStreamResponse(out, rootDir, selectedPairs)
                    }

                    "/api/delete_selected" -> {
                        if (!isRemoteDeleteAllowed()) {
                            sendTextResponse(out, "403 Forbidden", "text/plain", "Exclusao bloqueada neste aparelho")
                            return
                        }
                        val itemsParam = params["items"].orEmpty()
                        val rootDir = StorageEngine.resolveRootDir(baseDir, createIfMissing = false)
                        val selectedPairs = itemsParam.split("|").filter { it.contains("/") }
                        var deletedCount = 0
                        for (relItem in selectedPairs) {
                            val cleanRel = StorageEngine.sanitizeRelativePath(relItem)
                            val target = File(rootDir, cleanRel)
                            if (target.exists() && target.isFile && target.delete()) {
                                deletedCount++
                            }
                        }
                        if (deletedCount > 0) {
                            notifyStorageChangedMainThread()
                        }
                        val redirectAlbum = params["album"].orEmpty()
                        if (params["web"] == "1") {
                            val enc = URLEncoder.encode(redirectAlbum, "UTF-8")
                            sendRedirect(out, if (redirectAlbum.isEmpty()) "/" else "/?album=$enc")
                        } else {
                            sendTextResponse(out, "200 OK", "application/json", "{\"deletedCount\":$deletedCount}")
                        }
                    }

                    "/api/delete_file" -> {
                        if (!isRemoteDeleteAllowed()) {
                            sendTextResponse(out, "403 Forbidden", "text/plain", "Exclusao bloqueada")
                            return
                        }
                        val albumRel = params["album"].orEmpty()
                        val fileName = params["name"].orEmpty()
                        val rootDir = StorageEngine.resolveRootDir(baseDir, createIfMissing = false)
                        val targetFile = File(
                            File(rootDir, StorageEngine.sanitizeRelativePath(albumRel)),
                            StorageEngine.sanitizeSingleName(fileName)
                        )
                        val deleted = targetFile.exists() && targetFile.delete()
                        if (deleted) {
                            notifyStorageChangedMainThread()
                        }

                        if (params["web"] == "1") {
                            val encodedAlbum = URLEncoder.encode(albumRel, "UTF-8")
                            sendRedirect(out, if (albumRel.isEmpty()) "/" else "/?album=$encodedAlbum")
                        } else {
                            sendTextResponse(out, "200 OK", "application/json", "{\"deleted\":$deleted}")
                        }
                    }

                    "/api/delete_album" -> {
                        if (!isRemoteDeleteAllowed()) {
                            sendTextResponse(out, "403 Forbidden", "text/plain", "Exclusao bloqueada")
                            return
                        }
                        val albumRel = params["album"].orEmpty()
                        val deleted = StorageEngine.deleteAlbum(baseDir, albumRel)
                        if (deleted) {
                            notifyStorageChangedMainThread()
                        }
                        sendTextResponse(out, "200 OK", "application/json", "{\"deleted\":$deleted}")
                    }

                    else -> {
                        sendTextResponse(out, "404 Not Found", "text/plain", "Rota nao encontrada")
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun buildPcWebPortalHtml(baseDir: String, selectedAlbum: String?): String {
        val albums = StorageEngine.listAllAlbumsRecursively(baseDir)
        val rootDir = StorageEngine.resolveRootDir(baseDir, createIfMissing = false)

        val galleryItems = mutableListOf<Pair<String, File>>()
        if (selectedAlbum.isNullOrBlank()) {
            for (alb in albums) {
                val dir = File(rootDir, StorageEngine.sanitizeRelativePath(alb.relativePath))
                dir.listFiles { f -> StorageEngine.isMediaFile(f) }
                    ?.sortedByDescending { it.lastModified() }
                    ?.forEach { f -> galleryItems.add(alb.relativePath to f) }
            }
        } else {
            val dir = File(rootDir, StorageEngine.sanitizeRelativePath(selectedAlbum))
            dir.listFiles { f -> StorageEngine.isMediaFile(f) }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { f -> galleryItems.add(selectedAlbum to f) }
        }

        val totalAllPhotos = albums.sumOf { it.fileCount }
        val currentAlbumParam = selectedAlbum.orEmpty()
        val encCurrentAlbum = URLEncoder.encode(currentAlbumParam, "UTF-8")

        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html lang='pt-PT'><head><meta charset='utf-8'>")
        sb.append("<meta name='viewport' content='width=device-width, initial-scale=1'>")
        sb.append("<title>CamFLC Web • ${getDeviceName()}</title>")
        sb.append("<style>")
        sb.append(":root{--bg:#F8FAFC;--card:#FFFFFF;--text:#0F172A;--sub:#475569;--border:#0F172A;--amber:#FFD600;--green:#00C853;--red:#D50000;}")
        sb.append("body.dark{--bg:#0B0F19;--card:#1E293B;--text:#F8FAFC;--sub:#CBD5E1;--border:#475569;}")
        sb.append("*{box-sizing:border-box;}body{font-family:system-ui,-apple-system,sans-serif;background:var(--bg);color:var(--text);margin:0;padding:16px;transition:background .2s;}")
        sb.append(".topbar{background:#0B0F19;color:#FFFFFF;padding:16px 20px;border-radius:14px;display:flex;flex-wrap:wrap;justify-content:space-between;align-items:center;gap:12px;margin-bottom:16px;border:2px solid var(--amber);}")
        sb.append(".layout{display:grid;grid-template-columns:260px 1fr;gap:16px;}")
        sb.append("@media(max-width:800px){.layout{grid-template-columns:1fr;}}")
        sb.append(".panel{background:var(--card);border:2px solid var(--border);border-radius:14px;padding:16px;}")
        sb.append(".folder-item{display:flex;justify-content:space-between;align-items:center;padding:10px 12px;border-radiu
