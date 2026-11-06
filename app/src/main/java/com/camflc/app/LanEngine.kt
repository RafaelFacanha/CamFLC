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
                            withContext(Dispatchers.Main) { onStorageChanged() }
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
                            withContext(Dispatchers.Main) { onStorageChanged() }
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
                            withContext(Dispatchers.Main) { onStorageChanged() }
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

    // Portal Web LAN Modernizado: Tema Claro/Escuro, Pesquisa Instantanea, Filtro e Lightbox
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
        sb.append(".folder-item{display:flex;justify-content:space-between;align-items:center;padding:10px 12px;bord                             .orEmpty()
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

                    // Gerador ultrarrapido de Miniaturas (Thumbnails ~25KB) para Galeria LAN e Web
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
                            val zipName = (albumRel.replace("/", "_").ifEmpty { "CamFLC_Todos" }) + ".zip"
                            sendZipStreamResponse(out, targetDir, zipName)
                        } else {
                            sendTextResponse(out, "404 Not Found", "text/plain", "Pasta nao encontrada")
                        }
                    }

                    // Baixa em .ZIP apenas as fotos selecionadas individualmente no PC
                    "/api/zip_selected" -> {
                        val itemsParam = params["items"].orEmpty()
                        val rootDir = StorageEngine.resolveRootDir(baseDir, createIfMissing = false)
                        val selectedPairs = itemsParam.split("|").filter { it.contains("/") }
                        sendSelectedZipStreamResponse(out, rootDir, selectedPairs)
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
                        if (deleted) onStorageChanged()

                        if (params["web"] == "1") {
                            val encodedAlbum = URLEncoder.encode(albumRel, "UTF-8")
                            sendRedirect(out, "/?album=$encodedAlbum")
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
                        if (deleted) onStorageChanged()
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

    private fun isMediaFile(f: File): Boolean {
        return f.isFile && (f.name.endsWith(".jpg", true) ||
                f.name.endsWith(".jpeg", true) ||
                f.name.endsWith(".png", true) ||
                f.name.endsWith(".mp4", true))
    }

    // Painel Web para Computador com Modo Galeria de Miniaturas e Selecao Individual
    private fun buildPcWebPortalHtml(baseDir: String, selectedAlbum: String?): String {
        val albums = StorageEngine.listAllAlbumsRecursively(baseDir)
        val rootDir = StorageEngine.resolveRootDir(baseDir, createIfMissing = false)

        // Se nenhuma pasta especifica foi clicada, exibe a Galeria Geral com todas as fotos
        val galleryItems = mutableListOf<Pair<String, File>>()
        if (selectedAlbum.isNullOrBlank()) {
            for (alb in albums) {
                val dir = File(rootDir, StorageEngine.sanitizeRelativePath(alb.relativePath))
                dir.listFiles { f -> isMediaFile(f) }
                    ?.sortedByDescending { it.lastModified() }
                    ?.forEach { f -> galleryItems.add(alb.relativePath to f) }
            }
        } else {
            val dir = File(rootDir, StorageEngine.sanitizeRelativePath(selectedAlbum))
            dir.listFiles { f -> isMediaFile(f) }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { f -> galleryItems.add(selectedAlbum to f) }
        }

        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html lang='pt-BR'><head><meta charset='utf-8'>")
        sb.append("<meta name='viewport' content='width=device-width, initial-scale=1'>")
        sb.append("<title>CamFLC • Galeria LAN (${getDeviceName()})</title>")
        sb.append("<style>")
        sb.append("body{font-family:sans-serif;background:#FFFFFF;color:#000000;margin:0;padding:20px;}")
        sb.append(".header{background:#000000;color:#FFD600;padding:18px;border-radius:12px;margin-bottom:20px;}")
        sb.append(".card{background:#F1F5F9;border:2px solid #000000;border-radius:12px;padding:16px;margin-bottom:16px;}")
        sb.append(".btn{display:inline-block;background:#000000;color:#FFD600;padding:10px 14px;text-decoration:none;font-weight:bold;border-radius:8px;margin-right:8px;margin-top:6px;cursor:pointer;border:none;}")
        sb.append(".btn-amber{background:#FFD600;color:#000000;border:2px solid #000000;}")
        sb.append(".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(180px,1fr));gap:14px;margin-top:14px;}")
        sb.append(".thumb-card{background:#FFFFFF;border:2px solid #000000;border-radius:10px;padding:8px;text-align:center;}")
        sb.append(".thumb-card img{width:100%;height:140px;object-fit:cover;border-radius:6px;background:#000;}")
        sb.append(".chk{transform:scale(1.6);margin-right:8px;cursor:pointer;}")
        sb.append("</style>")
        sb.append("<script>")
        sb.append("function toggleAll(source){const c=document.querySelectorAll('.photo-chk');for(let i=0;i<c.length;i++){c[i].checked=source.checked;}}")
        sb.append("function downloadSelected(){const c=document.querySelectorAll('.photo-chk:checked');if(c.length===0){alert('Selecione pelo menos 1 foto!');return;}const arr=[];c.forEach(x=>arr.push(x.value));window.location.href='/api/zip_selected?items='+encodeURIComponent(arr.join('|'));}")
        sb.append("</script></head><body>")

        sb.append("<div class='header'>")
        sb.append("<h2 style='margin:0;'>CamFLC • GALERIA & REDE LOCAL (${getDeviceName()})</h2>")
        sb.append("<a class='btn btn-amber' href='/'>🖼 GALERIA GERAL (TODAS AS FOTOS)</a>")
        sb.append("<a class='btn btn-amber' href='/api/zip?album='>⬇ BAIXAR TODAS AS PASTAS (.ZIP)</a>")
        sb.append("</div>")

        sb.append("<div class='card'><h3>1. FILTRAR POR PASTA / SUBPASTA (${albums.size})</h3>")
        for (album in albums) {
            val enc = URLEncoder.encode(album.relativePath, "UTF-8")
            val activeStyle = if (album.relativePath == selectedAlbum) "border:3px solid #00C853;" else ""
            sb.append("<a class='btn' style='$activeStyle' href='/?album=$enc'>📁 ${album.relativePath} (${album.fileCount})</a>")
        }
        sb.append("</div>")

        val titleGallery = if (selectedAlbum.isNullOrBlank()) {
            "2. MODO GALERIA GERAL (${galleryItems.size} fotos em todas as pastas)"
        } else {
            "2. GALERIA DA PASTA '$selectedAlbum' (${galleryItems.size} fotos)"
        }

        sb.append("<div class='card'>")
        sb.append("<h3>$titleGallery</h3>")
        sb.append("<div>")
        sb.append("<label style='font-weight:bold;font-size:16px;margin-right:16px;'><input type='checkbox' class='chk' onclick='toggleAll(this)'> SELECIONAR TODAS</label>")
        sb.append("<button class='btn btn-amber' onclick='downloadSelected()'>⬇ BAIXAR FOTOS SELECIONADAS (.ZIP)</button>")
        if (!selectedAlbum.isNullOrBlank()) {
            val encAlb = URLEncoder.encode(selectedAlbum, "UTF-8")
            sb.append("<a class='btn' href='/api/zip?album=$encAlb'>⬇ BAIXAR ESTA PASTA INTEIRA (.ZIP)</a>")
        }
        sb.append("</div>")

        sb.append("<div class='grid'>")
        for ((albPath, file) in galleryItems) {
            val encAlb = URLEncoder.encode(albPath, "UTF-8")
            val encFile = URLEncoder.encode(file.name, "UTF-8")
            val itemValue = "$albPath/${file.name}"
            sb.append("<div class='thumb-card'>")
            sb.append("<a target='_blank' href='/api/file?album=$encAlb&name=$encFile'>")
            sb.append("<img loading='lazy' src='/api/thumb?album=$encAlb&name=$encFile' alt='${file.name}'>")
            sb.append("</a>")
            sb.append("<div style='margin-top:8px;text-align:left;font-size:12px;font-weight:bold;word-break:break-all;'>")
            sb.append("<label><input type='checkbox' class='chk photo-chk' value=\"$itemValue\"> ${file.name}</label>")
            sb.append("<div style='color:#555;margin-top:4px;'>📁 $albPath (${file.length() / 1024} KB)</div>")
            sb.append("</div></div>")
        }
        sb.append("</div></div></body></html>")
        return sb.toString()
    }

    private fun sendThumbnailResponse(out: OutputStream, file: File) {
        try {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
            var sampleSize = 1
            val maxDim = 320
            while (boundsOpts.outWidth / sampleSize > maxDim || boundsOpts.outHeight / sampleSize > maxDim) {
                sampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bmp = BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
            if (bmp != null) {
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 72, baos)
                bmp.recycle()
                val bytes = baos.toByteArray()
                val header = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: image/jpeg\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Cache-Control: max-age=3600\r\n" +
                        "Connection: close\r\n\r\n"
                out.write(header.toByteArray(Charsets.UTF_8))
                out.write(bytes)
                out.flush()
                return
            }
        } catch (_: Exception) {
        }
        sendFileResponse(out, file)
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

    private fun sendSelectedZipStreamResponse(out: OutputStream, rootDir: File, selectedItems: List<String>) {
        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/zip\r\n" +
                "Content-Disposition: attachment; filename=\"CamFLC_Selecionadas.zip\"\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))

        ZipOutputStream(BufferedOutputStream(out)).use { zos ->
            val buf = ByteArray(16 * 1024)
            val addedEntries = mutableSetOf<String>()
            for (relItem in selectedItems) {
                val cleanRel = StorageEngine.sanitizeRelativePath(relItem)
                if (cleanRel.isEmpty() || !addedEntries.add(cleanRel)) continue
                val file = File(rootDir, cleanRel)
                if (file.exists() && file.isFile) {
                    zos.putNextEntry(ZipEntry(cleanRel))
                    FileInputStream(file).use { fis ->
                        var len: Int
                        while (fis.read(buf).also { len = it } != -1) {
                            zos.write(buf, 0, len)
                        }
                    }
                    zos.closeEntry()
                }
            }
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
    // CLIENTE LAN: CONSULTA DE GALERIA, MINIATURAS E TRANSFERENCIA SELETIVA
    // =========================================================================

    suspend fun fetchRemoteDeviceInfo(peerIp: String, peerPort: Int = HTTP_PORT): Result<Pair<String, List<AlbumInfo>>> =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("http://$peerIp:$peerPort/api/info")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 8000
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

    // Busca fotos de uma pasta especifica OU de todas as pastas (Modo Galeria Geral quando albumRelativePath == null)
    suspend fun fetchRemoteGalleryFiles(
        peerIp: String,
        peerPort: Int = HTTP_PORT,
        albumRelativePath: String?
    ): Result<List<RemoteFileInfo>> = withContext(Dispatchers.IO) {
        try {
            val endpoint = if (albumRelativePath == null) {
                "http://$peerIp:$peerPort/api/all_files"
            } else {
                val encAlbum = URLEncoder.encode(albumRelativePath, "UTF-8")
                "http://$peerIp:$peerPort/api/files?album=$encAlbum"
            }
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 8000
                requestMethod = "GET"
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(text)
            val arr = json.optJSONArray("files") ?: JSONArray()
            val files = mutableListOf<RemoteFileInfo>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                files.add(
                    RemoteFileInfo(
                        albumRelativePath = o.optString("album", albumRelativePath.orEmpty()),
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

    // Baixa a miniatura leve (~25KB) de uma foto remota para exibir na grade do celular
    suspend fun fetchRemoteThumbnailBitmap(
        peerIp: String,
        peerPort: Int = HTTP_PORT,
        albumRelativePath: String,
        fileName: String
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val encAlbum = URLEncoder.encode(albumRelativePath, "UTF-8")
            val encName = URLEncoder.encode(fileName, "UTF-8")
            val url = URL("http://$peerIp:$peerPort/api/thumb?album=$encAlbum&name=$encName")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 6000
            }
            conn.inputStream.use { input ->
                val bytes = input.readBytes()
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (_: Exception) {
            null
        }
    }

    // Copia ou Transfere (Move) uma lista de fotos selecionadas (ou de uma pasta inteira) mantendo as respectivas pastas
    suspend fun syncSelectedFilesFromPeer(
        peerIp: String,
        peerPort: Int = HTTP_PORT,
        selectedFiles: List<RemoteFileInfo>,
        deleteRemoteAfterTransfer: Boolean,
        onProgress: (String) -> Unit
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val localBase = getBaseDirectory()
            val localRoot = StorageEngine.resolveRootDir(localBase, createIfMissing = true)
            var copiedCount = 0

            for ((index, remoteFile) in selectedFiles.withIndex()) {
                withContext(Dispatchers.Main) {
                    onProgress("Transferindo (${index + 1}/${selectedFiles.size}): ${remoteFile.name}...")
                }

                val targetAlbumDir = File(
                    localRoot,
                    StorageEngine.sanitizeRelativePath(remoteFile.albumRelativePath)
                )
                if (!targetAlbumDir.exists()) targetAlbumDir.mkdirs()

                val localFile = File(targetAlbumDir, StorageEngine.sanitizeSingleName(remoteFile.name))
                val encAlbum = URLEncoder.encode(remoteFile.albumRelativePath, "UTF-8")
                val encName = URLEncoder.encode(remoteFile.name, "UTF-8")

                if (!localFile.exists() || localFile.length() != remoteFile.sizeBytes) {
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

                if (deleteRemoteAfterTransfer && localFile.exists() && localFile.length() > 0) {
                    val delUrl = URL("http://$peerIp:$peerPort/api/delete_file?album=$encAlbum&name=$encName")
                    runCatching {
                        (delUrl.openConnection() as HttpURLConnection).inputStream.close()
                    }
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
