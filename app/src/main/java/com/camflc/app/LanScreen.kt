package com.camflc.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun LanScreen(
    lanEngine: LanEngine,
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

    // Estado do Dispositivo Remoto atualmente aberto para gerenciamento
    var selectedPeer by remember { mutableStateOf<PeerDevice?>(null) }
    var remotePeerRealName by remember { mutableStateOf("") }
    var remoteAlbums by remember { mutableStateOf<List<AlbumInfo>>(emptyList()) }
    var expandedRemoteAlbum by remember { mutableStateOf<String?>(null) }
    var remoteFilesInAlbum by remember { mutableStateOf<List<RemoteFileInfo>>(emptyList()) }

    var isSyncing by remember { mutableStateOf(false) }
    var statusLog by remember {
        mutableStateOf("Servidor LAN ativo! Digite o endereço acima no PC ou toque em um dispositivo abaixo.")
    }

    // Combina dispositivos pareados salvos + dispositivos descobertos pelo radar UDP
    val combinedPeers = remember(pairedPeers, discoveredPeers) {
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
        map.values.toList()
    }

    // Funcao para conectar e listar as pastas de um dispositivo remoto
    val connectAndLoadPeer: (PeerDevice) -> Unit = { peer ->
        selectedPeer = peer
        expandedRemoteAlbum = null
        coroutineScope.launch {
            statusLog = "Conectando a ${peer.name} (${peer.ip})..."
            val res = lanEngine.fetchRemoteDeviceInfo(peer.ip, peer.port)
            res.onSuccess { (realName, albums) ->
                remotePeerRealName = realName
                remoteAlbums = albums
                vibrateFeedback()
                statusLog = "✓ Conectado a '$realName': ${albums.size} pastas encontradas."
            }.onFailure { err ->
                remoteAlbums = emptyList()
                statusLog = "Falha ao conectar em ${peer.ip}: verifique se o CamFLC está aberto nele."
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
        // 1. PAINEL DO SERVIDOR LOCAL (PARA ACESSO NO COMPUTADOR OU OUTRO CELULAR)
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
                        text = "SERVIDOR LAN ATIVO (PC & MOBILE)",
                        color = SolarAmber,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    OutlinedButton(
                        onClick = {
                            localIp = lanEngine.getLocalIpAddress()
                            onTriggerRadarScan()
                            vibrateFeedback()
                        },
                        border = BorderStroke(1.dp, SolarAmber),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("ATUALIZAR IP", color = SolarAmber, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }

                Text(
                    text = "http://$localIp:${LanEngine.HTTP_PORT}",
                    color = SolarGreen,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "No computador (mesmo Wi-Fi ou Roteador do celular), abra o navegador e digite o endereço verde acima para baixar pastas em .ZIP ou gerenciar arquivos.",
                    color = SolarWhite,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Configuracoes de Identificacao na Rede e Permissao de Transferencia
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
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = onDeviceNameChange,
                    label = { Text("Nome deste Dispositivo na Rede", fontWeight = FontWeight.Bold, color = SolarBlack) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Permitir que dispositivos pareados movam/excluam arquivos deste aparelho",
                        color = SolarBlack,
                        fontSize = 13.sp,
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

        // 2. LISTA DE DISPOSITIVOS PAREADOS E DESCOBERTOS NA REDE LOCAL
        Card(
            colors = CardDefaults.cardColors(containerColor = SolarSurface),
            border = BorderStroke(2.dp, SolarBlack),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "1. DISPOSITIVOS NA REDE (${combinedPeers.size})",
                        color = SolarBlack,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Button(
                        onClick = {
                            localIp = lanEngine.getLocalIpAddress()
                            onTriggerRadarScan()
                            vibrateFeedback()
                            statusLog = "Buscando dispositivos CamFLC na rede local..."
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SolarBlack, contentColor = SolarAmber),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Icon(Icons.Default.Radar, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("BUSCAR NA REDE", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }

                if (combinedPeers.isEmpty()) {
                    Text(
                        text = "Nenhum outro celular detectado ainda. Abra a aba REDE LAN no outro celular ou adicione o IP dele abaixo para parear.",
                        color = SolarBlack,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    combinedPeers.forEach { peer ->
                        val isSelected = selectedPeer?.ip == peer.ip
                        Surface(
                            color = if (isSelected) SolarBlack else SolarWhite,
                            border = BorderStroke(2.dp, SolarBlack),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { connectAndLoadPeer(peer) }
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
                                            text = "IP: ${peer.ip}:${peer.port} " +
                                                    if (peer.isPaired) "• [PAREADO]" else "• [DESCOBERTO]",
                                            color = if (isSelected) SolarWhite else SolarBlack,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (!peer.isPaired) {
                                        OutlinedButton(
                                            onClick = {
                                                onSavePairedPeer(peer.name, peer.ip)
                                                vibrateFeedback()
                                            },
                                            border = BorderStroke(1.dp, if (isSelected) SolarAmber else SolarBlack)
                                        ) {
                                            Text(
                                                "FIXAR",
                                                color = if (isSelected) SolarAmber else SolarBlack,
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = 11.sp
                                            )
                                        }
                                    } else {
                                        IconButton(onClick = { onRemovePairedPeer(peer.ip) }) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = null,
                                                tint = SolarRed
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Divider(color = SolarBlack, thickness = 1.dp)

                // Pareamento manual rapido por IP
                Text(
                    text = "Adicionar / Parear Dispositivo pelo IP:",
                    color = SolarBlack,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = manualNameInput,
                        onValueChange = { manualNameInput = it },
                        label = { Text("Apelido (ex: Celular 2)", fontWeight = FontWeight.Bold) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = manualIpInput,
                        onValueChange = { manualIpInput = it },
                        label = { Text("IP (ex: 192.168.1.20)", fontWeight = FontWeight.Bold) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Button(
                    onClick = {
                        val cleanIp = manualIpInput.trim()
                        val cleanName = manualNameInput.trim().ifEmpty { "Dispositivo $cleanIp" }
                        if (cleanIp.isNotEmpty()) {
                            onSavePairedPeer(cleanName, cleanIp)
                            val newPeer = PeerDevice(name = cleanName, ip = cleanIp, isPaired = true)
                            manualIpInput = ""
                            manualNameInput = ""
                            connectAndLoadPeer(newPeer)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SolarBlack, contentColor = SolarAmber),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(Icons.Default.AddLink, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("PAREAR E ACESSAR DISPOSITIVO", fontWeight = FontWeight.ExtraBold)
                }
            }
        }

        // Barra de Status das Transferencias
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
                modifier = Modifier.padding(14.dp)
            )
        }

        // 3. EXPLORADOR E TRANSFERENCIA DE PASTAS DO DISPOSITIVO REMOTO SELECIONADO
        selectedPeer?.let { peer ->
            Card(
                colors = CardDefaults.cardColors(containerColor = SolarSurface),
                border = BorderStroke(3.dp, SolarBlack),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "PASTAS EM '${remotePeerRealName.ifEmpty { peer.name }}'",
                                color = SolarBlack,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = "Conectado via LAN (${peer.ip}) • ${remoteAlbums.size} álbuns",
                                color = SolarBlack,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        OutlinedButton(
                            onClick = { connectAndLoadPeer(peer) },
                            border = BorderStroke(2.dp, SolarBlack)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = SolarBlack)
                        }
                    }

                    // Botao Gigante: Copiar Todos os Albuns de Uma So Vez
                    if (remoteAlbums.isNotEmpty()) {
                        Button(
                            onClick = {
                                if (!isSyncing) {
                                    isSyncing = true
                                    coroutineScope.launch {
                                        var totalCopied = 0
                                        for (album in remoteAlbums) {
                                            if (album.fileCount > 0) {
                                                val r = lanEngine.syncAlbumFromPeer(
                                                    peerIp = peer.ip,
                                                    peerPort = peer.port,
                                                    albumRelativePath = album.relativePath,
                                                    deleteRemoteAfterTransfer = false,
                                                    onProgress = { msg -> statusLog = msg }
                                                )
                                                totalCopied += r.getOrDefault(0)
                                            }
                                        }
                                        isSyncing = false
                                        vibrateFeedback()
                                        statusLog = "✓ SINCRONIZAÇÃO COMPLETA: $totalCopied arquivos novos copiados de ${peer.name}!"
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SolarAmber,
                                contentColor = SolarBlack
                            ),
                            border = BorderStroke(2.dp, SolarBlack),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                        ) {
                            Icon(Icons.Default.CloudDownload, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isSyncing) "SINCRONIZANDO..." else "COPIAR TODOS OS ÁLBUNS DESTE APARELHO",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }

                    // Lista de Pastas e Subpastas Remotas com Acoes Individuais
                    remoteAlbums.forEach { album ->
                        Surface(
                            color = SolarWhite,
                            border = BorderStroke(2.dp, SolarBlack),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "📁 ${album.relativePath}",
                                            color = SolarBlack,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                        Text(
                                            text = "${album.fileCount} arquivos • ${album.formattedSize()}",
                                            color = SolarBlack,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    TextButton(
                                        onClick = {
                                            if (expandedRemoteAlbum == album.relativePath) {
                                                expandedRemoteAlbum = null
                                            } else {
                                                expandedRemoteAlbum = album.relativePath
                                                coroutineScope.launch {
                                                    val res = lanEngine.fetchRemoteAlbumFiles(
                                                        peer.ip,
                                                        peer.port,
                                                        album.relativePath
                                                    )
                                                    remoteFilesInAlbum = res.getOrDefault(emptyList())
                                                }
                                            }
                                        }
                                    ) {
                                        Text(
                                            text = if (expandedRemoteAlbum == album.relativePath) "OCULTAR" else "VER FOTOS",
                                            color = SolarBlack,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                }

                                // Botoes Ageis: COPIAR PASTA vs TRANSFERIR (MOVER) PASTA
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Button(
                                        onClick = {
                                            if (!isSyncing) {
                                                isSyncing = true
                                                coroutineScope.launch {
                                                    val res = lanEngine.syncAlbumFromPeer(
                                                        peerIp = peer.ip,
                                                        peerPort = peer.port,
                                                        albumRelativePath = album.relativePath,
                                                        deleteRemoteAfterTransfer = false,
                                                        onProgress = { statusLog = it }
                                                    )
                                                    isSyncing = false
                                                    vibrateFeedback()
                                                    res.onSuccess { count ->
                                                        statusLog = "✓ PASTA '${album.relativePath}' COPIADA ($count arquivos novos)!"
                                                    }.onFailure {
                                                        statusLog = "Erro ao copiar pasta: ${it.message}"
                                                    }
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = SolarBlack,
                                            contentColor = SolarAmber
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(46.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("COPIAR", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                                    }

                                    Button(
                                        onClick = {
                                            if (!isSyncing) {
                                                isSyncing = true
                                                coroutineScope.launch {
                                                    val res = lanEngine.syncAlbumFromPeer(
                                                        peerIp = peer.ip,
                                                        peerPort = peer.port,
                                                        albumRelativePath = album.relativePath,
                                                        deleteRemoteAfterTransfer = true,
                                                        onProgress = { statusLog = it }
                                                    )
                                                    isSyncing = false
                                                    vibrateFeedback()
                                                    res.onSuccess { count ->
                                                        statusLog = "✓ PASTA '${album.relativePath}' TRANSFERIDA ($count arq movidos)!"
                                                        connectAndLoadPeer(peer)
                                                    }.onFailure {
                                                        statusLog = "Erro ao transferir: ${it.message}"
                                                    }
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = SolarGreen,
                                            contentColor = SolarBlack
                                        ),
                                        border = BorderStroke(2.dp, SolarBlack),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(46.dp)
                                    ) {
                                        Icon(Icons.Default.DriveFileMove, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("TRANSFERIR", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                                    }
                                }

                                // Lista expansivel de arquivos individuais dentro da pasta remota
                                if (expandedRemoteAlbum == album.relativePath) {
                                    Divider(color = SolarBlack, thickness = 1.dp)
                                    if (remoteFilesInAlbum.isEmpty()) {
                                        Text(
                                            text = "Nenhum arquivo dentro desta pasta remota.",
                                            color = SolarBlack,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    } else {
                                        remoteFilesInAlbum.forEach { rf ->
                                            Row(
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 4.dp)
                                            ) {
                                                Text(
                                                    text = "• ${rf.name} (${rf.formattedSize()})",
                                                    color = SolarBlack,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                IconButton(
                                                    onClick = {
                                                        coroutineScope.launch {
                                                            val ok = lanEngine.deleteRemoteSingleFile(
                                                                peer.ip,
                                                                peer.port,
                                                                album.relativePath,
                                                                rf.name
                                                            )
                                                            if (ok) {
                                                                vibrateFeedback()
                                                                remoteFilesInAlbum = remoteFilesInAlbum.filterNot { it.name == rf.name }
                                                                statusLog = "Arquivo remoto '${rf.name}' excluído."
                                                            } else {
                                                                statusLog = "Exclusão negada (ative a permissão no outro aparelho)."
                                                            }
                                                        }
                                                    },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.DeleteForever,
                                                        contentDescription = null,
                                                        tint = SolarRed
                                                    )
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
        }
    }
}
