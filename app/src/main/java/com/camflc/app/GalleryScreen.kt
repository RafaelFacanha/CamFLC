package com.camflc.app

import android.net.Uri
import android.util.LruCache
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val galleryThumbCache = object : LruCache<String, ImageBitmap>(160) {}

@Composable
fun GalleryScreen(
    baseDirectory: String,
    albumsList: List<AlbumInfo>,
    activeFolder: String,
    initialFocusedFilePath: String?,
    initialFocusedFolder: String?,
    onClearInitialFocus: () -> Unit,
    isDarkMode: Boolean,
    onSelectActiveFolder: (String) -> Unit,
    onStorageChanged: () -> Unit,
    vibrateFeedback: () -> Unit
) {
    val context = LocalContext.current

    // Paleta adaptativa (Modo Claro Solar vs Modo Escuro OLED)
    val bgColor = if (isDarkMode) Color(0xFF0B0F19) else SolarWhite
    val cardColor = if (isDarkMode) Color(0xFF1E293B) else SolarSurface
    val textColor = if (isDarkMode) SolarWhite else SolarBlack
    val subTextColor = if (isDarkMode) Color(0xFFCBD5E1) else Color(0xFF334155)
    val borderColor = if (isDarkMode) Color(0xFF475569) else SolarBlack

    var searchQuery by remember { mutableStateOf("") }
    // Inicia filtrando diretamente na PASTA ATIVA (e nao na geral!)
    var selectedAlbumFilter by remember { mutableStateOf<String?>(activeFolder) }
    var folderDropdownExpanded by remember { mutableStateOf(false) }
    var folderDropdownSearch by remember { mutableStateOf("") }

    var refreshTick by remember { mutableIntStateOf(0) }

    // Selecao multipla de arquivos
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Caminho do arquivo aberto em Tela Cheia (Permite navegar ◀ / ▶ pela lista atual)
    var inspectedPath by remember { mutableStateOf<String?>(null) }

    // Dialogos de Gerenciamento (Mover, Renomear, Criar Pasta, Excluir)
    var showMoveDialog by remember { mutableStateOf(false) }
    var showRenameFileDialog by remember { mutableStateOf(false) }
    var renameTargetItem by remember { mutableStateOf<LocalMediaItem?>(null) }
    var renameFileInput by remember { mutableStateOf("") }

    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderInput by remember { mutableStateOf("") }
    var createAsSubfolder by remember { mutableStateOf(true) }

    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    // Sincroniza quando o usuario clica na miniatura dentro da Camera Pro
    LaunchedEffect(initialFocusedFolder, initialFocusedFilePath) {
        if (!initialFocusedFolder.isNullOrBlank()) {
            selectedAlbumFilter = initialFocusedFolder
        }
        if (!initialFocusedFilePath.isNullOrBlank()) {
            inspectedPath = initialFocusedFilePath
        }
        if (initialFocusedFolder != null || initialFocusedFilePath != null) {
            onClearInitialFocus()
        }
    }

    // Carrega os itens filtrados pela pasta selecionada e pela barra de pesquisa
    val galleryItems = remember(baseDirectory, albumsList, selectedAlbumFilter, searchQuery, refreshTick) {
        StorageEngine.queryLocalGallery(
            baseRelativePath = baseDirectory,
            albumFilter = selectedAlbumFilter,
            searchQuery = searchQuery
        )
    }

    val selectedItemsList = remember(galleryItems, selectedPaths) {
        galleryItems.filter { selectedPaths.contains(it.uniquePath) }
    }

    // Indice e objeto atual em Tela Cheia
    val currentInspectedIndex = remember(galleryItems, inspectedPath) {
        if (inspectedPath == null) -1 else galleryItems.indexOfFirst { it.uniquePath == inspectedPath }
    }
    val inspectedItem = if (currentInspectedIndex in galleryItems.indices) {
        galleryItems[currentInspectedIndex]
    } else null

    // 1. MODAL DE VISUALIZACAO EM TELA CHEIA COM NAVEGACAO (◀ / ▶), VIDEO E EDICAO DIRETA
    if (inspectedItem != null) {
        FullScreenMediaDialog(
            item = inspectedItem,
            currentIndex = currentInspectedIndex,
            totalCount = galleryItems.size,
            hasPrevious = currentInspectedIndex > 0,
            hasNext = currentInspectedIndex < galleryItems.size - 1,
            onPrevious = {
                if (currentInspectedIndex > 0) {
                    vibrateFeedback()
                    inspectedPath = galleryItems[currentInspectedIndex - 1].uniquePath
                }
            },
            onNext = {
                if (currentInspectedIndex < galleryItems.size - 1) {
                    vibrateFeedback()
                    inspectedPath = galleryItems[currentInspectedIndex + 1].uniquePath
                }
            },
            onDismiss = { inspectedPath = null },
            onRenameClick = {
                renameTargetItem = inspectedItem
                renameFileInput = inspectedItem.file.nameWithoutExtension
                showRenameFileDialog = true
            },
            onMoveClick = {
                selectedPaths = setOf(inspectedItem.uniquePath)
                showMoveDialog = true
            },
            onDeleteClick = {
                // Calcula a proxima foto para continuar em tela cheia apos excluir a atual
                val nextNeighbor = galleryItems.getOrNull(currentInspectedIndex + 1)
                    ?: galleryItems.getOrNull(currentInspectedIndex - 1)
                val deleted = StorageEngine.deleteMediaFiles(listOf(inspectedItem.file))
                if (deleted > 0) {
                    vibrateFeedback()
                    selectedPaths = selectedPaths - inspectedItem.uniquePath
                    inspectedPath = nextNeighbor?.uniquePath
                    statusBanner = "🗑️ '${inspectedItem.name}' excluído."
                    refreshTick++
                    onStorageChanged()
                }
            }
        )
    }

    // 2. DIALOGO PARA RENOMEAR ARQUIVO (FUNCIONA NA GRADE E EM TELA CHEIA)
    if (showRenameFileDialog && renameTargetItem != null) {
        AlertDialog(
            onDismissRequest = { showRenameFileDialog = false },
            containerColor = cardColor,
            title = {
                Text("RENOMEAR ARQUIVO", color = textColor, fontWeight = FontWeight.ExtraBold)
            },
            text = {
                OutlinedTextField(
                    value = renameFileInput,
                    onValueChange = { renameFileInput = it },
                    label = { Text("Novo nome (sem extensão)", color = textColor, fontWeight = FontWeight.Bold) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = renameTargetItem!!
                        val updatedFile = StorageEngine.renameMediaFile(context, target.file, renameFileInput)
                        if (updatedFile != null) {
                            vibrateFeedback()
                            statusBanner = "✓ Renomeado para ${updatedFile.name}"
                            selectedPaths = emptySet()
                            if (inspectedPath == target.uniquePath) {
                                inspectedPath = updatedFile.absolutePath
                            }
                            refreshTick++
                            onStorageChanged()
                        } else {
                            statusBanner = "Não foi possível renomear (verifique se o nome já existe)."
                        }
                        showRenameFileDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SolarAmber, contentColor = SolarBlack)
                ) {
                    Text("SALVAR", fontWeight = FontWeight.ExtraBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameFileDialog = false }) {
                    Text("CANCELAR", color = textColor, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // 3. DIALOGO PARA MOVER ARQUIVOS ENTRE PASTAS / SUBPASTAS
    if (showMoveDialog) {
        AlertDialog(
            onDismissRequest = { showMoveDialog = false },
            containerColor = cardColor,
            title = {
                Text(
                    text = "MOVER ${selectedItemsList.size} ARQUIVO(S) PARA:",
                    color = textColor,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    albumsList.forEach { album ->
                        Surface(
                            color = bgColor,
                            border = BorderStroke(1.5.dp, borderColor),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val moved = StorageEngine.moveMediaFilesToAlbum(
                                        context = context,
                                        baseRelativePath = baseDirectory,
                                        filesToMove = selectedItemsList.map { it.file },
                                        targetAlbumRelativePath = album.relativePath
                                    )
                                    vibrateFeedback()
                                    statusBanner = "✓ $moved arquivo(s) movido(s) para '${album.relativePath}'!"
                                    selectedPaths = emptySet()
                                    inspectedPath = null
                                    showMoveDialog = false
                                    refreshTick++
                                    onStorageChanged()
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(12.dp)
                            ) {
                                Icon(Icons.Default.Folder, contentDescription = null, tint = SolarAmber)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = album.relativePath,
                                        color = textColor,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "${album.fileCount} arquivos",
                                        color = subTextColor,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMoveDialog = false }) {
                    Text("FECHAR", color = textColor, fontWeight = FontWeight.ExtraBold)
                }
            }
        )
    }

    // 4. DIALOGO PARA CRIAR NOVA PASTA OU SUBPASTA DIRETO NA GALERIA
    if (showCreateFolderDialog) {
        val baseParent = selectedAlbumFilter ?: activeFolder
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            containerColor = cardColor,
            title = {
                Text("CRIAR PASTA / SUBPASTA", color = textColor, fontWeight = FontWeight.ExtraBold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = createAsSubfolder,
                            onClick = { createAsSubfolder = true },
                            label = { Text("Subpasta de '$baseParent'", fontWeight = FontWeight.Bold) }
                        )
                        FilterChip(
                            selected = !createAsSubfolder,
                            onClick = { createAsSubfolder = false },
                            label = { Text("Na Raiz", fontWeight = FontWeight.Bold) }
                        )
                    }
                    OutlinedTextField(
                        value = newFolderInput,
                        onValueChange = { newFolderInput = it },
                        label = { Text("Nome da nova pasta", fontWeight = FontWeight.Bold) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newFolderInput.isNotBlank()) {
                            val targetRel = if (createAsSubfolder && baseParent.isNotBlank()) {
                                "$baseParent/${newFolderInput.trim()}"
                            } else {
                                newFolderInput.trim()
                            }
                            val created = StorageEngine.createAlbum(baseDirectory, targetRel)
                            if (created != null) {
                                vibrateFeedback()
                                selectedAlbumFilter = created
                                onSelectActiveFolder(created)
                                statusBanner = "✓ Pasta '$created' criada e selecionada!"
                                newFolderInput = ""
                                refreshTick++
                                onStorageChanged()
                            }
                            showCreateFolderDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SolarAmber, contentColor = SolarBlack)
                ) {
                    Text("CRIAR", fontWeight = FontWeight.ExtraBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) {
                    Text("CANCELAR", color = textColor, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // 5. DIALOGO DE CONFIRMACAO DE EXCLUSAO EM LOTE
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            containerColor = cardColor,
            title = {
                Text("EXCLUIR ARQUIVOS?", color = SolarRed, fontWeight = FontWeight.ExtraBold)
            },
            text = {
                Text(
                    text = "Deseja excluir permanentemente ${selectedItemsList.size} arquivo(s) selecionado(s)?",
                    color = textColor,
                    fontWeight = FontWeight.Bold
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val deleted = StorageEngine.deleteMediaFiles(selectedItemsList.map { it.file })
                        vibrateFeedback()
                        statusBanner = "🗑️ $deleted arquivo(s) excluído(s)."
                        selectedPaths = emptySet()
                        showDeleteConfirmDialog = false
                        refreshTick++
                        onStorageChanged()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SolarRed, contentColor = SolarWhite)
                ) {
                    Text("EXCLUIR AGORA", fontWeight = FontWeight.ExtraBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("CANCELAR", color = textColor, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // CABECALHO DA GALERIA + BOTAO CRIAR PASTA
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Text(
                    text = "GALERIA LOCAL",
                    color = textColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "${galleryItems.size} itens na visão • ${selectedPaths.size} marcados",
                    color = subTextColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Button(
                onClick = { showCreateFolderDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = SolarAmber, contentColor = SolarBlack),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("+ PASTA", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
            }
        }

        // SELETOR DE PASTA EM MODELO DE LISTA SUSPENSA (DROPDOWN)
        Box(modifier = Modifier.fillMaxWidth()) {
            val currentAlbumObj = albumsList.find { it.relativePath == selectedAlbumFilter }
            val dropdownLabel = if (selectedAlbumFilter == null) {
                "🌐 Todas as Pastas (${albumsList.sumOf { it.fileCount }} arq)"
            } else {
                "📂 Pasta: $selectedAlbumFilter (${currentAlbumObj?.fileCount ?: galleryItems.size} arq)"
            }

            Surface(
                color = cardColor,
                border = BorderStroke(2.dp, SolarAmber),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clickable { folderDropdownExpanded = true }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp)
                ) {
                    Text(
                        text = dropdownLabel,
                        color = textColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Selecionar pasta",
                        tint = SolarAmber
                    )
                }
            }

            DropdownMenu(
                expanded = folderDropdownExpanded,
                onDismissRequest = {
                    folderDropdownExpanded = false
                    folderDropdownSearch = ""
                },
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .heightIn(max = 380.dp)
                    .background(cardColor)
            ) {
                Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    OutlinedTextField(
                        value = folderDropdownSearch,
                        onValueChange = { folderDropdownSearch = it },
                        placeholder = { Text("Filtrar pasta na lista...", color = subTextColor, fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Opcao para ver Todas as Pastas
                DropdownMenuItem(
                    text = {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "🌐 Todas as Pastas",
                                color = if (selectedAlbumFilter == null) SolarAmber else textColor,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = "${albumsList.sumOf { it.fileCount }} arq",
                                color = SolarGreen,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    },
                    onClick = {
                        selectedAlbumFilter = null
                        folderDropdownExpanded = false
                        folderDropdownSearch = ""
                        vibrateFeedback()
                    }
                )

                val filteredAlbums = albumsList.filter {
                    folderDropdownSearch.isBlank() ||
                            it.relativePath.contains(folderDropdownSearch.trim(), ignoreCase = true)
                }

                filteredAlbums.forEach { album ->
                    val isSel = selectedAlbumFilter == album.relativePath
                    DropdownMenuItem(
                        text = {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (album.relativePath.contains("/")) "↳ 📂 ${album.relativePath}" else "📁 ${album.relativePath}",
                                    color = if (isSel) SolarAmber else textColor,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "${album.fileCount} arq • ${album.formattedSize()}",
                                    color = subTextColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        },
                        onClick = {
                            selectedAlbumFilter = album.relativePath
                            onSelectActiveFolder(album.relativePath)
                            folderDropdownExpanded = false
                            folderDropdownSearch = ""
                            vibrateFeedback()
                        }
                    )
                }
            }
        }

        // BARRA DE PESQUISA INSTANTANEA POR NOME OU PREFIXO
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = {
                Text("Pesquisar foto/vídeo por prefixo ou código...", color = subTextColor, fontWeight = FontWeight.Bold)
            },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null, tint = textColor)
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Limpar", tint = textColor)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )

        // BANNER DE FEEDBACK DE OPERACOES
        statusBanner?.let { msg ->
            Surface(
                color = SolarBlack,
                border = BorderStroke(1.dp, SolarGreen),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = msg,
                        color = SolarGreen,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { statusBanner = null }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = SolarWhite)
                    }
                }
            }
        }

        // BARRA DE ACOES DE GERENCIAMENTO EM LOTE
        Card(
            colors = CardDefaults.cardColors(containerColor = cardColor),
            border = BorderStroke(1.5.dp, borderColor),
            shape = RoundedCornerShape(12.dp),
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                selectedPaths = galleryItems.map { it.uniquePath }.toSet()
                                vibrateFeedback()
                            },
                            border = BorderStroke(1.dp, borderColor),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text("Marcar Todas", color = textColor, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                        }

                        if (selectedPaths.isNotEmpty()) {
                            OutlinedButton(
                                onClick = {
                                    selectedPaths = emptySet()
                                    vibrateFeedback()
                                },
                                border = BorderStroke(1.dp, borderColor),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("Limpar (${selectedPaths.size})", color = textColor, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }

                    if (selectedAlbumFilter != activeFolder) {
                        TextButton(
                            onClick = {
                                selectedAlbumFilter = activeFolder
                                vibrateFeedback()
                            }
                        ) {
                            Text("Ir p/ Pasta Ativa", color = SolarAmber, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }

                if (selectedItemsList.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = { showMoveDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = SolarAmber, contentColor = SolarBlack),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                        ) {
                            Icon(Icons.Default.DriveFileMove, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("MOVER (${selectedItemsList.size})", fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
                        }

                        if (selectedItemsList.size == 1) {
                            Button(
                                onClick = {
                                    val single = selectedItemsList.first()
                                    renameTargetItem = single
                                    renameFileInput = single.file.nameWithoutExtension
                                    showRenameFileDialog = true
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = SolarBlack, contentColor = SolarAmber),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("RENOMEAR", fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
                            }
                        }

                        Button(
                            onClick = { showDeleteConfirmDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = SolarRed, contentColor = SolarWhite),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("EXCLUIR", fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // GRADE DE MINIATURAS DA GALERIA LOCAL (2 COLUNAS)
        if (galleryItems.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = cardColor),
                border = BorderStroke(1.5.dp, borderColor),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp)
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = subTextColor, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (searchQuery.isNotBlank()) {
                            "Nenhum arquivo encontrado para '$searchQuery'."
                        } else {
                            "Nenhuma foto ou vídeo nesta pasta ainda."
                        },
                        color = textColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        } else {
            val rows = galleryItems.chunked(2)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEach { pair ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        pair.forEach { mediaItem ->
                            val isSelected = selectedPaths.contains(mediaItem.uniquePath)
                            GalleryPhotoCard(
                                item = mediaItem,
                                isSelected = isSelected,
                                isDarkMode = isDarkMode,
                                onToggleSelect = {
                                    selectedPaths = if (isSelected) {
                                        selectedPaths - mediaItem.uniquePath
                                    } else {
                                        selectedPaths + mediaItem.uniquePath
                                    }
                                    vibrateFeedback()
                                },
                                onInspectFullScreen = {
                                    vibrateFeedback()
                                    inspectedPath = mediaItem.uniquePath
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

@Composable
fun GalleryPhotoCard(
    item: LocalMediaItem,
    isSelected: Boolean,
    isDarkMode: Boolean,
    onToggleSelect: () -> Unit,
    onInspectFullScreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardBg = when {
        isSelected -> SolarAmber
        isDarkMode -> Color(0xFF1E293B)
        else -> SolarWhite
    }
    val contentColor = if (isSelected) SolarBlack else if (isDarkMode) SolarWhite else SolarBlack
    val borderColor = if (isSelected) SolarBlack else if (isDarkMode) Color(0xFF475569) else SolarBlack

    val cacheKey = "${item.uniquePath}_${item.lastModified}"
    val thumb by produceState<ImageBitmap?>(initialValue = galleryThumbCache.get(cacheKey), cacheKey) {
        if (value == null) {
            val decoded = withContext(Dispatchers.IO) {
                StorageEngine.loadMediaThumbnail(item.file, sampleSize = 4)?.asImageBitmap()
            }
            if (decoded != null) {
                galleryThumbCache.put(cacheKey, decoded)
                value = decoded
            }
        }
    }

    Surface(
        color = cardBg,
        border = BorderStroke(if (isSelected) 3.dp else 1.5.dp, borderColor),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.clickable { onInspectFullScreen() }
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(135.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SolarBlack)
            ) {
                if (thumb != null) {
                    Image(
                        bitmap = thumb!!,
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (item.isVideo) {
                    Surface(
                        color = SolarRed.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp)
                    ) {
                        Text(
                            text = "🎬 VÍDEO",
                            color = SolarWhite,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                // Botao no canto superior esquerdo para Ampliar em Tela Cheia
                Surface(
                    color = SolarBlack.copy(alpha = 0.75f),
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .size(32.dp)
                        .clickable { onInspectFullScreen() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (item.isVideo) Icons.Default.PlayArrow else Icons.Default.ZoomIn,
                            contentDescription = "Abrir em Tela Cheia",
                            tint = SolarAmber,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Checkbox no canto superior direito para Selecao Multipla
                Surface(
                    color = if (isSelected) SolarGreen else SolarBlack.copy(alpha = 0.75f),
                    border = BorderStroke(1.5.dp, SolarWhite),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clickable { onToggleSelect() }
                ) {
                    Icon(
                        imageVector = if (isSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                        contentDescription = "Selecionar",
                        tint = if (isSelected) SolarBlack else SolarWhite,
                        modifier = Modifier
                            .padding(2.dp)
                            .size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = item.name,
                color = contentColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "📁 ${item.albumRelativePath} • ${item.formattedSize()}",
                color = contentColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun FullScreenMediaDialog(
    item: LocalMediaItem,
    currentIndex: Int,
    totalCount: Int,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onDismiss: () -> Unit,
    onRenameClick: () -> Unit,
    onMoveClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val fullBitmap by produceState<ImageBitmap?>(initialValue = null, item.uniquePath) {
        if (item.isVideo) {
            value = null
        } else {
            value = withContext(Dispatchers.IO) {
                StorageEngine.loadMediaThumbnail(item.file, sampleSize = 2)?.asImageBitmap()
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B0F19))
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. TOPO DA TELA CHEIA: Nome, Pasta, Posicao (Ex: 3 de 12) e Botao Fechar
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        color = SolarAmber,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "📂 ${item.albumRelativePath} • ${item.formattedSize()} • (${currentIndex + 1} de $totalCount)",
                        color = SolarWhite,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0xFF1E293B), CircleShape)
                        .border(1.5.dp, SolarAmber, CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Fechar", tint = SolarAmber)
                }
            }

            // 2. CENTRO: VISOR AMPLIADO DE FOTO OU REPRODUTOR DE VIDEO (.MP4) + SETAS LATERAIS (◀ / ▶)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 10.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(SolarBlack)
                    .border(1.5.dp, Color(0xFF334155), RoundedCornerShape(14.dp))
            ) {
                if (item.isVideo) {
                    key(item.uniquePath) {
                        AndroidView(
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    val mediaController = MediaController(ctx)
                                    mediaController.setAnchorView(this)
                                    setMediaController(mediaController)
                                    setVideoURI(Uri.fromFile(item.file))
                                    setOnPreparedListener { mp ->
                                        mp.isLooping = false
                                        start()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    if (fullBitmap != null) {
                        Image(
                            bitmap = fullBitmap!!,
                            contentDescription = item.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        CircularProgressIndicator(color = SolarAmber)
                    }
                }

                // Seta Flutuante Esquerda (◀ Anterior)
                if (hasPrevious) {
                    IconButton(
                        onClick = onPrevious,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 10.dp)
                            .size(48.dp)
                            .background(SolarBlack.copy(alpha = 0.75f), CircleShape)
                            .border(2.dp, SolarAmber, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
                            contentDescription = "Foto Anterior",
                            tint = SolarAmber,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                // Seta Flutuante Direita (Próxima ▶)
                if (hasNext) {
                    IconButton(
                        onClick = onNext,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 10.dp)
                            .size(48.dp)
                            .background(SolarBlack.copy(alpha = 0.75f), CircleShape)
                            .border(2.dp, SolarAmber, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = "Próxima Foto",
                            tint = SolarAmber,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            // 3. RODAPE DA TELA CHEIA: BOTOES DE NAVEGACAO E EDICAO DIRETA (RENOMEAR, MOVER, EXCLUIR)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = onPrevious,
                        enabled = hasPrevious,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1E293B),
                            contentColor = SolarAmber
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("ANTERIOR", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Button(
                        onClick = onNext,
                        enabled = hasNext,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1E293B),
                            contentColor = SolarAmber
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("PRÓXIMA", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = onRenameClick,
                        colors = ButtonDefaults.buttonColors(containerColor = SolarAmber, contentColor = SolarBlack),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("RENOMEAR", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                    }

                    Button(
                        onClick = onMoveClick,
                        colors = ButtonDefaults.buttonColors(containerColor = SolarGreen, contentColor = SolarBlack),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Icon(Icons.Default.DriveFileMove, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("MOVER", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                    }

                    Button(
                        onClick = onDeleteClick,
                        colors = ButtonDefaults.buttonColors(containerColor = SolarRed, contentColor = SolarWhite),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("EXCLUIR", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
    }
}
