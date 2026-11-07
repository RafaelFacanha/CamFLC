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
                            engineScope.launch(Dispatchers.Main) { onStorageChanged() }
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
                            engineScope.launch(Dispatchers.Main) { onStorageChanged() }
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
                            engineScope.launch(Dispatchers.Main) { onStorageChanged() }
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
        sb.append(".folder-item{display:flex;justify-content:space-between;align-items:center;padding:10px 12px;border-radius:10px;text-decoration:none;color:var(--text);font-weight:700;margin-bottom:6px;border:1.5px solid transparent;}")
        sb.append(".folder-item:hover{border-color:var(--amber);}")
        sb.append(".folder-item.active{background:var(--amber);color:#000000;border-color:#000000;}")
        sb.append(".search-box{width:100%;padding:12px 16px;border-radius:10px;border:2px solid var(--border);background:var(--bg);color:var(--text);font-size:15px;font-weight:700;margin-bottom:12px;}")
        sb.append(".toolbar{display:flex;flex-wrap:wrap;gap:8px;align-items:center;justify-content:space-between;padding-bottom:12px;border-bottom:2px solid var(--border);margin-bottom:14px;}")
        sb.append(".btn{display:inline-flex;align-items:center;gap:6px;background:#0F172A;color:var(--amber);padding:10px 14px;text-decoration:none;font-weight:800;font-size:13px;border-radius:10px;border:2px solid #0F172A;cursor:pointer;}")
        sb.append(".btn-amber{background:var(--amber);color:#000000;border:2px solid #000000;}")
        sb.append(".btn-green{background:var(--green);color:#000000;border:2px solid #000000;}")
        sb.append(".btn-red{background:var(--red);color:#FFFFFF;border:2px solid #000000;}")
        sb.append(".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(190px,1fr));gap:14px;}")
        sb.append(".photo-card{background:var(--bg);border:2px solid var(--border);border-radius:12px;padding:8px;position:relative;transition:transform .1s;}")
        sb.append(".photo-card.selected{border:3px solid var(--green);background:rgba(0,200,83,0.12);}")
        sb.append(".thumb-wrap{position:relative;width:100%;height:145px;background:#000;border-radius:8px;overflow:hidden;cursor:pointer;}")
        sb.append(".thumb-wrap img{width:100%;height:100%;object-fit:cover;}")
        sb.append(".chk{transform:scale(1.5);cursor:pointer;accent-color:var(--green);}")
        sb.append(".modal{display:none;position:fixed;inset:0;background:rgba(0,0,0,0.92);z-index:999;align-items:center;justify-content:center;padding:20px;flex-direction:column;}")
        sb.append(".modal img{max-width:92vw;max-height:76vh;border-radius:10px;border:2px solid var(--amber);}")
        sb.append("</style>")

        sb.append("<script>")
        sb.append("function initTheme(){if(localStorage.getItem('camflc_theme')==='dark'){document.body.classList.add('dark');}}")
        sb.append("function toggleTheme(){document.body.classList.toggle('dark');localStorage.setItem('camflc_theme',document.body.classList.contains('dark')?'dark':'light');}")
        sb.append("function filterPhotos(){const q=document.getElementById('searchInput').value.toLowerCase();const cards=document.querySelectorAll('.photo-card');let visible=0;cards.forEach(c=>{const match=c.dataset.search.includes(q);c.style.display=match?'block':'none';if(match)visible++;});document.getElementById('visibleCount').innerText=visible;}")
        sb.append("function updateSelectionCount(){const all=document.querySelectorAll('.photo-chk');let sel=0;all.forEach(x=>{const card=x.closest('.photo-card');if(x.checked){sel++;card.classList.add('selected');}else{card.classList.remove('selected');}});document.getElementById('selCount').innerText=sel;}")
        sb.append("function toggleVisible(state){const cards=document.querySelectorAll('.photo-card');cards.forEach(c=>{if(c.style.display!=='none'){const chk=c.querySelector('.photo-chk');if(chk)chk.checked=state;}});updateSelectionCount();}")
        sb.append("function getSelectedItems(){const c=document.querySelectorAll('.photo-chk:checked');const arr=[];c.forEach(x=>arr.push(x.value));return arr;}")
        sb.append("function downloadSelected(){const arr=getSelectedItems();if(arr.length===0){alert('Selecione pelo menos 1 foto na grelha!');return;}window.location.href='/api/zip_selected?items='+encodeURIComponent(arr.join('|'));}")
        sb.append("function deleteSelected(){const arr=getSelectedItems();if(arr.length===0){alert('Selecione pelo menos 1 foto!');return;}if(confirm('Eliminar permanentemente '+arr.length+' foto(s) selecionada(s)?')){window.location.href='/api/delete_selected?web=1&album=$encCurrentAlbum&items='+encodeURIComponent(arr.join('|'));}}")
        sb.append("function openLightbox(url,title,sub){document.getElementById('lbImg').src=url;document.getElementById('lbTitle').innerText=title;document.getElementById('lbSub').innerText=sub;document.getElementById('lbDownload').href=url;document.getElementById('lightbox').style.display='flex';}")
        sb.append("function closeLightbox(){document.getElementById('lightbox').style.display='none';document.getElementById('lbImg').src='';}")
        sb.append("</script></head><body onload='initTheme()'>")

        // Cabecalho Superior
        sb.append("<div class='topbar'>")
        sb.append("<div><h2 style='margin:0;color:var(--amber);'>📷 CamFLC Web • ${getDeviceName()}</h2>")
        sb.append("<div style='font-size:13px;color:#CBD5E1;font-weight:700;'>Raiz: /storage/emulated/0/$baseDir • $totalAllPhotos fotos indexadas</div></div>")
        sb.append("<div style='display:flex;gap:8px;flex-wrap:wrap;'>")
        sb.append("<button class='btn btn-amber' onclick='toggleTheme()'>☀️/🌙 Alternar Tema</button>")
        sb.append("<a class='btn btn-green' href='/api/zip?album='>⬇️ Transferir Tudo (.ZIP)</a>")
        sb.append("</div></div>")

        // Layout em 2 Colunas (Pastas na Esquerda | Galeria e Pesquisa na Direita)
        sb.append("<div class='layout'>")

        // Coluna 1: Filtro de Pastas e Subpastas
        sb.append("<div class='panel'><h3 style='margin-top:0;'>📁 PASTAS (${albums.size})</h3>")
        val allActiveClass = if (selectedAlbum.isNullOrBlank()) "folder-item active" else "folder-item"
        sb.append("<a class='$allActiveClass' href='/'><span>🖼️ Todas as Fotos</span><span>$totalAllPhotos</span></a>")

        for (album in albums) {
            val enc = URLEncoder.encode(album.relativePath, "UTF-8")
            val isAct = album.relativePath == selectedAlbum
            val cls = if (isAct) "folder-item active" else "folder-item"
            val icon = if (album.relativePath.contains("/")) "↳ 📂" else "📁"
            sb.append("<a class='$cls' href='/?album=$enc'><span>$icon ${album.relativePath}</span><span>${album.fileCount}</span></a>")
        }

        if (!selectedAlbum.isNullOrBlank()) {
            sb.append("<hr style='border-color:var(--border);margin:14px 0;'>")
            sb.append("<a class='btn btn-amber' style='width:100%;justify-content:center;' href='/api/zip?album=$encCurrentAlbum'>⬇️ Baixar Pasta '$selectedAlbum' (.ZIP)</a>")
        }
        sb.append("</div>")

        // Coluna 2: Pesquisa + Barra de Acoes em Lote + Grelha de Miniaturas
        sb.append("<div class='panel'>")
        sb.append("<input id='searchInput' type='text' class='search-box' oninput='filterPhotos()' placeholder='🔍 Pesquisar instantaneamente por prefixo, nome do ficheiro ou pasta...'>")

        sb.append("<div class='toolbar'>")
        sb.append("<div style='display:flex;gap:8px;flex-wrap:wrap;align-items:center;'>")
        sb.append("<button class='btn' onclick='toggleVisible(true)'>☑️ Marcar Visíveis</button>")
        sb.append("<button class='btn' onclick='toggleVisible(false)'>⬜ Limpar</button>")
        sb.append("<span style='font-weight:800;font-size:13px;'>Exibindo <span id='visibleCount'>${galleryItems.size}</span> • Selecionadas: <span id='selCount'>0</span></span>")
        sb.append("</div>")

        sb.append("<div style='display:flex;gap:8px;flex-wrap:wrap;'>")
        sb.append("<button class='btn btn-amber' onclick='downloadSelected()'>⬇️ Baixar Selecionadas (.ZIP)</button>")
        if (isRemoteDeleteAllowed()) {
            sb.append("<button class='btn btn-red' onclick='deleteSelected()'>🗑️ Excluir Selecionadas</button>")
        }
        sb.append("</div></div>")

        sb.append("<div class='grid'>")
        for ((albPath, file) in galleryItems) {
            val encAlb = URLEncoder.encode(albPath, "UTF-8")
            val encFile = URLEncoder.encode(file.name, "UTF-8")
            val itemValue = "$albPath/${file.name}"
            val fullUrl = "/api/file?album=$encAlb&name=$encFile"
            val thumbUrl = "/api/thumb?album=$encAlb&name=$encFile"
            val kbSize = "${file.length() / 1024} KB"
            val searchTokens = "${file.name} $albPath".lowercase(Locale.getDefault())

            sb.append("<div class='photo-card' data-search=\"$searchTokens\">")
            sb.append("<div class='thumb-wrap' onclick=\"openLightbox('$fullUrl','${file.name}','Pasta: $albPath • $kbSize')\">")
            sb.append("<img loading='lazy' src='$thumbUrl' alt='${file.name}'>")
            sb.append("</div>")
            sb.append("<div style='margin-top:8px;display:flex;justify-content:space-between;align-items:flex-start;gap:6px;'>")
            sb.append("<label style='font-size:12px;font-weight:800;word-break:break-all;cursor:pointer;flex:1;'>")
            sb.append("<input type='checkbox' class='chk photo-chk' value=\"$itemValue\" onchange='updateSelectionCount()'> ${file.name}")
            sb.append("</label></div>")
            sb.append("<div style='display:flex;justify-content:space-between;align-items:center;margin-top:6px;font-size:11px;color:var(--sub);font-weight:700;'>")
            sb.append("<span>📁 $albPath ($kbSize)</span>")
            sb.append("<a href='$fullUrl' download='${file.name}' style='text-decoration:none;font-size:14px;' title='Baixar foto'>⬇️</a>")
            sb.append("</div></div>")
        }
        sb.append("</div></div></div>")

        // Modal Lightbox de Visualizacao em Alta Resolucao
        sb.append("<div id='lightbox' class='modal' onclick='closeLightbox()'>")
        sb.append("<img id='lbImg' src='' onclick='event.stopPropagation()'>")
        sb.append("<div style='color:#FFF;text-align:center;margin-top:12px;' onclick='event.stopPropagation()'>")
        sb.append("<div id='lbTitle' style='font-size:16px;font-weight:800;color:var(--amber);'></div>")
        sb.append("<div id='lbSub' style='font-size:13px;margin-bottom:12px;'></div>")
        sb.append("<a id='lbDownload' class='btn btn-amber' href='' download>⬇️ Guardar Imagem Original</a> ")
        sb.append("<button class='btn' onclick='closeLightbox()'>✕ Fechar</button>")
        sb.append("</div></div>")

        sb.append("</body></html>")
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
    // CLIENTE LAN: CONSULTA DE GALERIA REMOTA E TRANSFERENCIA SELETIVA
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
                    onProgress("A transferir (${index + 1}/${selectedFiles.size}): ${remoteFile.name}...")
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
        return "Ative o Wi-Fi ou Ponto de Acesso"
    }
}
