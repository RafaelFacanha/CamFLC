package com.camflc.app

import android.util.LruCache
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.util.Locale

private val remoteThumbCache = object : LruCache<String, ImageBitmap>(140) {}

@Composable
fun LanScreen(
    lanEngine: LanEngine,
    isDarkMode: Boolean,
    pairedPeers: List<PeerDevice>,
    discoveredPeers: List<PeerDevice>,
    onSavePairedPeer: (String, String) -> Unit,
    onRemovePairedPeer: (String) -> Unit,
    onTriggerRadarScan: () -> Unit,
    vibrateFeedback: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    // Paleta adaptativa (Modo Sol Claro vs Modo Escuro OLED)
    val bgColor = if (isDarkMode) Color(0xFF0B0F19) else SolarWhite
    val cardColor = if (isDarkMode) Color(0xFF1E293B) else SolarSurface
    val textColor = if (isDarkMode) SolarWhite else SolarBlack
    val subTextColor = if (isDarkMode) Color(0xFFCBD5E1) else Color(0xFF334155)
    val borderColor = if (isDarkMode) Color(0xFF475569) else SolarBlack

    var localIp by remember { mutableStateOf(lanEngine.getLocalIpAddress()) }
    var manualIpInput by remember { mutableStateOf("") }

    // Estado do Dispositivo Remoto Conectado
    var selectedPeer by remember { mutableStateOf<PeerDevice?>(null) }
    var remotePeerRealName by remember { mutableStateOf("") }
    var remoteAlbums by remember { mutableStateOf<List<AlbumInfo>>(emptyList()) }

    // Filtro de pasta e barra de pesquisa nos ficheiros remotos
    var activeRemoteAlbumFilter by remember { mutableStateOf<String?>(null) }
    var remoteSearchQuery by remember { mutableStateOf("") }
    var remoteGalleryFiles by remember { mutableStateOf<List<RemoteFileInfo>>(emptyList()) }
    var selectedRemoteKeys by remember { mutableStateOf<Set<String>>(emptySet()) }

    var isLoadingRemote by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }
    var statusLog by remember {
        mutableStateOf("Toque num dispositivo encontrado ou digite o endereço acima no navegador do PC.")
    }

    // Lista 100% Estavel ordenada por IP
    val stablePeersList = remember(pairedPeers, discoveredPeers) {
        val map = linkedMapOf<String, PeerDevice>()
        pairedPeers.forEach { map[it.ip] = it.copy(isPaired = true) }
        discoveredPeers.forEach { d ->
            val existing = map[d.ip]
            if (existing != null) {
                map[d.ip] = existing.copy(name = d.name)
            } else {
                map[d.ip] = d
            }
        }
        map.values.sortedBy { it.ip }
    }

    // Filtra as fotos remotas em tempo real pelo texto digitado na pesquisa
    val filteredRemoteFiles = remember(remoteGalleryFiles, remoteSearchQuery) {
        val q = remoteSearchQuery.trim().lowercase(Locale.getDefault())
        if (q.isEmpty()) {
            remoteGalleryFiles
        } else {
            remoteGalleryFiles.filter {
                it.name.lowercase(Locale.getDefault()).contains(q) ||
                        it.albumRelativePath.lowercase(Locale.getDefault()).contains(q)
            }
        }
    }

    val loadRemoteGallery: (PeerDevice, String?) -> Unit = { peer, albumFilter ->
        activeRemoteAlbumFilter = albumFilter
        isLoadingRemote = true
        coroutineScope.launch {
            val filesRes = lanEngine.fetchRemoteGalleryFiles(peer.ip, peer.port, albumFilter)
            isLoadingRemote = false
            filesRes.onSuccess { files ->
                remoteGalleryFiles = files
                selectedRemoteKeys = emptySet()
                val label = albumFilter ?: "Todas as pastas"
                statusLog = "✓ ${files.size} fotos carregadas de '$label'."
            }.onFailure { err ->
                remoteGalleryFiles = emptyList()
                statusLog = "Erro ao listar fotos remotas: ${err.message}"
            }
        }
    }

    val connectAndOpenPeerGallery: (PeerDevice) -> Unit = { peer ->
        selectedPeer = peer
        remoteSearchQuery = ""
        isLoadingRemote = true
        coroutineScope.launch {
            statusLog = "A ligar a ${peer.name} (${peer.ip}:${peer.port})..."
            val infoRes = lanEngine.fetchRemoteDeviceInfo(peer.ip, peer.port)
            infoRes.onSuccess { (realName, albums) ->
                remotePeerRealName = realName
                remoteAlbums = albums
                vibrateFeedback()
                loadRemoteGallery(peer, null)
            }.onFailure {
                isLoadingRemote = false
                remoteAlbums = emptyList()
                remoteGalleryFiles = emptyList()
                statusLog = "Não foi possível ligar a ${peer.ip}. Verifique se o CamFLC está aberto no outro aparelho."
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. CARTAO DE ACESSO RAPIDO VIA NAVEGADOR (PC / NOTEBOOK)
        Surface(
            color = Color(0xFF0B0F19),
            border = BorderStroke(2.dp, SolarAmber),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Computer, contentDescription = null, tint = SolarAmber)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "ACESSO VIA NAVEGADOR (PC)",
                            color = SolarAmber,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            localIp = lanEngine.getLocalIpAddress()
                            onTriggerRadarScan()
                            vibrateFeedback()
                        },
                        border = BorderStroke(1.dp, SolarAmber),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("ATUALIZAR IP", color = SolarAmber, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }

                Text(
                    text = "http://$localIp:${LanEngine.HTTP_PORT}",
                    color = SolarGreen,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "Digite o endereço acima no navegador do PC para abrir a Galeria Web com pesquisa, filtro e download em .ZIP.",
                    color = SolarWhite,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 2. LISTA ESTAVEL DE DISPOSITIVOS NA REDE LOCAL
        Card(
            colors = CardDefaults.cardColors(containerColor = cardColor),
            border = BorderStroke(1.5.dp, borderColor),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "DISPOSITIVOS NA REDE (${stablePeersList.size})",
                        color = textColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Button(
                        onClick = {
                            localIp = lanEngine.getLocalIpAddress()
                            onTriggerRadarScan()
                            vibrateFeedback()
                            statusLog = "A procurar dispositivos CamFLC na rede local..."
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SolarAmber, contentColor = SolarBlack),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(Icons.Default.Radar, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("PROCURAR", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }

                if (stablePeersList.isEmpty()) {
                    Text(
                        text = "Nenhum outro telemóvel detetado ainda. Certifique-se de que estão no mesmo Wi-Fi/Ponto de Acesso ou ligue por IP abaixo.",
                        color = subTextColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    stablePeersList.forEach { peer ->
                        key(peer.ip) {
                            val isSelected = selectedPeer?.ip == peer.ip
                            Surface(
                                color = if (isSelected) SolarAmber else bgColor,
                                border = BorderStroke(if (isSelected) 2.5.dp else 1.5.dp, if (isSelected) SolarBlack else borderColor),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { connectAndOpenPeerGallery(peer) }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.padding(12.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Smartphone,
                                            contentDescription = null,
                                            tint = if (isSelected) SolarBlack else SolarAmber
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = peer.name,
                                                color = if (isSelected) SolarBlack else textColor,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                            Text(
                                                text = "IP: ${peer.ip} • Toque para gerir ficheiros",
                                                color = if (isSelected) SolarBlack else subTextColor,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    if (!peer.isPaired) {
                                        OutlinedButton(
                                            onClick = {
                                                onSavePairedPeer(peer.name, peer.ip)
                                                vibrateFeedback()
                                            },
                                            border = BorderStroke(1.dp, if (isSelected) SolarBlack else borderColor),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Text(
                                                "FIXAR",
                                                color = if (isSelected) SolarBlack else textColor,
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = 11.sp
                                            )
                                        }
                                    } else {
                                        IconButton(onClick = { onRemovePairedPeer(peer.ip) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Remover", tint = SolarRed)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Conexao manual rapida por IP
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = manualIpInput,
                        onValueChange = { manualIpInput = it },
                        placeholder = { Text("IP Manual (ex: 192.168.1.20)", color = subTextColor, fontWeight = FontWeight.Bold) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val cleanIp = manualIpInput.trim()
                            if (cleanIp.isNotEmpty()) {
                                val name = "Dispositivo $cleanIp"
                                onSavePairedPeer(name, cleanIp)
                                val peer = PeerDevice(name = name, ip = cleanIp, isPaired = true)
                                manualIpInput = ""
                                connectAndOpenPeerGallery(peer)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SolarBlack, contentColor = SolarAmber),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(54.dp)
                    ) {
                        Text("LIGAR", fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }

        // BARRA DE STATUS DE SINCRONIZACAO
        Surface(
            color = SolarBlack,
            border = BorderStroke(1.dp, SolarGreen),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = statusLog,
                color = SolarGreen,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(12.dp)
            )
        }

        // 3. EXPLORADOR REMOTO COM PESQUISA, FILTRO DE PASTAS E SELECAO EM MINIATURA
        selectedPeer?.let { peer ->
            Card(
                colors = CardDefaults.cardColors(containerColor = cardColor),
                border = BorderStroke(2.dp, SolarAmber),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "ARQUIVOS EM '${remotePeerRealName.ifEmpty { peer.name }}'",
                                color = textColor,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = "Exibindo ${filteredRemoteFiles.size} fotos • ${selectedRemoteKeys.size} marcadas",
                                color = subTextColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        IconButton(onClick = { loadRemoteGallery(peer, activeRemoteAlbumFilter) }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Atualizar", tint = textColor)
                        }
                    }

                    // Barra de Pesquisa nos Ficheiros Remotos
                    OutlinedTextField(
                        value = remoteSearchQuery,
                        onValueChange = { remoteSearchQuery = it },
                        placeholder = {
                            Text("Pesquisar foto ou prefixo neste dispositivo...", color = subTextColor, fontWeight = FontWeight.Bold)
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, tint = textColor)
                        },
                        trailingIcon = {
                            if (remoteSearchQuery.isNotEmpty()) {
                                IconButton(onClick = { remoteSearchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Limpar", tint = textColor)
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Carrossel de Filtro de Pastas Remotas
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            val isAllSelected = activeRemoteAlbumFilter == null
                            Surface(
                                color = if (isAllSelected) SolarAmber else bgColor,
                                border = BorderStroke(1.5.dp, if (isAllSelected) SolarBlack else borderColor),
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier
                                    .height(38.dp)
                                    .clickable { loadRemoteGallery(peer, null) }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                ) {
                                    Icon(
                                        Icons.Default.GridView,
                                        contentDescription = null,
                                        tint = if (isAllSelected) SolarBlack else textColor,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Todas",
                                        color = if (isAllSelected) SolarBlack else textColor,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }

                        items(remoteAlbums) { album ->
                            val isSelected = activeRemoteAlbumFilter == album.relativePath
                            Surface(
                                color = if (isSelected) SolarAmber else bgColor,
                                border = BorderStroke(1.5.dp, if (isSelected) SolarBlack else borderColor),
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier
                                    .height(38.dp)
                                    .clickable { loadRemoteGallery(peer, album.relativePath) }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = if (isSelected) SolarBlack else SolarAmber,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "${album.relativePath} (${album.fileCount})",
                                        color = if (isSelected) SolarBlack else textColor,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }

                    // Selecao Rapida (Marcar Visiveis / Limpar)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = {
                                selectedRemoteKeys = filteredRemoteFiles.map { it.uniqueKey }.toSet()
                                vibrateFeedback()
                            },
                            border = BorderStroke(1.dp, borderColor),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.SelectAll, contentDescription = null, tint = textColor, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Marcar Visíveis (${filteredRemoteFiles.size})", color = textColor, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                selectedRemoteKeys = emptySet()
                                vibrateFeedback()
                            },
                            border = BorderStroke(1.dp, borderColor),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ClearAll, contentDescription = null, tint = textColor, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Limpar Seleção", color = textColor, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
                        }
                    }

                    // Botoes de Transferencia: COPIAR vs TRANSFERIR (MOVER)
                    val filesToAct = remember(remoteGalleryFiles, selectedRemoteKeys) {
                        remoteGalleryFiles.filter { selectedRemoteKeys.contains(it.uniqueKey) }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = {
                                if (!isSyncing && filesToAct.isNotEmpty()) {
                                    isSyncing = true
                                    coroutineScope.launch {
                                        val res = lanEngine.syncSelectedFilesFromPeer(
                                            peerIp = peer.ip,
                                            peerPort = peer.port,
                                            selectedFiles = filesToAct,
                                            deleteRemoteAfterTransfer = false,
                                            onProgress = { statusLog = it }
                                        )
                                        isSyncing = false
                                        vibrateFeedback()
                                        res.onSuccess { count ->
                                            statusLog = "✓ $count fotos COPIADAS para este aparelho!"
                                        }.onFailure {
                                            statusLog = "Erro ao copiar: ${it.message}"
                                        }
                                    }
                                } else if (filesToAct.isEmpty()) {
                                    statusLog = "Toque nas miniaturas abaixo para selecionar pelo menos 1 foto!"
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SolarAmber, contentColor = SolarBlack),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("COPIAR (${filesToAct.size})", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                        }

                        Button(
                            onClick = {
                                if (!isSyncing && filesToAct.isNotEmpty()) {
                                    isSyncing = true
                                    coroutineScope.launch {
                                        val res = lanEngine.syncSelectedFilesFromPeer(
                                            peerIp = peer.ip,
                                            peerPort = peer.port,
                                            selectedFiles = filesToAct,
                                            deleteRemoteAfterTransfer = true,
                                            onProgress = { statusLog = it }
                                        )
                                        isSyncing = false
                                        vibrateFeedback()
                                        res.onSuccess { count ->
                                            statusLog = "✓ $count fotos TRANSFERIDAS (movidas) com sucesso!"
                                            loadRemoteGallery(peer, activeRemoteAlbumFilter)
                                        }.onFailure {
                                            statusLog = "Erro ao transferir: ${it.message}"
                                        }
                                    }
                                } else if (filesToAct.isEmpty()) {
                                    statusLog = "Toque nas miniaturas abaixo para selecionar pelo menos 1 foto!"
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SolarGreen, contentColor = SolarBlack),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                        ) {
                            Icon(Icons.Default.DriveFileMove, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("MOVER (${filesToAct.size})", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                        }
                    }

                    // Grelha de Miniaturas Remotas (2 Colunas)
                    if (isLoadingRemote) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp)
                        ) {
                            CircularProgressIndicator(color = SolarAmber)
                        }
                    } else if (filteredRemoteFiles.isEmpty()) {
                        Text(
                            text = "Nenhuma foto encontrada para o filtro atual.",
                            color = subTextColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        val rows = filteredRemoteFiles.chunked(2)
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            rows.forEach { pair ->
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    pair.forEach { fileItem ->
                                        val isChecked = selectedRemoteKeys.contains(fileItem.uniqueKey)
                                        RemoteThumbnailCard(
                                            lanEngine = lanEngine,
                                            peerIp = peer.ip,
                                            peerPort = peer.port,
                                            fileInfo = fileItem,
                                            isChecked = isChecked,
                                            isDarkMode = isDarkMode,
                                            onToggleCheck = {
                                                selectedRemoteKeys = if (isChecked) {
                                                    selectedRemoteKeys - fileItem.uniqueKey
                                                } else {
                                                    selectedRemoteKeys + fileItem.uniqueKey
                                                }
                                                vibrateFeedback()
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    if (pair.size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RemoteThumbnailCard(
    lanEngine: LanEngine,
    peerIp: String,
    peerPort: Int,
    fileInfo: RemoteFileInfo,
    isChecked: Boolean,
    isDarkMode: Boolean,
    onToggleCheck: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardBg = when {
        isChecked -> SolarAmber
        isDarkMode -> Color(0xFF0B0F19)
        else -> SolarWhite
    }
    val textColor = if (isChecked) SolarBlack else if (isDarkMode) SolarWhite else SolarBlack
    val borderColor = if (isChecked) SolarBlack else if (isDarkMode) Color(0xFF475569) else SolarBlack

    val cacheKey = "$peerIp:${fileInfo.uniqueKey}"
    val thumbState by produceState<ImageBitmap?>(initialValue = remoteThumbCache.get(cacheKey), cacheKey) {
        if (value == null) {
            val bmp = lanEngine.fetchRemoteThumbnailBitmap(
                peerIp = peerIp,
                peerPort = peerPort,
                albumRelativePath = fileInfo.albumRelativePath,
                fileName = fileInfo.name
            )
            if (bmp != null) {
                val imgBmp = bmp.asImageBitmap()
                remoteThumbCache.put(cacheKey, imgBmp)
                value = imgBmp
            }
        }
    }

    Surface(
        color = cardBg,
        border = BorderStroke(if (isChecked) 3.dp else 1.5.dp, borderColor),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.clickable { onToggleCheck() }
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SolarBlack)
            ) {
                if (thumbState != null) {
                    Image(
                        bitmap = thumbState!!,
                        contentDescription = fileInfo.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(Icons.Default.Image, contentDescription = null, tint = SolarAmber, modifier = Modifier.size(32.dp))
                    }
                }

                Surface(
                    color = if (isChecked) SolarGreen else SolarBlack.copy(alpha = 0.75f),
                    border = BorderStroke(1.5.dp, SolarWhite),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                ) {
                    Icon(
                        imageVector = if (isChecked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                        contentDescription = null,
                        tint = if (isChecked) SolarBlack else SolarWhite,
                        modifier = Modifier
                            .padding(2.dp)
                            .size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = fileInfo.name,
                color = textColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "📁 ${fileInfo.albumRelativePath} • ${fileInfo.formattedSize()}",
                color = textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
