package com.camflc.app

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val galleryThumbCache = object : LruCache<String, ImageBitmap>(160) {}

@Composable
fun GalleryScreen(
    baseDirectory: String,
    albumsList: List<AlbumInfo>,
    activeFolder: String,
    initialFocusedFilePath: String?,
    onClearInitialFocusedFile: () -> Unit,
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
    // null = Todas as pastas | String = Pasta especifica
    var selectedAlbumFilter by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }

    // Selecao multipla de ficheiros
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Item aberto em Tela Cheia (Inspecao / Zoom)
    var inspectedItem by remember { mutableStateOf<LocalMediaItem?>(null) }

    // Dialogos de Gestao (Mover, Renomear Foto, Criar Pasta, Excluir)
    var showMoveDialog by remember { mutableStateOf(false) }
    var showRenameFileDialog by remember { mutableStateOf(false) }
    var renameTargetItem by remember { mutableStateOf<LocalMediaItem?>(null) }
    var renameFileInput by remember { mutableStateOf("") }

    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderInput by remember { mutableStateOf("") }
    var createAsSubfolder by remember { mutableStateOf(false) }

    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    // Carrega os itens filtrados por pasta e pela barra de pesquisa
    val galleryItems = remember(baseDirectory, albumsList, selectedAlbumFilter, searchQuery, refreshTick) {
        StorageEngine.queryLocalGallery(
            baseRelativePath = baseDirectory,
            albumFilter = selectedAlbumFilter,
            searchQuery = searchQuery
        )
    }

    // Se o utilizador veio do clique na miniatura da Camera Pro, abre a foto em destaque
    LaunchedEffect(initialFocusedFilePath, galleryItems) {
        if (!initialFocusedFilePath.isNullOrBlank()) {
            val found = galleryItems.find { it.uniquePath == initialFocusedFilePath }
            if (found != null) {
                inspectedItem = found
                onClearInitialFocusedFile()
            }
        }
    }

    val selectedItemsList = remember(galleryItems, selectedPaths) {
        galleryItems.filter { selectedPaths.contains(it.uniquePath) }
    }

    // 1. MODAL DE VISUALIZACAO EM TELA CHEIA (QUANDO CLICA NA LUPA OU VEM DA CAMERA)
    inspectedItem?.let { item ->
        FullScreenPhotoDialog(
            item = item,
            onDismiss = { inspectedItem = null },
            onRenameClick = {
                renameTargetItem = item
                renameFileInput = item.file.nameWithoutExtension
                showRenameFileDialog = true
            },
            onMoveClick = {
                selectedPaths = setOf(item.uniquePath)
                inspectedItem = null
                showMoveDialog = true
            },
            onDeleteClick = {
                selectedPaths = setOf(item.uniquePath)
                inspectedItem = null
                showDeleteConfirmDialog = true
            }
        )
    }

    // 2. DIALOGO PARA RENOMEAR FICHEIRO
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
                            inspectedItem = null
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

    // 3. DIALOGO PARA MOVER FOTOS ENTRE PASTAS / SUBPASTAS
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
                            selected = !createAsSubfolder,
                            onClick = { createAsSubfolder = false },
                            label = { Text("Na Raiz", fontWeight = FontWeight.Bold) }
                        )
                        FilterChip(
                            selected = createAsSubfolder,
                            onClick = { createAsSubfolder = true },
                            label = { Text("Subpasta de '$baseParent'", fontWeight = FontWeight.Bold) }
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

    // 5. DIALOGO DE CONFIRMACAO DE EXCLUSAO
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
                    text = "${galleryItems.size} fotos exibidas • ${selectedPaths.size} selecionadas",
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

        // BARRA DE PESQUISA INSTANTANEA (POR PREFIXO, NOME OU PASTA)
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = {
                Text("Pesquisar por prefixo, código ou pasta...", color = subTextColor, fontWeight = FontWeight.Bold)
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

        // CARROSSEL DE FILTRO DE PASTAS E SUBPASTAS
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                val isAll = selectedAlbumFilter == null
                Surface(
                    color = if (isAll) SolarAmber else cardColor,
                    border = BorderStroke(1.5.dp, if (isAll) SolarBlack else borderColor),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .height(40.dp)
                        .clickable {
                            selectedAlbumFilter = null
                            vibrateFeedback()
                        }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp)
                    ) {
                        Icon(
                            Icons.Default.GridView,
                            contentDescription = null,
                            tint = if (isAll) SolarBlack else textColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Todas (${albumsList.sumOf { it.fileCount }})",
                            color = if (isAll) SolarBlack else textColor,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            items(albumsList) { album ->
                val isSel = selectedAlbumFilter == album.relativePath
                Surface(
                    color = if (isSel) SolarAmber else cardColor,
                    border = BorderStroke(1.5.dp, if (isSel) SolarBlack else borderColor),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .height(40.dp)
                        .clickable {
                            selectedAlbumFilter = album.relativePath
                            vibrateFeedback()
                        }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp)
                    ) {
                        Icon(
                            imageVector = if (album.relativePath.contains("/")) {
                                Icons.Default.SubdirectoryArrowRight
                            } else {
                                Icons.Default.Folder
                            },
                            contentDescription = null,
                            tint = if (isSel) SolarBlack else SolarAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${album.relativePath} (${album.fileCount})",
                            color = if (isSel) SolarBlack else textColor,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

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

        // BARRA DE ACOES DE GESTAO (SELECAO E ACOES EM LOTE)
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

                    // Se uma pasta especifica estiver filtrada, permite defini-la como ativa para a Camera
                    selectedAlbumFilter?.let { filterFolder ->
                        if (filterFolder != activeFolder) {
                            TextButton(
                                onClick = {
                                    onSelectActiveFolder(filterFolder)
                                    vibrateFeedback()
                                    statusBanner = "✓ '$filterFolder' definida como pasta ativa da câmera!"
                                }
                            ) {
                                Text("Usar na Câmera", color = SolarAmber, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                }

                // Botoes de Acao Direta: MOVER | RENOMEAR | EXCLUIR
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
                            "Nenhuma foto nesta pasta ainda."
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
                                    inspectedItem = mediaItem
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
                try {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeFile(item.file.absolutePath, opts)?.asImageBitmap()
                } catch (_: Exception) {
                    null
                }
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
        modifier = modifier.clickable { onToggleSelect() }
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
                            imageVector = Icons.Default.ZoomIn,
                            contentDescription = "Ampliar",
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
fun FullScreenPhotoDialog(
    item: LocalMediaItem,
    onDismiss: () -> Unit,
    onRenameClick: () -> Unit,
    onMoveClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val fullBitmap by produceState<ImageBitmap?>(initialValue = null, item.uniquePath) {
        value = withContext(Dispatchers.IO) {
            try {
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeFile(item.file.absolutePath, opts)?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0B0F19),
        title = {
            Column {
                Text(
                    text = item.name,
                    color = SolarAmber,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "📁 ${item.albumRelativePath} • ${item.formattedSize()} • ${item.formattedDate()}",
                    color = SolarWhite,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SolarBlack)
                ) {
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

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = onRenameClick,
                        border = BorderStroke(1.dp, SolarAmber),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, tint = SolarAmber, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Renomear", color = SolarAmber, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                    }

                    OutlinedButton(
                        onClick = onMoveClick,
                        border = BorderStroke(1.dp, SolarGreen),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.DriveFileMove, contentDescription = null, tint = SolarGreen, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Mover", color = SolarGreen, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                    }

                    OutlinedButton(
                        onClick = onDeleteClick,
                        border = BorderStroke(1.dp, SolarRed),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = SolarRed, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Excluir", color = SolarRed, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = SolarAmber, contentColor = SolarBlack)
            ) {
                Text("FECHAR", fontWeight = FontWeight.ExtraBold)
            }
        }
    )
}
