package com.camflc.app

import android.content.Context
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
    val lastSeenMs: Long = System.currentTimeMillis(),
    val isPaired: Boolean = false
)

data class RemoteFileInfo(
    val name: String,
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

    fun startServer(onPeerDiscovered: (PeerDevice) -> Unit) {
        if (isRunning) return
        isRunning = true

        // 1. Inicia o Servidor HTTP e API REST na porta 8080
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

        // 2. Escuta anuncios UDP de outros celulares CamFLC na mesma rede Wi-Fi/Hotspot
        udpListenerJob = engineScope.launch {
            try {
                val dSocket = DatagramSocket(null)
                dSocket.reuseAddress = true
                dSocket.broadcast = true
                dSocket.bind(InetSocketAddress(UDP_PORT))
                udpSocket = dSocket

                val buffer = ByteArray(512)
                val myIp = getLocalIpAddress()

                while (isActive && isRunning) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    dSocket.receive(packet)
                    val senderIp = packet.address.hostAddress ?: continue
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

        // 3. Anuncia este aparelho na rede local a cada 3 segundos
        udpBroadcasterJob = engineScope.launch {
            while (isActive && isRunning) {
                sendDiscoveryBroadcast()
                delay(3000)
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
                    // Envia para o broadcast global e tambem para o broadcast da sub-rede local
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
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        try {
            udpSocket?.close()
        } catch (_: Exception) {
        }
        serverJob?.cancel()
        udpListenerJob?.cancel()
        udpBroadcasterJob?.cancel()
    }

    private fun handleHttpClient(client: Socket) {
        client.use { sock ->
            try {
                sock.soTimeout = 15000
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                val out = sock.getOutputStream()

                val requestLine = reader.readLine() ?: return
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
                        val files = albumDir.listFiles { f -> f.isFile }
                            ?.sortedByDescending { it.lastModified() }
                            .orEmpty()

                        val arr = JSONArray()
                        files.forEach { f ->
                            arr.put(
                                JSONObject().apply {
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
                            val zipName = (albumRel.replace("/", "_").ifEmpty { "CamFLC_Todos" }) + ".zip"
                            sendZipStreamResponse(out, targetDir, zipName)
                        } else {
                            sendTextResponse(out, "404 Not Found", "text/plain", "Pasta nao encontrada")
                        }
                    }

                    "/api/delete_file" -> {
                        if (!isRemoteDeleteAllowed()) {
                            sendTextResponse(out, "403 Forbidden", "text/plain", "Exclusao remota bloqueada neste aparelho")
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
                            onStorageChanged()
                        }
                        val redirectWeb = params["web"] == "1"
                        if (redirectWeb) {
                            val encodedAlbum = URLEncoder.encode(albumRel, "UTF-8")
                            sendRedirect(out, "/?album=$encodedAlbum")
                        } else {
                            sendTextResponse(out, "200 OK", "application/json", "{\"deleted\":$deleted}")
                        }
                    }

                    "/api/delete_album" -> {
                        if (!isRemoteDeleteAllowed()) {
                            sendTextResponse(out, "403 Forbidden", "text/plain", "Exclusao remota bloqueada neste aparelho")
                            return
                        }
                        val albumRel = params["album"].orEmpty()
                        val deleted = StorageEngine.deleteAlbum(baseDir, albumRel)
                        if (deleted) {
                            onStorageChanged()
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
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html lang='pt-BR'><head><meta charset='utf-8'>")
        sb.append("<meta name='viewport' content='width=device-width, initial-scale=1'>")
        sb.append("<title>CamFLC • Painel LAN (${getDeviceName()})</title>")
        sb.append("<style>")
        sb.append("body{font-family:sans-serif;background:#FFFFFF;color:#000000;margin:0;padding:20px;}")
        sb.append(".header{background:#000000;color:#FFD600;padding:18px;border-radius:12px;margin-bottom:20px;}")
        sb.append(".card{background:#F1F5F9;border:2px solid #000000;border-radius:12px;padding:16px;margin-bottom:16px;}")
        sb.append(".btn{display:inline-block;background:#000000;color:#FFD600;padding:10px 16px;text-decoration:none;font-weight:bold;border-radius:8px;margin-right:8px;margin-top:6px;}")
        sb.append(".btn-amber{background:#FFD600;color:#000000;border:2px solid #000000;}")
        sb.append(".btn-red{background:#D50000;color:#FFFFFF;}")
        sb.append("table{width:100%;border-collapse:collapse;margin-top:12px;}")
        sb.append("th,td{border-bottom:1px solid #000;padding:10px;text-align:left;font-weight:bold;}")
        sb.append("</style></head><body>")

        sb.append("<div class='header'>")
        sb.append("<h2 style='margin:0;'>CamFLC • GERENCIADOR DE REDE LOCAL</h2>")
        sb.append("<p style='color:#FFF;margin:6px 0 0 0;font-weight:bold;'>Dispositivo: ${getDeviceName()} | Raiz: /storage/emulated/0/$baseDir</p>")
        sb.append("<a class='btn btn-amber' href='/api/zip?album='>⬇ BAIXAR TODAS AS PASTAS (.ZIP)</a>")
        sb.append("</div>")

        sb.append("<div class='card'><h3>1. ÁLBUNS E SUBPASTAS NO DISPOSITIVO (${albums.size})</h3>")
        for (album in albums) {
            val enc = URLEncoder.encode(album.relativePath, "UTF-8")
            sb.append("<div style='padding:10px;border-bottom:1px solid #ccc;'>")
            sb.append("<span style='font-size:18px;font-weight:900;'>📁 ${album.relativePath}</span> ")
            sb.append("<span>(${album.fileCount} arquivos • ${album.formattedSize()})</span><br>")
            sb.append("<a class='btn' href='/?album=$enc'>VER ARQUIVOS</a>")
            sb.append("<a class='btn btn-amber' href='/api/zip?album=$enc'>⬇ BAIXAR PASTA (.ZIP)</a>")
            sb.append("</div>")
        }
        sb.append("</div>")

        if (!selectedAlbum.isNullOrBlank()) {
            val rootDir = StorageEngine.resolveRootDir(baseDir, createIfMissing = false)
            val folder = File(rootDir, StorageEngine.sanitizeRelativePath(selectedAlbum))
            val files = folder.listFiles { f -> f.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()
            val encAlbum = URLEncoder.encode(selectedAlbum, "UTF-8")

            sb.append("<div class='card'>")
            sb.append("<h3>2. ARQUIVOS EM '$selectedAlbum' (${files.size})</h3>")
            sb.append("<table><tr><th>Arquivo</th><th>Tamanho</th><th>Ações</th></tr>")
            for (f in files) {
                val encFile = URLEncoder.encode(f.name, "UTF-8")
                sb.append("<tr>")
                sb.append("<td>${f.name}</td>")
                sb.append("<td>${f.length() / 1024} KB</td>")
                sb.append("<td>")
                sb.append("<a class='btn' target='_blank' href='/api/file?album=$encAlbum&name=$encFile'>ABRIR / BAIXAR</a>")
                if (isRemoteDeleteAllowed()) {
                    sb.append("<a class='btn btn-red' href='/api/delete_file?album=$encAlbum&name=$encFile&web=1'>EXCLUIR</a>")
                }
                sb.append("</td></tr>")
            }
            sb.append("</table></div>")
        }

        sb.append("</body></html>")
        return sb.toString()
    }

    private fun sendTextResponse(out: OutputStream, status: String, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $status\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun sendRedirect(out: OutputStream, location: String) {
        val header = "HTTP/1.1 302 Found\r\nLocation: $location\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun sendFileResponse(out: OutputStream, file: File) {
        val mime = when {
            file.name.endsWith(".png", true) -> "image/png"
            file.name.endsWith(".mp4", true) -> "video/mp4"
            else -> "image/jpeg"
        }
        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $mime\r\n" +
                "Content-Length: ${file.length()}\r\n" +
                "Content-Disposition: inline; filename=\"${file.name}\"\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        FileInputStream(file).use { fis ->
            val buf = ByteArray(16 * 1024)
            var read: Int
            while (fis.read(buf).also { read = it } != -1) {
                out.write(buf, 0, read)
            }
        }
        out.flush()
    }

    private fun sendZipStreamResponse(out: OutputStream, rootFolder: File, zipFileName: String) {
        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/zip\r\n" +
                "Content-Disposition: attachment; filename=\"$zipFileName\"\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))

        ZipOutputStream(BufferedOutputStream(out)).use { zos ->
            fun addFolderToZip(folder: File, parentPath: String) {
                val children = folder.listFiles().orEmpty()
                val buf = ByteArray(16 * 1024)
                for (child in children) {
                    val entryPath = if (parentPath.isEmpty()) child.name else "$parentPath/${child.name}"
                    if (child.isDirectory) {
                        addFolderToZip(child, entryPath)
                    } else if (child.isFile) {
                        val entry = ZipEntry(entryPath)
                        zos.putNextEntry(entry)
                        FileInputStream(child).use { fis ->
                            var len: Int
                            while (fis.read(buf).also { len = it } != -1) {
                                zos.write(buf, 0, len)
                            }
                        }
                        zos.closeEntry()
                    }
                }
            }
            addFolderToZip(rootFolder, "")
            zos.flush()
        }
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val map = mutableMapOf<String, String>()
        query.split("&").forEach { pair ->
            val k = URLDecoder.decode(pair.substringBefore("="), "UTF-8")
            val v = URLDecoder.decode(pair.substringAfter("=", ""), "UTF-8")
            if (k.isNotEmpty()) map[k] = v
        }
        return map
    }

    // =========================================================================
    // CLIENTE LAN: COMUNICACAO E SINCRONIZACAO ENTRE DISPOSITIVOS PAREADOS
    // =========================================================================

    suspend fun fetchRemoteDeviceInfo(peerIp: String, peerPort: Int = HTTP_PORT): Result<Pair<String, List<AlbumInfo>>> =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("http://$peerIp:$peerPort/api/info")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 6000
                    requestMethod = "GET"
                }
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val json = JSONObject(text)
                val deviceName = json.optString("deviceName", peerIp)
                val arr = json.optJSONArray("albums") ?: JSONArray()
                val list = mutableListOf<AlbumInfo>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        AlbumInfo(
                            name = obj.optString("name"),
                            relativePath = obj.optString("relativePath"),
                            fullPath = "",
                            fileCount = obj.optInt("fileCount", 0),
                            subFolderCount = 0,
                            sizeBytes = obj.optLong("sizeBytes", 0L)
                        )
                    )
                }
                Result.success(deviceName to list)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun fetchRemoteAlbumFiles(
        peerIp: String,
        peerPort: Int = HTTP_PORT,
        albumRelativePath: String
    ): Result<List<RemoteFileInfo>> = withContext(Dispatchers.IO) {
        try {
            val encAlbum = URLEncoder.encode(albumRelativePath, "UTF-8")
            val url = URL("http://$peerIp:$peerPort/api/files?album=$encAlbum")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 6000
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(text)
            val arr = json.optJSONArray("files") ?: JSONArray()
            val files = mutableListOf<RemoteFileInfo>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                files.add(
                    RemoteFileInfo(
                        name = o.optString("name"),
                        sizeBytes = o.optLong("sizeBytes", 0L)
                    )
                )
            }
            Result.success(files)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Copia ou Transfere (Move) uma pasta inteira do dispositivo remoto para este celular
    suspend fun syncAlbumFromPeer(
        peerIp: String,
        peerPort: Int = HTTP_PORT,
        albumRelativePath: String,
        deleteRemoteAfterTransfer: Boolean,
        onProgress: (String) -> Unit
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val filesResult = fetchRemoteAlbumFiles(peerIp, peerPort, albumRelativePath)
            val remoteFiles = filesResult.getOrElse { return@withContext Result.failure(it) }

            val localBase = getBaseDirectory()
            val localRoot = StorageEngine.resolveRootDir(localBase, createIfMissing = true)
            val targetAlbumDir = File(localRoot, StorageEngine.sanitizeRelativePath(albumRelativePath))
            if (!targetAlbumDir.exists()) targetAlbumDir.mkdirs()

            var copiedCount = 0
            val encAlbum = URLEncoder.encode(albumRelativePath, "UTF-8")

            for ((index, remoteFile) in remoteFiles.withIndex()) {
                withContext(Dispatchers.Main) {
                    onProgress("Baixando (${index + 1}/${remoteFiles.size}): ${remoteFile.name}...")
                }

                val localFile = File(targetAlbumDir, StorageEngine.sanitizeSingleName(remoteFile.name))
                // Se o arquivo ja existe com o mesmo tamanho exato, nao baixa duplicado
                if (!localFile.exists() || localFile.length() != remoteFile.sizeBytes) {
                    val encName = URLEncoder.encode(remoteFile.name, "UTF-8")
                    val fileUrl = URL("http://$peerIp:$peerPort/api/file?album=$encAlbum&name=$encName")
                    val conn = (fileUrl.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 5000
                        readTimeout = 15000
                    }
                    conn.inputStream.use { input ->
                        FileOutputStream(localFile).use { output ->
                            val buf = ByteArray(16 * 1024)
                            var r: Int
                            while (input.read(buf).also { r = it } != -1) {
                                output.write(buf, 0, r)
                            }
                            output.flush()
                        }
                    }
                    StorageEngine.indexFileInGallery(context, localFile)
                    copiedCount++
                }

                // Se for MODO TRANSFERENCIA (MOVER) e o arquivo local foi gravado com sucesso, apaga no remoto
                if (deleteRemoteAfterTransfer && localFile.exists() && localFile.length() > 0) {
                    val encName = URLEncoder.encode(remoteFile.name, "UTF-8")
                    val delUrl = URL("http://$peerIp:$peerPort/api/delete_file?album=$encAlbum&name=$encName")
                    runCatching {
                        (delUrl.openConnection() as HttpURLConnection).inputStream.close()
                    }
                }
            }

            if (deleteRemoteAfterTransfer) {
                val delAlbumUrl = URL("http://$peerIp:$peerPort/api/delete_album?album=$encAlbum")
                runCatching {
                    (delAlbumUrl.openConnection() as HttpURLConnection).inputStream.close()
                }
            }

            withContext(Dispatchers.Main) {
                onStorageChanged()
            }
            Result.success(copiedCount)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteRemoteSingleFile(
        peerIp: String,
        peerPort: Int = HTTP_PORT,
        albumRelativePath: String,
        fileName: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val encAlbum = URLEncoder.encode(albumRelativePath, "UTF-8")
            val encName = URLEncoder.encode(fileName, "UTF-8")
            val url = URL("http://$peerIp:$peerPort/api/delete_file?album=$encAlbum&name=$encName")
            val text = (url.openConnection() as HttpURLConnection)
                .inputStream.bufferedReader().use { it.readText() }
            text.contains("\"deleted\":true")
        } catch (_: Exception) {
            false
        }
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return "Sem Wi-Fi / Ative Wi-Fi ou Roteador"
    }
}
