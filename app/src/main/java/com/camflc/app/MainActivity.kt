package com.camflc.app

import android.content.Context
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Paleta de Alto Contraste Solar (Outdoor-First)
val SolarWhite = Color(0xFFFFFFFF)
val SolarSurface = Color(0xFFF1F5F9)
val SolarBlack = Color(0xFF000000)
val SolarAmber = Color(0xFFFFD600)
val SolarGreen = Color(0xFF00C853)
val SolarBlue = Color(0xFF004FFE)

class MainActivity : ComponentActivity() {

    // Estado observavel para testar o botao fisico Volume -
    private var onVolumeDownTrigger: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("camflc_prefs", Context.MODE_PRIVATE)

        setContent {
            MaterialTheme {
                CamFLCApp(
                    prefs = prefs,
                    registerVolumeTrigger = { callback -> onVolumeDownTrigger = callback },
                    vibrateFeedback = { vibrateShort() }
                )
            }
        }
    }

    // Intercepta o botao fisico Volume - antes do sistema diminuir o som
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            onVolumeDownTrigger?.invoke()
            return true // Consome o evento para nao exibir a barra de volume do Android
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun vibrateShort() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        vibrator?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}

@Composable
fun CamFLCApp(
    prefs: android.content.SharedPreferences,
    registerVolumeTrigger: ((() -> Unit)?) -> Unit,
    vibrateFeedback: () -> Unit
) {
    // Carrega configuracoes salvas ou valores iniciais
    var baseDirectory by remember {
        mutableStateOf(prefs.getString("base_dir", "DCIM/CamFLC") ?: "DCIM/CamFLC")
    }
    var filePrefix by remember {
        mutableStateOf(prefs.getString("file_prefix", "OBRA_01") ?: "OBRA_01")
    }
    var foldersList by remember {
        val saved = prefs.getString("folders_list", "Geral,Inspecao,Laudo_A,Equipamentos") ?: "Geral"
        mutableStateOf(saved.split(",").filter { it.isNotBlank() })
    }
    var activeFolder by remember {
        mutableStateOf(prefs.getString("active_folder", foldersList.firstOrNull() ?: "Geral") ?: "Geral")
    }
    var useNativeCameraFallback by remember {
        mutableStateOf(prefs.getBoolean("use_native_fallback", false))
    }

    // Controle de abas: 0 = Config/Inicio, 1 = Visor Camera, 2 = Rede Local (LAN)
    var currentTab by remember { mutableIntStateOf(0) }
    var lastCaptureLog by remember { mutableStateOf("Nenhuma captura realizada nesta sessao.") }

    // Funcao central que simula/executa o salvamento instantaneo no caminho ativo
    val triggerCapture: () -> Unit = {
        vibrateFeedback()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val cleanPrefix = filePrefix.trim().replace(" ", "_").ifEmpty { "FOTO" }
        val generatedFile = "$baseDirectory/$activeFolder/${cleanPrefix}_$timestamp.jpg"
        lastCaptureLog = "SALVO: $generatedFile"
    }

    // Registra o gatilho do botao Volume -
    LaunchedEffect(filePrefix, activeFolder, baseDirectory) {
        registerVolumeTrigger(triggerCapture)
    }

    Scaffold(
        containerColor = SolarWhite,
        bottomBar = {
            NavigationBar(
                containerColor = SolarBlack,
                contentColor = SolarWhite
            ) {
                NavigationBarItem(
                    selected = currentTab == 0,
                    onClick = { currentTab = 0 },
                    icon = { Icon(Icons.Default.Tune, contentDescription = null) },
                    label = { Text("PAINEL & PASTAS", fontWeight = FontWeight.ExtraBold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = SolarBlack,
                        selectedTextColor = SolarAmber,
                        indicatorColor = SolarAmber,
                        unselectedIconColor = SolarWhite,
                        unselectedTextColor = SolarWhite
                    )
                )
                NavigationBarItem(
                    selected = currentTab == 1,
                    onClick = { currentTab = 1 },
                    icon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                    label = { Text("CÂMERA PRO", fontWeight = FontWeight.ExtraBold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = SolarBlack,
                        selectedTextColor = SolarAmber,
                        indicatorColor = SolarAmber,
                        unselectedIconColor = SolarWhite,
                        unselectedTextColor = SolarWhite
                    )
                )
                NavigationBarItem(
                    selected = currentTab == 2,
                    onClick = { currentTab = 2 },
                    icon = { Icon(Icons.Default.Wifi, contentDescription = null) },
                    label = { Text("REDE LAN", fontWeight = FontWeight.ExtraBold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = SolarBlack,
                        selectedTextColor = SolarAmber,
                        indicatorColor = SolarAmber,
                        unselectedIconColor = SolarWhite,
                        unselectedTextColor = SolarWhite
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                0 -> HomeConfigScreen(
                    baseDirectory = baseDirectory,
                    onBaseDirChange = {
                        baseDirectory = it
                        prefs.edit().putString("base_dir", it).apply()
                    },
                    filePrefix = filePrefix,
                    onPrefixChange = {
                        filePrefix = it
                        prefs.edit().putString("file_prefix", it).apply()
                    },
                    foldersList = foldersList,
                    activeFolder = activeFolder,
                    onSelectFolder = { folder ->
                        activeFolder = folder
                        prefs.edit().putString("active_folder", folder).apply()
                        vibrateFeedback()
                    },
                    onAddFolder = { newFolder ->
                        val clean = newFolder.trim().replace("/", "_")
                        if (clean.isNotEmpty() && !foldersList.contains(clean)) {
                            val updated = foldersList + clean
                            foldersList = updated
                            activeFolder = clean
                            prefs.edit()
                                .putString("folders_list", updated.joinToString(","))
                                .putString("active_folder", clean)
                                .apply()
                            vibrateFeedback()
                        }
                    },
                    useNativeCameraFallback = useNativeCameraFallback,
                    onToggleNativeCamera = {
                        useNativeCameraFallback = it
                        prefs.edit().putBoolean("use_native_fallback", it).apply()
                    },
                    lastCaptureLog = lastCaptureLog,
                    onOpenCamera = { currentTab = 1 }
                )

                1 -> CameraPreviewPlaceholderScreen(
                    activeFolder = activeFolder,
                    foldersList = foldersList,
                    filePrefix = filePrefix,
                    baseDirectory = baseDirectory,
                    lastCaptureLog = lastCaptureLog,
                    onSelectFolder = { folder ->
                        activeFolder = folder
                        prefs.edit().putString("active_folder", folder).apply()
                        vibrateFeedback()
                    },
                    onTriggerCapture = triggerCapture
                )

                2 -> LanPlaceholderScreen(
                    baseDirectory = baseDirectory,
                    foldersList = foldersList
                )
            }
        }
    }
}

@Composable
fun HomeConfigScreen(
    baseDirectory: String,
    onBaseDirChange: (String) -> Unit,
    filePrefix: String,
    onPrefixChange: (String) -> Unit,
    foldersList: List<String>,
    activeFolder: String,
    onSelectFolder: (String) -> Unit,
    onAddFolder: (String) -> Unit,
    useNativeCameraFallback: Boolean,
    onToggleNativeCamera: (Boolean) -> Unit,
    lastCaptureLog: String,
    onOpenCamera: () -> Unit
) {
    var newFolderName by remember { mutableStateOf("") }
    val sampleTimestamp = remember { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) }
    val cleanPrefix = filePrefix.trim().replace(" ", "_").ifEmpty { "FOTO" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Cabecalho de Alto Contraste
        Surface(
            color = SolarBlack,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "CamFLC • CONTROLE RÁPIDO",
                    color = SolarAmber,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Destino Ativo: $baseDirectory/$activeFolder/${cleanPrefix}_$sampleTimestamp.jpg",
                    color = SolarWhite,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Botao Gigante de Acesso Imediato a Camera
        Button(
            onClick = onOpenCamera,
            colors = ButtonDefaults.buttonColors(
                containerColor = SolarAmber,
                contentColor = SolarBlack
            ),
            border = BorderStroke(3.dp, SolarBlack),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "ABRIR CÂMERA PROFISSIONAL",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }

        // 1. Alternancia Agil de Albuns / Pastas (1 Toque)
        Card(
            colors = CardDefaults.cardColors(containerColor = SolarSurface),
            border = BorderStroke(2.dp, SolarBlack),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "1. ÁLBUM / PASTA ATIVA (TOQUE PARA TROCAR EM 1 SEGUNDO)",
                    color = SolarBlack,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.height(10.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(foldersList) { folder ->
                        val isSelected = folder == activeFolder
                        Surface(
                            color = if (isSelected) SolarBlack else SolarWhite,
                            border = BorderStroke(2.dp, SolarBlack),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .height(54.dp)
                                .clickable { onSelectFolder(folder) }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            ) {
                                Icon(
                                    imageVector = if (isSelected) Icons.Default.FolderSpecial else Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = if (isSelected) SolarAmber else SolarBlack
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = folder,
                                    color = if (isSelected) SolarAmber else SolarBlack,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Criador rapido de nova pasta
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        label = { Text("Criar Novo Álbum/Pasta", fontWeight = FontWeight.Bold, color = SolarBlack) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            onAddFolder(newFolderName)
                            newFolderName = ""
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SolarBlack, contentColor = SolarAmber),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(56.dp)
                    ) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("CRIAR", fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }

        // 2. Prefixo Padrao e Diretorio Base
        Card(
            colors = CardDefaults.cardColors(containerColor = SolarSurface),
            border = BorderStroke(2.dp, SolarBlack),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "2. PREFIXO DOS ARQUIVOS E DIRETÓRIO BASE",
                    color = SolarBlack,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                OutlinedTextField(
                    value = filePrefix,
                    onValueChange = onPrefixChange,
                    label = { Text("Prefixo Padrão (ex: LOTE_12, CLIENTE_A)", fontWeight = FontWeight.Bold, color = SolarBlack) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = baseDirectory,
                    onValueChange = onBaseDirChange,
                    label = { Text("Caminho Base no Armazenamento", fontWeight = FontWeight.Bold, color = SolarBlack) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 3. Configuracao de Hardware e Gatilho Volume -
        Card(
            colors = CardDefaults.cardColors(containerColor = SolarSurface),
            border = BorderStroke(2.dp, SolarBlack),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "3. HARDWARE & GATILHO FÍSICO (VOLUME -)",
                    color = SolarBlack,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "• Botão Físico 'Volume -' ATIVO: Pressione 'Volume -' agora para testar o disparo instantâneo.",
                    color = SolarBlack,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Exibir atalho para Câmera Nativa do Sistema",
                        color = SolarBlack,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = useNativeCameraFallback,
                        onCheckedChange = onToggleNativeCamera
                    )
                }

                Surface(
                    color = SolarBlack,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = lastCaptureLog,
                        color = SolarGreen,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun CameraPreviewPlaceholderScreen(
    activeFolder: String,
    foldersList: List<String>,
    filePrefix: String,
    baseDirectory: String,
    lastCaptureLog: String,
    onSelectFolder: (String) -> Unit,
    onTriggerCapture: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SolarBlack)
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Barra Superior Solar no Visor para Troca de Pasta em 1 Segundo
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "PASTA ATIVA NO VISOR (TOQUE PARA ALTERNAR):",
                color = SolarAmber,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(foldersList) { folder ->
                    val isSelected = folder == activeFolder
                    Surface(
                        color = if (isSelected) SolarAmber else Color(0xFF262626),
                        border = BorderStroke(2.dp, SolarAmber),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .height(46.dp)
                            .clickable { onSelectFolder(folder) }
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 14.dp)
                        ) {
                            Text(
                                text = folder,
                                color = if (isSelected) SolarBlack else SolarWhite,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }
        }

        // Centro do Visor (Sera conectado ao CameraX + Camera2 no Marco 3)
        Surface(
            color = Color(0xFF1A1A1A),
            border = BorderStroke(2.dp, SolarAmber),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 12.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(20.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Camera,
                    contentDescription = null,
                    tint = SolarAmber,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "VISOR PRO • PRONTO PARA HARDWARE",
                    color = SolarWhite,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Pressione o botão físico 'VOLUME -' do celular ou o botão abaixo para testar a captura na pasta '$activeFolder' com prefixo '$filePrefix'.",
                    color = SolarAmber,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = lastCaptureLog,
                    color = SolarGreen,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        // Botao de Disparo Solar na Tela
        Button(
            onClick = onTriggerCapture,
            colors = ButtonDefaults.buttonColors(containerColor = SolarAmber, contentColor = SolarBlack),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "CAPTURAR AGORA (OU VOLUME -)",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

@Composable
fun LanPlaceholderScreen(
    baseDirectory: String,
    foldersList: List<String>
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            color = SolarBlack,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "COMPARTILHAMENTO LAN (CELULAR & PC)",
                    color = SolarAmber,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Diretório Compartilhado: $baseDirectory (${foldersList.size} álbuns prontos)",
                    color = SolarWhite,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = SolarSurface),
            border = BorderStroke(2.dp, SolarBlack),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "STATUS DO MOTOR DE REDE LOCAL",
                    color = SolarBlack,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp
                )
                Text(
                    text = "Nos Marcos 4 e 5 ativaremos aqui o Servidor Ktor Local + Descoberta Automática de Dispositivos para transferência de álbuns entre celulares e navegador do PC.",
                    color = SolarBlack,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }
    }
}
