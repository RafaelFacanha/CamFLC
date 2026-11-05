package com.camflc.app

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// Cache em memoria RAM para que as miniaturas nao precisem recarregar ao rolar a tela
private val thumbMemoryCache = object : LruCache<String, ImageBitmap>(120) {}

@Composable
fun LanScreen(
    lanEngine: LanEngine,
    baseDirectory: String,
    localAlbumsList: List<AlbumInfo>,
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    allowRemoteDelete: Boolean,
    onToggleRemoteDelete: (Boolean) -> Unit,
    pairedPeers: List<PeerDevice>,
    discoveredPeers: List<PeerDevice>,
    onSavePairedPeer: (String, String) -> Unit,
    onRemovePairedPeer: (String) -> Unit,
    onTriggerRadarScan: () -> Unit,
    vibrateFeedback: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    var localIp by remember { mutableStateOf(lanEngine.getLocalIpAddress()) }
    var manualIpInput by remember { mutableStateOf("") }
    var manualNameInput by remember { mutableStateOf("") }

    // Alterna entre Galeria Local deste celular (0) e Dispositivos na Rede (1)
    var viewMode by remember { mutableIntStateOf(1) }
    var selectedLocalAlbumFilter by remember { mutableStateOf<String?>(null) }

    // Estado do Dispositivo Remoto Conectado
    var selectedPeer by remember { mutableStateOf<PeerDevice?>(null) }
    var remotePeerRealName by remember { mutableStateOf("") }
    var remoteAlbums by remember { mutableStateOf<List<AlbumInfo>>(emptyList()) }

    // null = Modo Galeria Geral (Todas as fotos) | String = Pasta especifica aberta
    var activeRemoteAlbumFilter by remember { mutableStateOf<String?>(null) }
    var remoteGalleryFiles by remember { mutableStateOf<List<RemoteFileInfo>>(emptyList()) }
    var selectedRemoteKeys by remember { mutableStateOf<Set<String>>(emptySet()) }

    var isLoadingRemote by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }
    var statusLog by remember {
        mutableStateOf("Toque em um dispositivo abaixo para abrir a Galeria de Miniaturas dele.")
    }

    // Lista 100% ESTAVEL (Ordenada por IP para nunca pular nem embaralhar na tela!)
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

    // Carrega as fotos (Modo Galeria Geral ou de uma pasta especifica) do dispositivo remoto
    val loadRemoteGallery: (PeerDevice, String?) -> Unit = { peer, albumFilter ->
        activeRemoteAlbumFilter = albumFilter
        isLoadingRemote = true
        coroutineScope.launch {
            val filesRes = lanEngine.fetchRemoteGalleryFiles(peer.ip, peer.port, albumFilter)
            isLoadingRemote = false
            filesRes.onSuccess { files ->
                remoteGalleryFiles = files
                selectedRemoteKeys = emptySet()
                val label = albumFilter ?: "GALERIA GERAL (Todas as pastas)"
                statusLog = "✓ Exibindo ${files.size} miniaturas em '$label'."
            }.onFailure { err ->
                remoteGalleryFiles = emptyList()
                statusLog = "Erro ao listar fotos: ${err.message}"
            }
        }
    }

    // Conecta ao dispositivo tocado e ja abre o Modo Galeria Geral
    val connectAndOpenPeerGallery: (PeerDevice) -> Unit = { peer ->
        selectedPeer = peer
        isLoadingRemote = true
        coroutineScope.launch {
            statusLog = "Conectando a ${peer.name} (${peer.ip}:${peer.port})..."
            val infoRes = lanEngine.fetchRemoteDeviceInfo(peer.ip, peer.port)
            infoRes.onSuccess { (realName, albums) ->
                remotePeerRealName = realName
                remoteAlbums = albums
                vibrateFeedback()
                loadRemoteGallery(peer, null)
            }.onFailure { err ->
                isLoadingRemote = false
                remoteAlbums = emptyList()
                remoteGalleryFiles = emptyList()
                statusLog = "Falha ao conectar em ${peer.ip}: ${err.javaClass.simpleName} - Verifique se ambos instalaram esta atualização."
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // SELETOR SUPERIOR: DISPOSITIVOS NA REDE vs GALERIA LOCAL DESTE APARELHO
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = { viewMode = 1 },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (viewMode == 1) SolarBlack else SolarSurface,
                    contentColor = if (viewMode == 1) SolarAmber else SolarBlack
                ),
                border = BorderStroke(2.dp, SolarBlack),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(54.dp)
            ) {
                Icon(Icons.Default.Wifi, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("DISPOSITIVOS LAN", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
            }

            Button(
                onClick = { viewMode = 0 },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (viewMode == 0) SolarBlack else SolarSurface,
                    contentColor = if (viewMode == 0) SolarAmber else SolarBlack
                ),
                border = BorderStroke(2.dp, SolarBlack),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(54.dp)
            ) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("MINHA GALERIA LOCAL", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
            }
        }

        if (viewMode == 0) {
            // MODO 0: PRE-VISUALIZACAO GERAL EM GALERIA LOCAL (DESTE PROPRIO APARELHO)
            LocalGalleryPreviewCard(
                baseDirectory = baseDirectory,
                albumsList = localAlbumsList,
                selectedAlbumFilter = selectedLocalAlbumFilter,
                onSelectAlbumFilter = { selectedLocalAlbumFilter = it }
            )
        } else {
            // MODO 1: SERVIDOR LAN + DISPOSITIVOS PAREADOS + GALERIA REMOTA COM SELECAO INDIVIDUAL
            Surface(
                color = SolarBlack,
                shape = RoundedCornerShape(12.dp),
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
                        Text(
                            text = "ACESSO NO PC OU NAVEGADOR:",
                            color = SolarAmber,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
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
                            Text("ATUALIZAR", color = SolarAmber, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                    Text(
                        text = "http://$localIp:${LanEngine.HTTP_PORT}",
                        color = SolarGreen,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            // Lista Estavel de Dispositivos na Rede
            Card(
                colors = CardDefaults.cardColors(containerColor = SolarSurface),
                border = BorderStroke(2.dp, SolarBlack),
                shape = RoundedCornerShape(12.dp),
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
                            text = "1. DISPOSITIVOS NA REDE (${stablePeersList.size})",
                            color = SolarBlack,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Button(
                            onClick = {
                                localIp = lanEngine.getLocalIpAddress()
                                onTriggerRadarScan()
                                vibrateFeedback()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SolarBlack, contentColor = SolarAmber),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Icon(Icons.Default.Radar, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("BUSCAR", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                        }
                    }

                    if (stablePeersList.isEmpty()) {
                        Text(
                            text = "Procurando aparelhos na mesma rede Wi-Fi/Roteador...",
                            color = SolarBlack,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        stablePeersList.forEach { peer ->
                            key(peer.ip) {
                                val isSelected = selectedPeer?.ip == peer.ip
                                Surface(
                                    color = if (isSelected) SolarBlack else SolarWhite,
                                    border = BorderStroke(2.dp, SolarBlack),
                                    shape = RoundedCornerShape(10.dp),
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
                                                tint = if (isSelected) SolarAmber else SolarBlack
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text(
                                                    text = peer.name,
                                                    color = if (isSelected) SolarAmber else SolarBlack,
                                                    fontSize = 16.sp,
                                                    fontWeight = FontWeight.ExtraBold
                                                )
                                                Text(
                                                    text = "IP Fixo na Lista: ${peer.ip} • Toque para abrir Galeria",
                                                    color = if (isSelected) SolarGreen else SolarBlack,
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
                                                border = BorderStroke(1.dp, if (isSelected) SolarAmber else SolarBlack)
                                            ) {
                                                Text(
                                                    "SALVAR",
                                                    color = if (isSelected) SolarAmber else SolarBlack,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontSize = 11.sp
                                                )
                                            }
                                        } else {
                                            IconButton(onClick = { onRemovePairedPeer(peer.ip) }) {
                                                Icon(Icons.Default.Delete, contentDescription = null, tint = SolarRed)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Campo compacto para adicionar IP manualmente ou mudar nome
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = manualIpInput,
                            onValueChange = { manualIpInput = it },
                            label = { Text("Conectar por IP (ex: 192.168.1.20)", fontWeight = FontWeight.Bold) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                val cleanIp = manualIpInput.trim()
                                if (cleanIp.isNotEmpty()) {
                                    val name = manualNameInput.ifBlank { "Aparelho $cleanIp" }
                                    onSavePairedPeer(name, cleanIp)
                                    val peer = PeerDevice(name = name, ip = cleanIp, isPaired = true)
                                    manualIpInput = ""
                                    connectAndOpenPeerGallery(peer)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SolarBlack, contentColor = SolarAmber),
                            modifier = Modifier.height(54.dp)
                        ) {
                            Text("ABRIR", fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            }

            // Barra de Progresso e Status
            Surface(
                color = SolarBlack,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = statusLog,
                    color = SolarGreen,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(12.dp)
                )
            }

            // 2. MODO GALERIA REMOTA COM MINIATURAS E SELECAO INDIVIDUAL DE FOTOS
            selectedPeer?.let { peer ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = SolarSurface),
                    border = BorderStroke(3.dp, SolarBlack),
                    shape = RoundedCornerShape(12.dp),
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
                                    text = "GALERIA DE '${remotePeerRealName.ifEmpty { peer.name }}'",
                                    color = SolarBlack,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(
                                    text = "${selectedRemoteKeys.size} de ${remoteGalleryFiles.size} fotos marcadas",
                                    color = SolarBlack,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            OutlinedButton(
                                onClick = { loadRemoteGallery(peer, activeRemoteAlbumFilter) },
                                border = BorderStroke(2.dp, SolarBlack)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = SolarBlack)
                            }
                        }

                        // Carrossel de Filtro: [TODAS AS FOTOS (GALERIA GERAL)] + [Pastas Individuais]
                        Text(
                            text = "Escolha a visão (Galeria Geral ou Pasta Específica):",
                            color = SolarBlack,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                val isAllSelected = activeRemoteAlbumFilter == null
                                Surface(
                                    color = if (isAllSelected) SolarBlack else SolarWhite,
                                    border = BorderStroke(2.dp, SolarBlack),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .height(46.dp)
                                        .clickable { loadRemoteGallery(peer, null) }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 12.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.GridView,
                                            contentDescription = null,
                                            tint = if (isAllSelected) SolarAmber else SolarBlack
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "TODAS AS FOTOS (GERAL)",
                                            color = if (isAllSelected) SolarAmber else SolarBlack,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            }

                            items(remoteAlbums) { album ->
                                val isSelected = activeRemoteAlbumFilter == album.relativePath
                                Surface(
                                    color = if (isSelected) SolarBlack else SolarWhite,
                                    border = BorderStroke(2.dp, SolarBlack),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .height(46.dp)
                                        .clickable { loadRemoteGallery(peer, album.relativePath) }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 12.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Folder,
                                            contentDescription = null,
                                            tint = if (isSelected) SolarAmber else SolarBlack
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "${album.relativePath} (${album.fileCount})",
                                            color = if (isSelected) SolarAmber else SolarBlack,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            }
                        }

                        // Barra de Selecao Rapida (Selecionar Todas / Desmarcar Todas)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedButton(
                                onClick = {
                                    selectedRemoteKeys = remoteGalleryFiles.map { it.uniqueKey }.toSet()
                                    vibrateFeedback()
                                },
                                border = BorderStroke(2.dp, SolarBlack),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.SelectAll, contentDescription = null, tint = SolarBlack)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("MARCAR TODAS (${remoteGalleryFiles.size})", color = SolarBlack, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                            }

                            OutlinedButton(
                                onClick = {
                                    selectedRemoteKeys = emptySet()
                                    vibrateFeedback()
                                },
                                border = BorderStroke(2.dp, SolarBlack),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.ClearAll, contentDescription = null, tint = SolarBlack)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("LIMPAR SELEÇÃO", color = SolarBlack, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                            }
                        }

                        // Botoes de Acao para as Fotos Selecionadas (Copiar vs Transferir/Mover)
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
                                                statusLog = "✓ $count fotos selecionadas COPIADAS com sucesso!"
                                            }.onFailure {
                                                statusLog = "Erro ao copiar: ${it.message}"
                                            }
                                        }
                                    } else if (filesToAct.isEmpty()) {
                                        statusLog = "Toque nas miniaturas abaixo para marcar pelo menos 1 foto!"
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = SolarAmber, contentColor = SolarBlack),
                                border = BorderStroke(2.dp, SolarBlack),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(54.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "COPIAR (${filesToAct.size})",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 13.sp
                                )
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
                                                statusLog = "✓ $count fotos TRANSFERIDAS (movidas para este aparelho)!"
                                                loadRemoteGallery(peer, activeRemoteAlbumFilter)
                                            }.onFailure {
                                                statusLog = "Erro ao transferir: ${it.message}"
                                            }
                                        }
                                    } else if (filesToAct.isEmpty()) {
                                        statusLog = "Toque nas miniaturas abaixo para marcar pelo menos 1 foto!"
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = SolarGreen, contentColor = SolarBlack),
                                border = BorderStroke(2.dp, SolarBlack),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(54.dp)
                            ) {
                                Icon(Icons.Default.DriveFileMove, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "TRANSFERIR (${filesToAct.size})",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 13.sp
                                )
                            }
                        }

                        // GRADE DE MINIATURAS (2 COLUNAS DE ALTA VISIBILIDADE)
                        if (isLoadingRemote) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp)
                            ) {
                                CircularProgressIndicator(color = SolarBlack)
                            }
                        } else if (remoteGalleryFiles.isEmpty()) {
                            Text(
                                text = "Nenhuma foto encontrada nesta seleção.",
                                color = SolarBlack,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            val rows = remoteGalleryFiles.chunked(2)
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

            // Configuracoes de Nome e Permissao Remota
            Card(
                colors = CardDefaults.cardColors(containerColor = SolarSurface),
                border = BorderStroke(2.dp, SolarBlack),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "CONFIGURAÇÕES DESTE APARELHO NA REDE",
                        color = SolarBlack,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    OutlinedTextField(
                        value = deviceName,
                        onValueChange = onDeviceNameChange,
                        label = { Text("Nome deste Dispositivo", fontWeight = FontWeight.Bold) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Permitir que outros aparelhos movam/excluam fotos daqui",
                            color = SolarBlack,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = allowRemoteDelete,
                            onCheckedChange = onToggleRemoteDelete
                        )
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
    onToggleCheck: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cacheKey = "$peerIp:${fileInfo.uniqueKey}"
    val thumbState by produceState<ImageBitmap?>(initialValue = thumbMemoryCache.get(cacheKey), cacheKey) {
        if (value == null) {
            val bmp = lanEngine.fetchRemoteThumbnailBitmap(
                peerIp = peerIp,
                peerPort = peerPort,
                albumRelativePath = fileInfo.albumRelativePath,
                fileName = fileInfo.name
            )
            if (bmp != null) {
                val imgBmp = bmp.asImageBitmap()
                thumbMemoryCache.put(cacheKey, imgBmp)
                value = imgBmp
            }
        }
    }

    Surface(
        color = if (isChecked) SolarAmber else SolarWhite,
        border = BorderStroke(if (isChecked) 3.dp else 2.dp, SolarBlack),
        shape = RoundedCornerShape(10.dp),
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
                        Icon(Icons.Default.Image, contentDescription = null, tint = SolarAmber, modifier = Modifier.size(36.dp))
                    }
                }

                // Selo de Checkbox de Alta Visibilidade no canto superior direito
                Surface(
                    color = if (isChecked) SolarGreen else SolarBlack.copy(alpha = 0.75f),
                    border = BorderStroke(2.dp, SolarWhite),
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
                            .size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = fileInfo.name,
                color = SolarBlack,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "📁 ${fileInfo.albumRelativePath} • ${fileInfo.formattedSize()}",
                color = SolarBlack,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun LocalGalleryPreviewCard(
    baseDirectory: String,
    albumsList: List<AlbumInfo>,
    selectedAlbumFilter: String?,
    onSelectAlbumFilter: (String?) -> Unit
) {
    val localFiles = remember(baseDirectory, albumsList, selectedAlbumFilter) {
        val rootDir = StorageEngine.resolveRootDir(baseDirectory, createIfMissing = false)
        val result = mutableListOf<Pair<String, File>>()
        val targetAlbums = if (selectedAlbumFilter == null) {
            albumsList
        } else {
            albumsList.filter { it.relativePath == selectedAlbumFilter }
        }
        for (alb in targetAlbums) {
            val dir = File(rootDir, StorageEngine.sanitizeRelativePath(alb.relativePath))
            dir.listFiles { f -> f.isFile }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { f -> result.add(alb.relativePath to f) }
        }
        result
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = SolarSurface),
        border = BorderStroke(2.dp, SolarBlack),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "GALERIA LOCAL DESTE APARELHO (${localFiles.size} fotos)",
                color = SolarBlack,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold
            )

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    val isAll = selectedAlbumFilter == null
                    Surface(
                        color = if (isAll) SolarBlack else SolarWhite,
                        border = BorderStroke(2.dp, SolarBlack),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .height(44.dp)
                            .clickable { onSelectAlbumFilter(null) }
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp)) {
                            Text(
                                text = "TODAS AS PASTAS",
                                color = if (isAll) SolarAmber else SolarBlack,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
                items(albumsList) { alb ->
                    val isSel = selectedAlbumFilter == alb.relativePath
                    Surface(
                        color = if (isSel) SolarBlack else SolarWhite,
                        border = BorderStroke(2.dp, SolarBlack),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .height(44.dp)
                            .clickable { onSelectAlbumFilter(alb.relativePath) }
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp)) {
                            Text(
                                text = "${alb.relativePath} (${alb.fileCount})",
                                color = if (isSel) SolarAmber else SolarBlack,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            if (localFiles.isEmpty()) {
                Text(
                    text = "Nenhuma foto gravada ainda nesta seleção.",
                    color = SolarBlack,
                    fontWeight = FontWeight.Bold
                )
            } else {
                val rows = localFiles.chunked(2)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    rows.forEach { pair ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            pair.forEach { (albumRel, file) ->
                                LocalThumbItem(
                                    albumRel = albumRel,
                                    file = file,
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

@Composable
fun LocalThumbItem(
    albumRel: String,
    file: File,
    modifier: Modifier = Modifier
) {
    val cacheKey = "local:${file.absolutePath}"
    val thumb by produceState<ImageBitmap?>(initialValue = thumbMemoryCache.get(cacheKey), cacheKey) {
        if (value == null) {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeFile(file.absolutePath, opts)?.asImageBitmap()
                } catch (_: Exception) {
                    null
                }
            }
            if (bmp != null) {
                thumbMemoryCache.put(cacheKey, bmp)
                value = bmp
            }
        }
    }

    Surface(
        color = SolarWhite,
        border = BorderStroke(2.dp, SolarBlack),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(125.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SolarBlack)
            ) {
                if (thumb != null) {
                    Image(
                        bitmap = thumb!!,
                        contentDescription = file.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = file.name,
                color = SolarBlack,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "📁 $albumRel (${file.length() / 1024} KB)",
                color = SolarBlack,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
