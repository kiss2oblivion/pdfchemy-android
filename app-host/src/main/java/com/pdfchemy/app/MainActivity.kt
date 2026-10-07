package com.pdfchemy.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.res.stringResource
import com.pdfchemy.app.R
import java.io.File
import android.util.Log
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfchemy.app.utils.AppLogger
import com.pdfchemy.app.ui.MainViewModel
import com.pdfchemy.app.logic.PdfAnalysis
import com.pdfchemy.app.ui.OrganizeCategoryScreen
import com.pdfchemy.app.ui.MergePdfScreen
import com.pdfchemy.app.ui.SplitPdfScreen
import com.pdfchemy.app.ui.DeletePagesScreen
import com.pdfchemy.app.ui.ExtractImagesScreen
import com.pdfchemy.app.ui.CheckCategoryScreen
import com.pdfchemy.app.ui.InspectMetadataScreen
import com.pdfchemy.app.ui.StripMetadataScreen
import com.pdfchemy.app.ui.TextCleanerScreen
import com.pdfchemy.app.ui.ImagesToPdfScreen
import com.pdfchemy.app.ui.ImageCompressorScreen
import com.pdfchemy.app.ui.RotatePdfScreen
import com.pdfchemy.app.ui.ExtractTextScreen
import com.pdfchemy.app.ui.ProtectPdfScreen
import com.pdfchemy.app.ui.UnlockPdfScreen
import com.pdfchemy.app.ui.VanguardScanningOverlay
import com.pdfchemy.app.ui.rememberVanguardPdfPicker
import com.pdfchemy.app.ui.rememberVanguardMultiplePdfPicker
import com.pdfchemy.app.ui.PdfToImagesScreen
import com.pdfchemy.app.ui.FillFormScreen
import com.pdfchemy.app.ui.OcrPdfScreen
import com.pdfchemy.app.ui.ReflowReaderScreen
import com.pdfchemy.app.ui.ScanPdfScreen
import com.pdfchemy.app.ui.SignPdfScreen
import com.pdfchemy.app.ui.PageOrganizerScreen
import com.pdfchemy.app.ui.WatermarkScreen
import com.pdfchemy.app.ui.PageNumberScreen
import com.pdfchemy.app.ui.PageLayoutScreen
import com.pdfchemy.app.ui.PdfCompareScreen
import com.pdfchemy.app.ui.textconverter.TextConverterViewModel
import com.pdfchemy.app.ui.textconverter.TextConverterScreen
import com.pdfchemy.app.ui.PdfEditorScreen
import com.pdfchemy.app.ui.ImageReplacerScreen
import com.pdfchemy.app.ui.FindAndReplaceScreen
import com.pdfchemy.app.ui.OfficeExportScreen
import com.pdfchemy.app.ui.PageCropperScreen
import com.pdfchemy.app.ui.MetadataSanitizerScreen
import com.pdfchemy.app.ui.MarkdownStudioScreen
import com.pdfchemy.app.ui.EbookConverterScreen
import com.pdfchemy.app.ui.FlattenPdfScreen
import com.pdfchemy.app.ui.BookletScreen
import com.pdfchemy.app.ui.RepairPdfScreen
import com.pdfchemy.app.ui.GrayscaleOptimizerScreen
import com.pdfchemy.app.ui.HeaderFooterScreen
import com.pdfchemy.app.ui.BookmarkEditorScreen
import com.pdfchemy.app.ui.RedactionScreen
import com.pdfchemy.app.ui.AttachmentManagerScreen
import com.pdfchemy.app.ui.LinearizePdfScreen
import com.pdfchemy.app.ui.NUpScreen
import com.pdfchemy.app.ui.PdfAValidatorScreen
import com.pdfchemy.app.ui.FontInspectorScreen
import com.pdfchemy.app.ui.DeskewScreen
import com.pdfchemy.app.ui.TableExtractorScreen
import com.pdfchemy.app.ui.DocumentSanitizerScreen
import com.pdfchemy.app.ui.QuickFillSignScreen
import com.pdfchemy.app.ui.FormBuilderScreen

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.text.font.FontWeight
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.*
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.pdfchemy.app.billing.AdManager

// --- Color Palette ---
val md_theme_light_primary = Color(0xFF0072B2)
val md_theme_light_onPrimary = Color(0xFFFFFFFF)
val md_theme_light_primaryContainer = Color(0xFFD6E2F0)
val md_theme_light_onPrimaryContainer = Color(0xFF001D36)
val md_theme_light_secondary = Color(0xFF009E73)
val md_theme_light_onSecondary = Color(0xFFFFFFFF)
val md_theme_light_secondaryContainer = Color(0xFFB3E6D8)
val md_theme_light_onSecondaryContainer = Color(0xFF002B1D)
val md_theme_light_tertiary = Color(0xFFD55E00)
val md_theme_light_onTertiary = Color(0xFFFFFFFF)
val md_theme_light_tertiaryContainer = Color(0xFFFFDBC7)
val md_theme_light_onTertiaryContainer = Color(0xFF451A00)
val md_theme_light_background = Color(0xFFF8F9FA)
val md_theme_light_onBackground = Color(0xFF1B1B1F)
val md_theme_light_surface = Color(0xFFFFFFFF)
val md_theme_light_onSurface = Color(0xFF1B1B1F)
val md_theme_light_surfaceVariant = Color(0xFFE1E2E8)
val md_theme_light_onSurfaceVariant = Color(0xFF44474E)
val md_theme_light_outline = Color(0xFF74777F)
val md_theme_light_error = Color(0xFFBA1A1A)
val md_theme_light_onError = Color(0xFFFFFFFF)

val md_theme_dark_primary = Color(0xFF00E5FF) // Neon Cyan
val md_theme_dark_onPrimary = Color(0xFF00363D)
val md_theme_dark_primaryContainer = Color(0xFF004F58)
val md_theme_dark_onPrimaryContainer = Color(0xFF99F5FF)
val md_theme_dark_secondary = Color(0xFFD500F9) // Magic Purple
val md_theme_dark_onSecondary = Color(0xFFFFFFFF) // High contrast white
val md_theme_dark_secondaryContainer = Color(0xFF7B0094)
val md_theme_dark_onSecondaryContainer = Color(0xFFF4B3FF)
val md_theme_dark_tertiary = Color(0xFFFFEA00) // Glowing Gold
val md_theme_dark_onTertiary = Color(0xFF332F00)
val md_theme_dark_tertiaryContainer = Color(0xFF4D4700)
val md_theme_dark_onTertiaryContainer = Color(0xFFFFF599)
val md_theme_dark_background = Color(0xFF0B0B11) // Deep Dark Navy/Charcoal
val md_theme_dark_onBackground = Color(0xFFE3E2E6)
val md_theme_dark_surface = Color(0xFF13131C) // Slightly lighter than bg
val md_theme_dark_onSurface = Color(0xFFE3E2E6)
val md_theme_dark_surfaceVariant = Color(0xFF282836)
val md_theme_dark_onSurfaceVariant = Color(0xFFC4C6D0)
val md_theme_dark_outline = Color(0xFF8E9099)
val md_theme_dark_error = Color(0xFFFF5449)
val md_theme_dark_onError = Color(0xFFFFFFFF)

val LightColors = lightColorScheme(
    primary = md_theme_light_primary,
    onPrimary = md_theme_light_onPrimary,
    primaryContainer = md_theme_light_primaryContainer,
    onPrimaryContainer = md_theme_light_onPrimaryContainer,
    secondary = md_theme_light_secondary,
    onSecondary = md_theme_light_onSecondary,
    secondaryContainer = md_theme_light_secondaryContainer,
    onSecondaryContainer = md_theme_light_onSecondaryContainer,
    tertiary = md_theme_light_tertiary,
    onTertiary = md_theme_light_onTertiary,
    tertiaryContainer = md_theme_light_tertiaryContainer,
    onTertiaryContainer = md_theme_light_onTertiaryContainer,
    background = md_theme_light_background,
    onBackground = md_theme_light_onBackground,
    surface = md_theme_light_surface,
    onSurface = md_theme_light_onSurface,
    surfaceVariant = md_theme_light_surfaceVariant,
    onSurfaceVariant = md_theme_light_onSurfaceVariant,
    outline = md_theme_light_outline,
    error = md_theme_light_error,
    onError = md_theme_light_onError
)

val DarkColors = darkColorScheme(
    primary = md_theme_dark_primary,
    onPrimary = md_theme_dark_onPrimary,
    primaryContainer = md_theme_dark_primaryContainer,
    onPrimaryContainer = md_theme_dark_onPrimaryContainer,
    secondary = md_theme_dark_secondary,
    onSecondary = md_theme_dark_onSecondary,
    secondaryContainer = md_theme_dark_secondaryContainer,
    onSecondaryContainer = md_theme_dark_onSecondaryContainer,
    tertiary = md_theme_dark_tertiary,
    onTertiary = md_theme_dark_onTertiary,
    tertiaryContainer = md_theme_dark_tertiaryContainer,
    onTertiaryContainer = md_theme_dark_onTertiaryContainer,
    background = md_theme_dark_background,
    onBackground = md_theme_dark_onBackground,
    surface = md_theme_dark_surface,
    onSurface = md_theme_dark_onSurface,
    surfaceVariant = md_theme_dark_surfaceVariant,
    onSurfaceVariant = md_theme_dark_onSurfaceVariant,
    outline = md_theme_dark_outline,
    error = md_theme_dark_error,
    onError = md_theme_dark_onError
)

private val defaultTypography = Typography()
val AppTypography = Typography(
    displayLarge = defaultTypography.displayLarge.copy(fontFamily = FontFamily.SansSerif),
    displayMedium = defaultTypography.displayMedium.copy(fontFamily = FontFamily.SansSerif),
    displaySmall = defaultTypography.displaySmall.copy(fontFamily = FontFamily.SansSerif),
    headlineLarge = defaultTypography.headlineLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold),
    headlineMedium = defaultTypography.headlineMedium.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold),
    headlineSmall = defaultTypography.headlineSmall.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold),
    titleLarge = defaultTypography.titleLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold),
    titleMedium = defaultTypography.titleMedium.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold),
    titleSmall = defaultTypography.titleSmall.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium),
    bodyLarge = defaultTypography.bodyLarge.copy(fontFamily = FontFamily.SansSerif),
    bodyMedium = defaultTypography.bodyMedium.copy(fontFamily = FontFamily.SansSerif),
    bodySmall = defaultTypography.bodySmall.copy(fontFamily = FontFamily.SansSerif),
    labelLarge = defaultTypography.labelLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium),
    labelMedium = defaultTypography.labelMedium.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium),
    labelSmall = defaultTypography.labelSmall.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium)
)

@Composable
fun ShrinkPdfTheme(
    useDarkTheme: Boolean = true, // Force Dark Theme
    content: @Composable () -> Unit
) {
    val colors = if (!useDarkTheme) {
        LightColors
    } else {
        DarkColors
    }

    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        content = content
    )
}

fun playFeedback(view: android.view.View, isHaptic: Boolean, isSfx: Boolean) {
    if (isSfx) {
        view.playSoundEffect(android.view.SoundEffectConstants.CLICK)
    }
    if (isHaptic) {
        view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
    }
}

class MainActivity : AppCompatActivity() {
    override fun onDestroy() {
        if (isFinishing) com.pdfchemy.app.utils.DocumentStager.releaseAll()
        super.onDestroy()
    }

    private val incomingPdfUriState = MutableStateFlow<Uri?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val uri = extractPdfUri(intent)
        if (uri != null) {
            incomingPdfUriState.value = uri
        }
    }

    private fun extractPdfUri(intent: Intent?): Uri? {
        if (intent == null) return null
        val action = intent.action
        if (Intent.ACTION_VIEW == action) {
            return intent.data
        } else if (Intent.ACTION_SEND == action) {
            val streamUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            return streamUri ?: intent.data
        } else if (Intent.ACTION_SEND_MULTIPLE == action) {
            val list = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            }
            return list?.firstOrNull() ?: intent.data
        }
        return null
    }

    private fun cleanupOrphanedCacheFiles(context: Context) {
        try {
            val oneHourAgo = System.currentTimeMillis() - (60 * 60 * 1000L)
            fun cleanDir(dir: File?) {
                if (dir == null || !dir.exists()) return
                val files = dir.listFiles() ?: return
                for (file in files) {
                    if (file.isDirectory) {
                        cleanDir(file)
                    } else if (file.isFile && file.lastModified() < oneHourAgo) {
                        val name = file.name.lowercase()
                        if (name.endsWith(".pdf") || name.endsWith(".epub") || name.endsWith(".cbz") ||
                            name.endsWith(".csv") || name.endsWith(".docx") || name.endsWith(".xlsx") || name.endsWith(".pptx") ||
                            name.startsWith("temp_") || name.startsWith("pdf_seekable_") || name.startsWith("scan_")) {
                            try { file.delete() } catch (_: Throwable) {}
                        }
                    }
                }
            }
            cleanDir(context.cacheDir)
            // Saved scans are user documents, not disposable cache files.
        } catch (e: Throwable) {
            AppLogger.w("Failed to clean orphaned cache files: ${e.message}")
        }
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        lifecycleScope.launch(Dispatchers.IO) {
            cleanupOrphanedCacheFiles(applicationContext)
        }
        handleIncomingIntent(intent)
        
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val isScreenshotRun = BuildConfig.DEBUG && intent.getBooleanExtra("isScreenshotRun", false)
        val hasConsentedInitial = if (isScreenshotRun) true else prefs.getBoolean("has_consented", false)
        val hasCompletedOnboardingInitial = if (isScreenshotRun) true else prefs.getBoolean("has_completed_onboarding", false)
        val themeModeInitial = if (isScreenshotRun) "DARK" else {
            prefs.getString("theme_mode", null) ?: if (prefs.contains("is_dark_theme")) {
                if (prefs.getBoolean("is_dark_theme", false)) "DARK" else "LIGHT"
            } else {
                "SYSTEM"
            }
        }

        if (!isScreenshotRun) {
            val consentInformation = UserMessagingPlatform.getConsentInformation(this)
            if (consentInformation.canRequestAds()) {
                MobileAds.initialize(this) {
                    AdManager.loadInterstitial(this)
                }
            }

            val debugSettings = ConsentDebugSettings.Builder(this)
                .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
                .addTestDeviceHashedId("TEST-EMULATOR")
                .build()

            val params = ConsentRequestParameters.Builder()
                // Uncomment the next line to force the GDPR form for testing
                // .setConsentDebugSettings(debugSettings) 
                .build()

            consentInformation.requestConsentInfoUpdate(
                this,
                params,
                {
                    UserMessagingPlatform.loadAndShowConsentFormIfRequired(
                        this
                    ) { loadAndShowError ->
                        if (loadAndShowError != null) {
                            Log.w("UMP", "${loadAndShowError.errorCode}: ${loadAndShowError.message}")
                        }
                        if (consentInformation.canRequestAds()) {
                            MobileAds.initialize(this) {
                                AdManager.loadInterstitial(this)
                            }
                        }
                    }
                },
                { requestConsentError ->
                    Log.w("UMP", "${requestConsentError.errorCode}: ${requestConsentError.message}")
                    if (consentInformation.canRequestAds()) {
                        MobileAds.initialize(this) {
                            AdManager.loadInterstitial(this)
                        }
                    }
                }
            )
        }

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            var themeMode by remember { mutableStateOf(themeModeInitial) }
            val systemInDark = isSystemInDarkTheme()
            val useDark = when (themeMode) {
                "LIGHT" -> false
                "DARK" -> true
                else -> systemInDark
            }
            var hasConsented by remember { mutableStateOf(hasConsentedInitial) }
            var hasCompletedOnboarding by remember { mutableStateOf(hasCompletedOnboardingInitial) }
            var showTourManually by remember { mutableStateOf(false) }
            
            ShrinkPdfTheme(useDarkTheme = useDark) {
                if (!hasConsented) {
                    AlertDialog(
                        onDismissRequest = { /* Must explicitly decide to continue */ },
                        title = { Text(stringResource(R.string.consent_dialog_title)) },
                        text = { Text(stringResource(R.string.consent_dialog_body)) },
                        confirmButton = {
                            Button(onClick = {
                                prefs.edit().putBoolean("has_consented", true).apply()
                                hasConsented = true
                            }) {
                                Text(stringResource(R.string.consent_dialog_agree))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                // They decline personalization/ads, but can still use the app locally
                                prefs.edit().putBoolean("has_consented", true).apply()
                                hasConsented = true
                            }) {
                                Text(stringResource(R.string.consent_dialog_decline))
                            }
                        },
                        properties = androidx.compose.ui.window.DialogProperties(
                            dismissOnBackPress = false,
                            dismissOnClickOutside = false
                        )
                    )
                }

                if (hasConsented && (!hasCompletedOnboarding || showTourManually)) {
                    OnboardingWalkthroughDialog(
                        onDismiss = {
                            hasCompletedOnboarding = true
                            prefs.edit().putBoolean("has_completed_onboarding", true).apply()
                            showTourManually = false
                        }
                    )
                }

                MainApp(
                    windowWidthSizeClass = windowSizeClass.widthSizeClass,
                    incomingPdfUriState = incomingPdfUriState,
                    isDarkTheme = useDark,
                    themeMode = themeMode,
                    hasConsented = hasConsented,
                    isScreenshotRun = isScreenshotRun,
                    onChangeThemeMode = { newMode ->
                        themeMode = newMode
                        prefs.edit().putString("theme_mode", newMode).apply()
                    },
                    onOpenTour = {
                        showTourManually = true
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PremiumTopAppBar(
    title: String,
    onBack: (() -> Unit)? = null,
    isDarkTheme: Boolean = isSystemInDarkTheme()
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds()
            .background(MaterialTheme.colorScheme.primaryContainer)
    ) {
        TopAppBar(
            title = { Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                scrolledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ),
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.desc_back), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
        )
    }
}

@Composable
fun MainApp(
    windowWidthSizeClass: WindowWidthSizeClass = WindowWidthSizeClass.Compact,
    viewModel: MainViewModel = viewModel(),
    textConverterViewModel: TextConverterViewModel = viewModel(),
    incomingPdfUriState: MutableStateFlow<Uri?>? = null,
    isDarkTheme: Boolean = false,
    themeMode: String = "SYSTEM",
    hasConsented: Boolean = false,
    isScreenshotRun: Boolean = false,
    onChangeThemeMode: (String) -> Unit = {},
    onOpenTour: () -> Unit = {}
) {
    var currentScreen by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf<Screen>(Screen.Home) }
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val incomingPdfUri by (incomingPdfUriState?.collectAsState() ?: remember { mutableStateOf<Uri?>(null) })
    val isVanguardEnabled by viewModel.isVanguardEnabled.collectAsState()
    var showVanguardBlockedDialog by remember { mutableStateOf(false) }
    var showVanguardEncryptedDialog by remember { mutableStateOf(false) }
    var vanguardPendingEncryptedUri by remember { mutableStateOf<Uri?>(null) }
    var isVanguardScanning by remember { mutableStateOf(false) }
    var vanguardScanningFileName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(incomingPdfUri) {
        com.pdfchemy.app.ui.guardDocumentLoad(onFailure = {
            showVanguardBlockedDialog = true
            isVanguardScanning = false
            vanguardScanningFileName = null
            incomingPdfUriState?.value = null
        }) {
            incomingPdfUri?.let { originalUri ->
                val uri = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.pdfchemy.app.utils.DocumentStager.stageDocumentCancellable(context, originalUri).uri }
                val name = com.pdfchemy.app.utils.FileUtils.getFileName(context, uri)?.lowercase() ?: ""
                if (name.endsWith(".epub")) {
                    currentScreen = Screen.ReflowReader(initialUri = uri)
                } else if (name.endsWith(".cbz") || name.endsWith(".cbr")) {
                    currentScreen = Screen.EbookConverter
                } else {
                    if (isVanguardEnabled) {
                        isVanguardScanning = true
                        vanguardScanningFileName = com.pdfchemy.app.utils.FileUtils.getFileName(context, uri)
                        try {
                            val threatResult = com.pdfchemy.app.logic.PdfSanitizerEngine.checkVanguardThreat(context, uri)
                            when (threatResult) {
                                is com.pdfchemy.app.logic.VanguardThreatResult.Clean -> {
                                    currentScreen = Screen.PdfReader(initialPdfUri = uri)
                                }
                                is com.pdfchemy.app.logic.VanguardThreatResult.EncryptedCannotVerify -> {
                                    vanguardPendingEncryptedUri = uri
                                    showVanguardEncryptedDialog = true
                                }
                                is com.pdfchemy.app.logic.VanguardThreatResult.ExecutableThreat,
                                is com.pdfchemy.app.logic.VanguardThreatResult.ParseFailed -> {
                                    showVanguardBlockedDialog = true
                                }
                            }
                        } finally {
                            isVanguardScanning = false
                            vanguardScanningFileName = null
                        }
                    } else {
                        currentScreen = Screen.PdfReader(initialPdfUri = uri)
                    }
                }
                incomingPdfUriState?.value = null
            }
        }
    }

    if (showVanguardBlockedDialog) {
        AlertDialog(
            onDismissRequest = { showVanguardBlockedDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.vanguard_blocked_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.vanguard_blocked_message),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = { showVanguardBlockedDialog = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }

    if (showVanguardEncryptedDialog) {
        AlertDialog(
            onDismissRequest = {
                showVanguardEncryptedDialog = false
                vanguardPendingEncryptedUri = null
            },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.vanguard_encrypted_title),
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.vanguard_encrypted_message),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showVanguardEncryptedDialog = false
                        vanguardPendingEncryptedUri?.let { uri ->
                            currentScreen = Screen.UnlockPdf(initialUri = uri)
                        }
                        vanguardPendingEncryptedUri = null
                    }
                ) {
                    Text(stringResource(R.string.vanguard_action_unlock))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showVanguardEncryptedDialog = false
                        vanguardPendingEncryptedUri = null
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    VanguardScanningOverlay(
        visible = isVanguardScanning,
        fileName = vanguardScanningFileName
    )
    
    LaunchedEffect(isScreenshotRun) {
        if (isScreenshotRun) {
            viewModel.setPremiumForScreenshot()
        }
    }
    
    LaunchedEffect(Unit) {
        viewModel.initBilling(context)
    }

    val isPremiumRaw by viewModel.isPremium.collectAsState()
    val isPremium = isPremiumRaw || isScreenshotRun

    val safeguardAssessment by viewModel.safeguardAssessment.collectAsState()
    safeguardAssessment?.let { assessment ->
        HardwareSafeguardDialog(
            assessment = assessment,
            onDismiss = { viewModel.dismissSafeguardAssessment() },
            onAction = { action ->
                viewModel.dismissSafeguardAssessment()
                when (action) {
                    com.pdfchemy.app.logic.DeviceGuard.AlternativeAction.SPLIT_FIRST -> {
                        currentScreen = Screen.SplitPdf
                    }
                    com.pdfchemy.app.logic.DeviceGuard.AlternativeAction.PAGE_RANGE -> {
                        currentScreen = Screen.PageOrganizer
                    }
                    com.pdfchemy.app.logic.DeviceGuard.AlternativeAction.LIGHTWEIGHT_MODE -> {
                        viewModel.setUseGrayscale(true)
                        viewModel.setQuality(0.35f)
                    }
                    com.pdfchemy.app.logic.DeviceGuard.AlternativeAction.FREE_RAM -> {}
                }
            },
            onProceedAnyway = {
                viewModel.dismissSafeguardAssessment()
            }
        )
    }
    
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
            

        AnimatedContent(
            targetState = currentScreen,
            transitionSpec = {
                if (targetState == Screen.Home) {
                    (slideInHorizontally(
                        initialOffsetX = { -it / 6 },
                        animationSpec = tween(260, easing = FastOutSlowInEasing)
                    ) + fadeIn(animationSpec = tween(260))).togetherWith(
                        slideOutHorizontally(
                            targetOffsetX = { it / 4 },
                            animationSpec = tween(220, easing = FastOutSlowInEasing)
                        ) + fadeOut(animationSpec = tween(180))
                    )
                } else {
                    (slideInHorizontally(
                        initialOffsetX = { it / 4 },
                        animationSpec = tween(260, easing = FastOutSlowInEasing)
                    ) + fadeIn(animationSpec = tween(260))).togetherWith(
                        slideOutHorizontally(
                            targetOffsetX = { -it / 6 },
                            animationSpec = tween(220, easing = FastOutSlowInEasing)
                        ) + fadeOut(animationSpec = tween(180))
                    )
                }
            },
            label = "screenTransition",
            modifier = Modifier.fillMaxSize()
        ) { targetScreen ->
            when (targetScreen) {
                // =========================================================================================
                // 🏠 CORE NAVIGATION & SETTINGS
                // =========================================================================================
                Screen.Home -> HomeScreen(
                    windowWidthSizeClass = windowWidthSizeClass,
                    isDarkTheme = isDarkTheme,
                    onToggleTheme = {
                        val nextMode = when (themeMode) {
                            "SYSTEM" -> "LIGHT"
                            "LIGHT" -> "DARK"
                            else -> "SYSTEM"
                        }
                        onChangeThemeMode(nextMode)
                    },
                    onNavigate = { screen -> currentScreen = screen },
                    viewModel = viewModel
                )
                Screen.Settings -> SettingsScreen(
                    viewModel = viewModel,
                    themeMode = themeMode,
                    onChangeThemeMode = onChangeThemeMode,
                    onOpenTour = onOpenTour
                ) { currentScreen = Screen.Home }
                Screen.CompressCategory -> CompressCategoryScreen(onNavigate = { screen -> currentScreen = screen }, onBack = { currentScreen = Screen.Home })
                Screen.CreateCategory -> CreateCategoryScreen(onNavigate = { screen -> currentScreen = screen }, onBack = { currentScreen = Screen.Home })
                Screen.OrganizeCategory -> OrganizeCategoryScreen(onNavigate = { screen -> currentScreen = screen }, onBack = { currentScreen = Screen.Home })
                Screen.CheckCategory -> CheckCategoryScreen(onNavigate = { screen -> currentScreen = screen }, onBack = { currentScreen = Screen.Home })
                Screen.Premium -> PremiumUpgradeScreen(viewModel) { currentScreen = Screen.Home }

                // =========================================================================================
                // 1. 🗜️ COMPRESSION & OPTIMIZATION (FEATURES_REGISTRY Section 1)
                // =========================================================================================
                // [FEATURE: PDF Compressor] — 4 Presets (Extreme, Recommended, High Quality, Custom DPI/Quality)
                Screen.CompressPdf -> CompressPdfScreen(viewModel, 0, isScreenshotRun) { currentScreen = Screen.CompressCategory }
                // [FEATURE: Batch PDF Compressor] — Parallel multi-file background compression
                Screen.BatchCompressPdf -> CompressPdfScreen(viewModel, 1, isScreenshotRun) { currentScreen = Screen.CompressCategory }
                // [FEATURE: Image Compressor] — Compress standalone JPEG/PNG/WEBP photos
                Screen.ImageCompressor -> ImageCompressorScreen(viewModel) { currentScreen = Screen.CompressCategory }
                // [FEATURE: Grayscale Optimizer] — Converts color PDF streams to monochrome/grayscale
                Screen.GrayscaleOptimizer -> GrayscaleOptimizerScreen(viewModel) { currentScreen = Screen.OrganizeCategory }
                // [FEATURE: Linearize (Fast Web View)] — Restructures PDF dictionary for instant streaming
                Screen.LinearizePdf -> LinearizePdfScreen(viewModel) { currentScreen = Screen.OrganizeCategory }
                // [FEATURE: Flatten PDF] — Permanently bakes form fields, comments, and annotations into page layer
                Screen.FlattenPdf -> FlattenPdfScreen(viewModel) { currentScreen = Screen.CheckCategory }

                // =========================================================================================
                // 2. 📑 PAGE STUDIO & ORGANIZATION (FEATURES_REGISTRY Section 2)
                // =========================================================================================
                // [FEATURE: Merge PDFs] — Multi-document combiner with drag-and-drop reordering
                Screen.MergePdf -> MergePdfScreen(viewModel) { currentScreen = Screen.OrganizeCategory }
                // [FEATURE: Split PDFs (Range / All)] — Split into individual pages or arbitrary page ranges
                Screen.SplitPdf -> SplitPdfScreen(viewModel) { currentScreen = Screen.OrganizeCategory }
                // [FEATURE: Page Organizer] — Visual thumbnail grid: reorder, delete, duplicate, rotate
                Screen.PageOrganizer -> PageOrganizerScreen(viewModel) { currentScreen = Screen.OrganizeCategory }
                // [FEATURE: Delete Pages] — Visual single-page or multi-page removal
                Screen.DeletePages -> DeletePagesScreen(viewModel) { currentScreen = Screen.OrganizeCategory }
                // [FEATURE: Rotate Pages] — Lossless 90°, 180°, 270° orientation correction
                Screen.RotatePdf -> RotatePdfScreen(viewModel) { currentScreen = Screen.OrganizeCategory }
                // [FEATURE: Auto-Deskew & Straighten] — Hough transform scan tilt auto-correction
                Screen.Deskew -> DeskewScreen(viewModel) { currentScreen = Screen.OrganizeCategory }
                // [FEATURE: Page Cropper & Margin Trimmer] — CropBox adjustment to remove scanner borders
                Screen.CropPdf -> PageCropperScreen(viewModel) { currentScreen = Screen.OrganizeCategory }
                // [FEATURE: Paper Canvas Resizer] — Standard paper dimension imposition (A4, Letter, Legal, A3)
                Screen.PageLayout -> PageLayoutScreen(viewModel) { currentScreen = Screen.OrganizeCategory }
                // [FEATURE: N-Up Handouts] — Imposes 2, 4, 6, 9, 16 pages per sheet with border guides
                Screen.NUp -> NUpScreen(viewModel) { currentScreen = Screen.OrganizeCategory }
                // [FEATURE: Booklet Imposition] — Saddle-stitch fold printer ordering
                Screen.Booklet -> BookletScreen(viewModel) { currentScreen = Screen.OrganizeCategory }

                // =========================================================================================
                // 3. 🔄 CREATION & CONVERSION (FEATURES_REGISTRY Section 3)
                // =========================================================================================
                // [FEATURE: Images to PDF] — Converts camera photos, receipts, and gallery images to PDF
                Screen.ImagesToPdf -> ImagesToPdfScreen(viewModel) { currentScreen = Screen.CreateCategory }
                // [FEATURE: PDF to High-Res Images] — Exports pages as PNG or JPEG image files
                Screen.PdfToImages -> PdfToImagesScreen(viewModel) { currentScreen = Screen.CreateCategory }
                // [FEATURE: Scan to PDF] — Hardware camera scan with edge auto-detection and perspective correction
                Screen.ScanPdf -> ScanPdfScreen(viewModel, onNavigateToTool = { currentScreen = it }) { currentScreen = Screen.CreateCategory }
                // [FEATURE: EPUB to PDF Converter] — Standard EPUB parsing with fonts/margins to paginated PDF
                Screen.EbookConverter -> EbookConverterScreen(viewModel) { currentScreen = Screen.CreateCategory }
                // [FEATURE: Markdown to PDF Studio] — Rich Markdown editor with live preview
                Screen.MarkdownStudio -> MarkdownStudioScreen(viewModel) { currentScreen = Screen.CreateCategory }
                // [FEATURE: Text to PDF Converter] — Converts .txt, source code, and logs into paginated PDF
                Screen.TextToPdf -> TextToPdfScreen(viewModel) { currentScreen = Screen.CreateCategory }
                // [FEATURE: Text Format Converter] — Inter-converts between TXT, RTF, and HTML
                Screen.TextConverter -> TextConverterScreen(textConverterViewModel, viewModel) { currentScreen = Screen.CreateCategory }
                // [FEATURE: Table Extractor to CSV] — 2D column clustering: extracts tables to RFC 4180 CSV / Excel
                Screen.TableExtractor -> TableExtractorScreen(viewModel) { currentScreen = Screen.CreateCategory }
                // [FEATURE: Office Export (Word / Excel / PPTX)] — Pure OpenXML generators: PDF to DOCX, XLSX, PPTX
                is Screen.OfficeExport -> OfficeExportScreen((targetScreen as Screen.OfficeExport).initialFormat, viewModel) { currentScreen = Screen.CreateCategory }
                // [FEATURE: On-Device OCR] — 100% offline optical character recognition
                Screen.OcrPdf -> OcrPdfScreen(viewModel) { currentScreen = Screen.CreateCategory }
                // [FEATURE: Extract Text & Images] — Strips raw uncompressed text and raster bitmaps
                Screen.ExtractText -> ExtractTextScreen(viewModel) { currentScreen = Screen.CheckCategory }
                Screen.ExtractImages -> ExtractImagesScreen(viewModel) { currentScreen = Screen.OrganizeCategory }

                // =========================================================================================
                // 4. ✍️ FORM FILLING & DOCUMENT EDITING (FEATURES_REGISTRY Section 4)
                // =========================================================================================
                // [FEATURE: Visual PDF Editor] — Pen, highlighter, text overlays, shape rectangles, signature stamp
                is Screen.PdfEditor -> {
                    val editorScreen = targetScreen as Screen.PdfEditor
                    PdfEditorScreen(viewModel, editorScreen.initialPdfUri) {
                        currentScreen = if (editorScreen.initialPdfUri != null) Screen.Home else Screen.OrganizeCategory
                    }
                }
                is Screen.PdfReader -> {
                    val readerScreen = targetScreen as Screen.PdfReader
                    com.pdfchemy.app.ui.PdfReaderScreen(
                        viewModel = viewModel,
                        initialUri = readerScreen.initialPdfUri,
                        onBack = { currentScreen = if (readerScreen.initialPdfUri != null) Screen.Home else Screen.OrganizeCategory },
                        onNavigateToTool = { screen -> currentScreen = screen }
                    )
                }
                // [FEATURE: Quick Fill & Sign] — Flat PDF tap-to-place annotations (Text, ✓, ✗, Date, Signatures)
                Screen.QuickFillSign -> QuickFillSignScreen(viewModel) { currentScreen = Screen.CreateCategory }
                // [FEATURE: Interactive Form Builder] — Converts flat PDFs into genuine fillable forms (PDAcroForm)
                Screen.FormBuilder -> FormBuilderScreen { currentScreen = Screen.CreateCategory }
                // [FEATURE: AcroForm Interactive Filler] — Inspects and fills standard interactive PDF forms
                Screen.FillForm -> FillFormScreen(viewModel) { currentScreen = Screen.CreateCategory }
                // [FEATURE: Visual Signer] — Draw smoothed vector signatures and place on page
                Screen.SignPdf -> SignPdfScreen(viewModel) { currentScreen = Screen.OrganizeCategory }
                // [FEATURE: Watermark Studio] — Custom text/image watermarks with opacity and diagonal tiling
                Screen.Watermark -> WatermarkScreen(viewModel) { currentScreen = Screen.OrganizeCategory }
                // [FEATURE: Header & Footer Studio] — Running headers and footers with custom margins
                Screen.HeaderFooter -> HeaderFooterScreen(viewModel) { currentScreen = Screen.OrganizeCategory }
                // [FEATURE: Page Numbering Studio] — Page numbers (Page X of Y, X/Y), position, font, start offset
                Screen.PageNumber -> PageNumberScreen(viewModel) { currentScreen = Screen.OrganizeCategory }
                // [FEATURE: Bookmark Editor] — Edit document outline tree and table of contents
                Screen.BookmarkEditor -> BookmarkEditorScreen(viewModel) { currentScreen = Screen.OrganizeCategory }
                // [FEATURE: Find & Replace Text] — Text search across pages with replacement or redaction
                Screen.FindAndReplaceText -> FindAndReplaceScreen(viewModel) { currentScreen = Screen.OrganizeCategory }
                // [FEATURE: Image Replacer] — Replace embedded raster objects in PDF stream
                Screen.ImageReplacer -> ImageReplacerScreen(viewModel) { currentScreen = Screen.OrganizeCategory }

                // =========================================================================================
                // 5. 🛡️ SECURITY, PRIVACY & COMPLIANCE (FEATURES_REGISTRY Section 5)
                // =========================================================================================
                // [FEATURE: Encrypt / Password Protect] — AES-128 / AES-256 standard PDF encryption
                Screen.ProtectPdf -> ProtectPdfScreen(viewModel) { currentScreen = Screen.CheckCategory }
                is Screen.UnlockPdf -> UnlockPdfScreen(viewModel, targetScreen.initialUri) { currentScreen = Screen.CheckCategory }
                // [FEATURE: Permanent Smart Redaction] — PII auto-detection (emails, phones, SSNs) + stream scrubbing
                Screen.Redaction -> RedactionScreen(viewModel) { currentScreen = Screen.CheckCategory }
                // [FEATURE: Deep Threat Sanitizer] — Strips embedded JavaScript, launch actions, URI tracking beacons
                Screen.DocumentSanitizer -> DocumentSanitizerScreen(viewModel) { currentScreen = Screen.CheckCategory }
                // [FEATURE: Metadata Sanitizer] — Purges author name, software creator, GPS coordinates, history
                Screen.MetadataSanitizer -> MetadataSanitizerScreen(viewModel) { currentScreen = Screen.CheckCategory }
                Screen.InspectMetadata -> InspectMetadataScreen(viewModel) { currentScreen = Screen.CheckCategory }
                Screen.StripMetadata -> StripMetadataScreen(viewModel) { currentScreen = Screen.CheckCategory }
                Screen.TextCleaner -> TextCleanerScreen { currentScreen = Screen.CheckCategory }
                // [FEATURE: PDF/A Preflight Validator] — Audits ISO 19005 compliance (OutputIntents, fonts, XMP)
                Screen.PdfAValidator -> PdfAValidatorScreen(viewModel) { currentScreen = Screen.CheckCategory }
                // [FEATURE: Typography & Font Inspector] — Lists embedded font programs, TrueType/Type1, subsets
                Screen.FontInspector -> FontInspectorScreen(viewModel) { currentScreen = Screen.CheckCategory }
                // [FEATURE: Embedded Attachments Manager] — Inspects, extracts, and embeds arbitrary file attachments
                Screen.AttachmentManager -> AttachmentManagerScreen(viewModel) { currentScreen = Screen.OrganizeCategory }
                // [FEATURE: PDF Repair Studio] — Reconstructs broken cross-reference tables and truncated trailers
                Screen.RepairPdf -> RepairPdfScreen(viewModel) { currentScreen = Screen.CheckCategory }
                // [FEATURE: Document Visual Comparison] — Side-by-side synchronized comparison & textual diffs
                Screen.ComparePdf -> PdfCompareScreen(viewModel) { currentScreen = Screen.CheckCategory }

                // =========================================================================================
                // 6. 📖 READING & ACCESSIBILITY (FEATURES_REGISTRY Section 6)
                // =========================================================================================
                // [FEATURE: Reflow Reader Studio & Offline TTS] — E-reader reflow, themes, 100% offline TTS
                is Screen.ReflowReader -> {
                    val reflowScreen = targetScreen as Screen.ReflowReader
                    ReflowReaderScreen(viewModel = viewModel, initialUri = reflowScreen.initialUri) { currentScreen = Screen.OrganizeCategory }
                }
            }
        }

        if (uiState is MainViewModel.UiState.Processing || uiState is MainViewModel.UiState.BatchProcessing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    if (uiState is MainViewModel.UiState.BatchProcessing) {
                        val state = uiState as MainViewModel.UiState.BatchProcessing
                        Text(
                            text = stringResource(R.string.batch_compressing, state.current, state.total),
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = state.currentFileName,
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { state.current.toFloat() / state.total },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.White.copy(alpha = 0.3f)
                        )
                    } else {
                        val state = uiState as MainViewModel.UiState.Processing
                        val label = if (state.taskNameResId != null) stringResource(state.taskNameResId) else stringResource(R.string.compressing_pdf)
                        Text(
                            text = label,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { viewModel.cancelOperation(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        }
        // Dynamic Result Dialog
        when (val state = uiState) {
            is MainViewModel.UiState.Success -> {
                val isPremium by viewModel.isPremium.collectAsState()
                val activity = context as? android.app.Activity
                val resetAndShowAd = {
                    if (activity != null) {
                        AdManager.showInterstitialIfReady(activity, isPremium) { viewModel.resetState() }
                    } else {
                        viewModel.resetState()
                    }
                }
                AlertDialog(
                    onDismissRequest = resetAndShowAd,
                    title = { Text(state.title) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(state.message)
                            Spacer(modifier = Modifier.height(16.dp))
                            if (state.outputUris.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = { 
                                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                            setDataAndType(state.outputUris.first(), com.pdfchemy.app.utils.FileUtils.getMimeType(context, state.outputUris.first()))
                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        try { context.startActivity(intent) } catch (e: Exception) { }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(stringResource(R.string.btn_open_file)) }
                                OutlinedButton(
                                    onClick = { com.pdfchemy.app.logic.ShareUtil.shareFiles(context, state.outputUris, context.getString(R.string.desc_share_output)) },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(stringResource(R.string.share)) }
                            }
                            OutlinedButton(
                                onClick = resetAndShowAd,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Another Operation") }
                            Button(
                                onClick = { 
                                    resetAndShowAd()
                                    currentScreen = Screen.Home
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Home") }
                        }
                    },
                    confirmButton = {}
                )
            }
            is MainViewModel.UiState.Warning -> {
                AlertDialog(
                    onDismissRequest = { viewModel.resetState() },
                    title = { Text(state.title, color = MaterialTheme.colorScheme.error) },
                    text = { Text(state.message) },
                    confirmButton = {
                        TextButton(onClick = { viewModel.resetState() }) {
                            Text(stringResource(R.string.ok))
                        }
                    },
                    dismissButton = {
                        if (state.outputUris.isNotEmpty()) {
                            TextButton(onClick = { 
                                com.pdfchemy.app.logic.ShareUtil.shareFiles(context, state.outputUris, context.getString(R.string.desc_share_output))
                            }) {
                                Text(stringResource(R.string.share))
                            }
                        }
                    }
                )
            }
            is MainViewModel.UiState.Error -> {
                var showDiagnostics by remember { mutableStateOf(false) }
                AlertDialog(
                    onDismissRequest = { viewModel.resetState() },
                    icon = { Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    title = { Text(stringResource(R.string.error), fontWeight = FontWeight.Bold) },
                    text = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            if (!state.technicalDetails.isNullOrBlank()) {
                                OutlinedCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp),
                                    onClick = { showDiagnostics = !showDiagnostics }
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    Icons.Rounded.BugReport,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = stringResource(R.string.error_developer_diagnostics),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                            Icon(
                                                if (showDiagnostics) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        if (showDiagnostics) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Surface(
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(6.dp),
                                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                                            ) {
                                                Column(modifier = Modifier.padding(8.dp)) {
                                                    Text(
                                                        text = state.technicalDetails,
                                                        style = MaterialTheme.typography.bodySmall.copy(
                                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                            fontSize = 11.sp
                                                        ),
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                    TextButton(
                                                        onClick = {
                                                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                                            val clip = android.content.ClipData.newPlainText("PDFchemy Error Trace", state.technicalDetails)
                                                            clipboard?.setPrimaryClip(clip)
                                                            Toast.makeText(context, context.getString(R.string.error_diagnostics_copied), Toast.LENGTH_SHORT).show()
                                                        },
                                                        modifier = Modifier.align(Alignment.End)
                                                    ) {
                                                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text(stringResource(R.string.error_copy_diagnostics), style = MaterialTheme.typography.labelSmall)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = { viewModel.resetState() }) {
                            Text(stringResource(R.string.ok))
                        }
                    }
                )
            }
            else -> {}
        }

        val warning by viewModel.warning.collectAsState()
        LaunchedEffect(warning) {
            warning?.let {
                Toast.makeText(context, it, Toast.LENGTH_LONG).show()
                viewModel.dismissWarning()
            }
        }
        }
            val isReaderActive = currentScreen is Screen.PdfReader || currentScreen is Screen.ReflowReader
            if (!isPremium && hasConsented && !isScreenshotRun && !isReaderActive) {
                BannerAdView()
            }
        }
    }
}

@Composable
fun BannerAdView(modifier: Modifier = Modifier) {
    if (!rememberAdsAllowed()) return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = Modifier.wrapContentSize(),
            factory = { ctx ->
                com.google.android.gms.ads.AdView(ctx).apply {
                    setAdSize(com.google.android.gms.ads.AdSize.BANNER)
                    adUnitId = com.pdfchemy.app.BuildConfig.BANNER_AD_UNIT_ID
                    loadAd(com.google.android.gms.ads.AdRequest.Builder().build())
                }
            },
            onRelease = { adView ->
                adView.destroy()
            }
        )
    }
}

@Composable
private fun rememberAdsAllowed(): Boolean {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(UserMessagingPlatform.getConsentInformation(context).canRequestAds()) }
    LaunchedEffect(context) {
        while (true) {
            delay(1000)
            allowed = UserMessagingPlatform.getConsentInformation(context).canRequestAds()
        }
    }
    return allowed
}

// =============================================================================================
// MASTER SCREENS REGISTRY (Mapped 1:1 to FEATURES_REGISTRY.md)
// =============================================================================================
sealed class Screen {
    // Core Navigation & Settings
    object Home : Screen()
    object Settings : Screen()
    object Premium : Screen()
    object CompressCategory : Screen()
    object CreateCategory : Screen()
    object OrganizeCategory : Screen()
    object CheckCategory : Screen()

    // 1. 🗜️ COMPRESSION & OPTIMIZATION (FEATURES_REGISTRY Section 1)
    // [FEATURE: PDF Compressor] — Presets & custom DPI/Quality
    object CompressPdf : Screen()
    // [FEATURE: Batch PDF Compressor] — Multi-file compression
    object BatchCompressPdf : Screen()
    // [FEATURE: Image Compressor] — Standalone photo compression
    object ImageCompressor : Screen()
    // [FEATURE: Grayscale Optimizer] — Monochrome stream conversion
    object GrayscaleOptimizer : Screen()
    // [FEATURE: Linearize (Fast Web View)] — Web streaming dictionary structure
    object LinearizePdf : Screen()
    // [FEATURE: Flatten PDF] — Bake form fields and annotations
    object FlattenPdf : Screen()

    // 2. 📑 PAGE STUDIO & ORGANIZATION (FEATURES_REGISTRY Section 2)
    // [FEATURE: Merge PDFs] — Multi-document combiner
    object MergePdf : Screen()
    // [FEATURE: Split PDFs] — Range or single page split
    object SplitPdf : Screen()
    // [FEATURE: Page Organizer] — Visual thumbnail grid
    object PageOrganizer : Screen()
    // [FEATURE: Delete Pages] — Visual page deletion
    object DeletePages : Screen()
    // [FEATURE: Rotate Pages] — Orientation correction
    object RotatePdf : Screen()
    // [FEATURE: Auto-Deskew & Straighten] — Scan tilt correction
    object Deskew : Screen()
    // [FEATURE: Page Cropper & Margin Trimmer] — Scanner border trimmer
    object CropPdf : Screen()
    // [FEATURE: Paper Canvas Resizer] — Standard paper imposition
    object PageLayout : Screen()
    // [FEATURE: N-Up Handouts] — 2, 4, 6, 9, 16 pages per sheet
    object NUp : Screen()
    // [FEATURE: Booklet Imposition] — Saddle-stitch fold ordering
    object Booklet : Screen()

    // 3. 🔄 CREATION & CONVERSION (FEATURES_REGISTRY Section 3)
    // [FEATURE: Images to PDF] — Gallery photos & receipts to PDF
    object ImagesToPdf : Screen()
    // [FEATURE: PDF to High-Res Images] — Export pages to PNG/JPEG
    object PdfToImages : Screen()
    // [FEATURE: Scan to PDF] — Camera scan with edge detection
    object ScanPdf : Screen()
    // [FEATURE: EPUB to PDF Converter] — Standard EPUB book conversion
    object EbookConverter : Screen()
    // [FEATURE: Markdown to PDF Studio] — Markdown editor & PDF compiler
    object MarkdownStudio : Screen()
    // [FEATURE: Text to PDF Converter] — Plain text to paginated PDF
    object TextToPdf : Screen()
    // [FEATURE: Text Format Converter] — TXT / RTF / HTML converter
    object TextConverter : Screen()
    // [FEATURE: Table Extractor to CSV] — 2D column clustering to RFC 4180 CSV
    object TableExtractor : Screen()
    // [FEATURE: Office Export] — Word (.docx), Excel (.xlsx), PowerPoint (.pptx)
    data class OfficeExport(val initialFormat: com.pdfchemy.app.logic.OfficeFormat = com.pdfchemy.app.logic.OfficeFormat.WORD) : Screen()
    // [FEATURE: On-Device OCR] — Offline OCR text layer generator
    object OcrPdf : Screen()
    // [FEATURE: Extract Text & Images] — Stream object dump
    object ExtractText : Screen()
    object ExtractImages : Screen()

    // 4. ✍️ FORM FILLING & DOCUMENT EDITING (FEATURES_REGISTRY Section 4)
    // [FEATURE: Visual PDF Editor] — Pen, highlighter, text, shapes, stamp
    data class PdfEditor(val initialPdfUri: Uri? = null) : Screen()
    data class PdfReader(val initialPdfUri: Uri? = null) : Screen()
    // [FEATURE: Quick Fill & Sign] — Flat PDF tap-to-place marks (Text, ✓, ✗, Date, Signatures)
    object QuickFillSign : Screen()
    // [FEATURE: Interactive Form Builder] — PDAcroForm widget authoring (Text, Checkbox, Dropdown)
    object FormBuilder : Screen()
    // [FEATURE: AcroForm Interactive Filler] — Fill standard interactive forms
    object FillForm : Screen()
    // [FEATURE: Visual Signer] — Smooth vector signature placement
    object SignPdf : Screen()
    // [FEATURE: Watermark Studio] — Text & image watermarking
    object Watermark : Screen()
    // [FEATURE: Header & Footer Studio] — Running headers and footers
    object HeaderFooter : Screen()
    // [FEATURE: Page Numbering Studio] — Customizable page numbering
    object PageNumber : Screen()
    // [FEATURE: Bookmark Editor] — Document outline & TOC editor
    object BookmarkEditor : Screen()
    // [FEATURE: Find & Replace Text] — Text search & stream replacement
    object FindAndReplaceText : Screen()
    // [FEATURE: Image Replacer] — Stream raster image replacement
    object ImageReplacer : Screen()

    // 5. 🛡️ SECURITY, PRIVACY & COMPLIANCE (FEATURES_REGISTRY Section 5)
    // [FEATURE: Encrypt / Password Protect] — AES-128 / AES-256 password protection
    object ProtectPdf : Screen()
    // [FEATURE: Decrypt / Unlock PDF] — Restriction & password stripper
    data class UnlockPdf(val initialUri: Uri? = null) : Screen()
    // [FEATURE: Permanent Smart Redaction] — PII scrub & stream redaction
    object Redaction : Screen()
    // [FEATURE: Deep Threat Sanitizer] — JS, Actions, Beacons removal
    object DocumentSanitizer : Screen()
    // [FEATURE: Metadata Sanitizer] — Author, GPS, editing history purge
    object MetadataSanitizer : Screen()
    object InspectMetadata : Screen()
    object StripMetadata : Screen()
    object TextCleaner : Screen()
    // [FEATURE: PDF/A Preflight Validator] — ISO 19005 compliance audit
    object PdfAValidator : Screen()
    // [FEATURE: Typography & Font Inspector] — Embedded font programs & encodings
    object FontInspector : Screen()
    // [FEATURE: Embedded Attachments Manager] — Portfolios & embedded files
    object AttachmentManager : Screen()
    // [FEATURE: PDF Repair Studio] — Broken cross-reference table reconstruction
    object RepairPdf : Screen()
    // [FEATURE: Document Visual Comparison] — Side-by-side visual & text diffs
    object ComparePdf : Screen()

    // 6. 📖 READING & ACCESSIBILITY (FEATURES_REGISTRY Section 6)
    // [FEATURE: Reflow Reader Studio & Offline TTS] — E-reader reflow & 100% offline TTS
    data class ReflowReader(val initialUri: Uri? = null) : Screen()
}

val ScreenSaver: Saver<Screen, String> = Saver(
    save = { screen ->
        when (screen) {
            is Screen.PdfEditor -> "PdfEditor:${screen.initialPdfUri?.toString() ?: ""}"
            is Screen.PdfReader -> "PdfReader:${screen.initialPdfUri?.toString() ?: ""}"
            is Screen.ReflowReader -> "ReflowReader:${screen.initialUri?.toString() ?: ""}"
            is Screen.UnlockPdf -> "UnlockPdf:${screen.initialUri?.toString() ?: ""}"
            is Screen.OfficeExport -> "OfficeExport:${screen.initialFormat.name}"
            else -> screen::class.simpleName ?: "Home"
        }
    },
    restore = { str ->
        when {
            str.startsWith("PdfEditor:") -> {
                val uriStr = str.removePrefix("PdfEditor:")
                Screen.PdfEditor(if (uriStr.isNotEmpty()) Uri.parse(uriStr) else null)
            }
            str.startsWith("PdfReader:") -> {
                val uriStr = str.removePrefix("PdfReader:")
                Screen.PdfReader(if (uriStr.isNotEmpty()) Uri.parse(uriStr) else null)
            }
            str.startsWith("ReflowReader:") -> {
                val uriStr = str.removePrefix("ReflowReader:")
                Screen.ReflowReader(if (uriStr.isNotEmpty()) Uri.parse(uriStr) else null)
            }
            str.startsWith("UnlockPdf:") -> {
                val uriStr = str.removePrefix("UnlockPdf:")
                Screen.UnlockPdf(if (uriStr.isNotEmpty()) Uri.parse(uriStr) else null)
            }
            str.startsWith("OfficeExport:") -> {
                val formatName = str.removePrefix("OfficeExport:")
                val format = try {
                    com.pdfchemy.app.logic.OfficeFormat.valueOf(formatName)
                } catch (_: Exception) {
                    com.pdfchemy.app.logic.OfficeFormat.WORD
                }
                Screen.OfficeExport(format)
            }
            str == "CompressPdf" -> Screen.CompressPdf
            str == "BatchCompressPdf" -> Screen.BatchCompressPdf
            str == "ImageCompressor" -> Screen.ImageCompressor
            str == "TextToPdf" -> Screen.TextToPdf
            str == "TextConverter" -> Screen.TextConverter
            str == "Settings" -> Screen.Settings
            str == "CompressCategory" -> Screen.CompressCategory
            str == "CreateCategory" -> Screen.CreateCategory
            str == "OrganizeCategory" -> Screen.OrganizeCategory
            str == "MergePdf" -> Screen.MergePdf
            str == "SplitPdf" -> Screen.SplitPdf
            str == "DeletePages" -> Screen.DeletePages
            str == "ExtractImages" -> Screen.ExtractImages
            str == "CheckCategory" -> Screen.CheckCategory
            str == "InspectMetadata" -> Screen.InspectMetadata
            str == "StripMetadata" -> Screen.StripMetadata
            str == "TextCleaner" -> Screen.TextCleaner
            str == "ImagesToPdf" -> Screen.ImagesToPdf
            str == "RotatePdf" -> Screen.RotatePdf
            str == "ExtractText" -> Screen.ExtractText
            str == "ProtectPdf" -> Screen.ProtectPdf
            str == "UnlockPdf" -> Screen.UnlockPdf()
            str == "PdfToImages" -> Screen.PdfToImages
            str == "FillForm" -> Screen.FillForm
            str == "OcrPdf" -> Screen.OcrPdf
            str == "ScanPdf" -> Screen.ScanPdf
            str == "SignPdf" -> Screen.SignPdf
            str == "PageOrganizer" -> Screen.PageOrganizer
            str == "Watermark" -> Screen.Watermark
            str == "PageNumber" -> Screen.PageNumber
            str == "PageLayout" -> Screen.PageLayout
            str == "ComparePdf" -> Screen.ComparePdf
            str == "ImageReplacer" -> Screen.ImageReplacer
            str == "FindAndReplaceText" -> Screen.FindAndReplaceText
            str == "CropPdf" -> Screen.CropPdf
            str == "MetadataSanitizer" -> Screen.MetadataSanitizer
            str == "MarkdownStudio" -> Screen.MarkdownStudio
            str == "EbookConverter" -> Screen.EbookConverter
            str == "FlattenPdf" -> Screen.FlattenPdf
            str == "Booklet" -> Screen.Booklet
            str == "RepairPdf" -> Screen.RepairPdf
            str == "GrayscaleOptimizer" -> Screen.GrayscaleOptimizer
            str == "HeaderFooter" -> Screen.HeaderFooter
            str == "BookmarkEditor" -> Screen.BookmarkEditor
            str == "Redaction" -> Screen.Redaction
            str == "AttachmentManager" -> Screen.AttachmentManager
            str == "LinearizePdf" -> Screen.LinearizePdf
            str == "NUp" -> Screen.NUp
            str == "PdfAValidator" -> Screen.PdfAValidator
            str == "FontInspector" -> Screen.FontInspector
            str == "Deskew" -> Screen.Deskew
            str == "TableExtractor" -> Screen.TableExtractor
            str == "DocumentSanitizer" -> Screen.DocumentSanitizer
            str == "QuickFillSign" -> Screen.QuickFillSign
            str == "FormBuilder" -> Screen.FormBuilder
            str == "Premium" -> Screen.Premium
            else -> Screen.Home
        }
    }
)

/**
 * Animated title with a subtle shimmer highlight that sweeps across the text.
 * Content is always visible — the shimmer is purely additive.
 */
@Composable
fun ShimmerTitle(text: String, style: androidx.compose.ui.text.TextStyle, baseColor: Color, isDarkTheme: Boolean) {
    val shimmerAnim = remember { Animatable(-1f) }
    
    LaunchedEffect(Unit) {
        // Run once initially
        shimmerAnim.animateTo(
            targetValue = 2f,
            animationSpec = tween(3000, easing = LinearEasing)
        )
        
        while (true) {
            // Random delay between 5 and 10 minutes (in milliseconds)
            val delayMillis = (300_000L..600_000L).random()
            delay(delayMillis)
            
            shimmerAnim.snapTo(-1f)
            shimmerAnim.animateTo(
                targetValue = 2f,
                animationSpec = tween(3000, easing = LinearEasing)
            )
        }
    }

    val shimmerOffset = shimmerAnim.value

    val c1 = MaterialTheme.colorScheme.primary
    val shimmerBrush = Brush.linearGradient(
        colors = listOf(baseColor, baseColor.copy(alpha = 0.6f), Color.White, baseColor.copy(alpha = 0.6f), baseColor),
        start = Offset(shimmerOffset * 800f, 0f),
        end = Offset(shimmerOffset * 800f + 600f, 0f)
    )

    val textShadow = if (isDarkTheme) {
        androidx.compose.ui.graphics.Shadow(
            color = c1.copy(alpha = 0.55f),
            offset = Offset.Zero,
            blurRadius = 16f
        )
    } else {
        androidx.compose.ui.graphics.Shadow(
            color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.4f),
            offset = Offset(2f, 2f),
            blurRadius = 2f
        )
    }

    Text(
        text = text,
        style = style.copy(
            brush = shimmerBrush,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.5).sp,
            shadow = textShadow
        )
    )
}

@Composable
fun AnimatedMeshBackground() {
    val infiniteTransition = rememberInfiniteTransition(label = "mesh")
    val color1State = infiniteTransition.animateColor(
        initialValue = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
        targetValue = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "color1"
    )
    val color2State = infiniteTransition.animateColor(
        initialValue = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.18f),
        targetValue = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
        animationSpec = infiniteRepeatable(
            animation = tween(10000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "color2"
    )

    val bgColor = MaterialTheme.colorScheme.background
    Canvas(modifier = Modifier.fillMaxSize().background(bgColor)) {
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(color1State.value, Color.Transparent),
                center = Offset(size.width * 0.2f, size.height * 0.2f),
                radius = size.width * 0.85f
            )
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(color2State.value, Color.Transparent),
                center = Offset(size.width * 0.8f, size.height * 0.8f),
                radius = size.width * 0.85f
            )
        )
    }
}

@Composable
fun HomeScreen(
    windowWidthSizeClass: WindowWidthSizeClass = WindowWidthSizeClass.Compact,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onNavigate: (Screen) -> Unit,
    viewModel: MainViewModel
) {
    // Only run the entrance stagger once on initial cold launch
    val hasEnteredHome = rememberSaveable { mutableStateOf(false) }
    val initialAlpha = if (hasEnteredHome.value) 1f else 0f
    val initialOffset = if (hasEnteredHome.value) 0f else 24f

    val headerAlpha = remember { Animatable(initialAlpha) }
    val headerOffsetY = remember { Animatable(initialOffset) }
    val cardsAlpha = remember { Animatable(initialAlpha) }
    val cardsOffsetY = remember { Animatable(initialOffset) }

    // Refresh recent activity on entry
    LaunchedEffect(Unit) {
        viewModel.refreshHistory()
    }

    // Run the entrance animation only on first entry
    LaunchedEffect(Unit) {
        if (!hasEnteredHome.value) {
            try {
                launch {
                    headerAlpha.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
                }
                launch {
                    headerOffsetY.animateTo(0f, tween(320, easing = FastOutSlowInEasing))
                }
                launch {
                    delay(80)
                    cardsAlpha.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
                }
                launch {
                    delay(80)
                    cardsOffsetY.animateTo(0f, tween(320, easing = FastOutSlowInEasing))
                }
            } finally {
                hasEnteredHome.value = true
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedMeshBackground()

        val isPremium by viewModel.isPremium.collectAsState()

        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
        val configuration = LocalConfiguration.current
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val isWideScreen = isLandscape

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            if (!isPremium) {
                IconButton(onClick = { onNavigate(Screen.Premium) }) {
                    Icon(Icons.Rounded.WorkspacePremium, contentDescription = stringResource(R.string.desc_go_premium), tint = Color(0xFFFFD700))
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            IconButton(onClick = { onNavigate(Screen.Settings) }) {
                Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.desc_settings), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onToggleTheme) {
                Icon(
                    imageVector = if (isDarkTheme) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                    contentDescription = stringResource(R.string.desc_toggle_theme),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 680.dp)
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.graphicsLayer {
                            alpha = headerAlpha.value
                            translationY = -headerOffsetY.value  // slide down from above
                        }
                    ) {
                        ShimmerTitle(
                            text = stringResource(R.string.title_tools),
                            style = MaterialTheme.typography.headlineLarge,
                            baseColor = MaterialTheme.colorScheme.primary,
                            isDarkTheme = isDarkTheme
                        )
                        Text(
                            text = stringResource(R.string.home_subtitle),
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 2.sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
                        )
                    }
                    
                    com.pdfchemy.app.ui.ToolSearchBar(
                        modifier = Modifier.padding(bottom = 16.dp),
                        onToolSelected = onNavigate
                    )

                    val readerPicker = com.pdfchemy.app.ui.rememberVanguardPdfPicker(
                        onNavigateToUnlock = { onNavigate(Screen.UnlockPdf(it)) },
                        onPdfSelected = { onNavigate(Screen.PdfReader(it)) }
                    )
                    OutlinedButton(
                        onClick = { readerPicker.launch() },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                    ) {
                        Icon(Icons.Rounded.MenuBook, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.select_pdf_for_reading))
                    }

                    Column(
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.graphicsLayer {
                            alpha = cardsAlpha.value
                            translationY = cardsOffsetY.value  // slide up from below
                        }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().height(150.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CategoryCard(stringResource(R.string.cat_compress), stringResource(R.string.cat_compress_desc), Icons.Rounded.Compress, { onNavigate(Screen.CompressCategory) }, Modifier.weight(1f).fillMaxHeight())
                            CategoryCard(stringResource(R.string.cat_create), stringResource(R.string.cat_create_desc), Icons.Rounded.AddCircleOutline, { onNavigate(Screen.CreateCategory) }, Modifier.weight(1f).fillMaxHeight())
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().height(150.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CategoryCard(stringResource(R.string.cat_organize), stringResource(R.string.cat_organize_desc), Icons.Rounded.FolderOpen, { onNavigate(Screen.OrganizeCategory) }, Modifier.weight(1f).fillMaxHeight())
                            CategoryCard(stringResource(R.string.cat_check), stringResource(R.string.cat_check_desc), Icons.Rounded.FactCheck, { onNavigate(Screen.CheckCategory) }, Modifier.weight(1f).fillMaxHeight())
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        RecentFilesSection(viewModel, onNavigate = { screen -> onNavigate(screen) })
                    }
                }
            }
        }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "scaleAnim"
    )
    val view = androidx.compose.ui.platform.LocalView.current

    LaunchedEffect(isPressed) {
        if (isPressed) {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    Card(
        onClick = onClick,
        modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale },
        interactionSource = interactionSource,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp, pressedElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                )
            ).border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                shape = RoundedCornerShape(24.dp)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f), shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 15.sp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateCategoryScreen(onNavigate: (Screen) -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var scannedPdfUri by remember { mutableStateOf<Uri?>(null) }

    val saveScannedPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri: Uri? ->
        if (destUri != null && scannedPdfUri != null) {
            val sourceUri = scannedPdfUri ?: return@rememberLauncherForActivityResult
            coroutineScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        requireNotNull(context.contentResolver.openInputStream(sourceUri)) { "Cannot read scanned PDF" }.use { input ->
                            requireNotNull(context.contentResolver.openOutputStream(destUri, "wt")) { "Cannot open scan destination" }.use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                    }
                    Toast.makeText(context, context.getString(R.string.msg_scanned_pdf_exported), Toast.LENGTH_SHORT).show()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    AppLogger.e("Exception caught in MainActivity", e)
                    Toast.makeText(context, context.getString(R.string.msg_failed_export), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val scannerLauncherReal = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val scanningResult = com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            scanningResult?.pdf?.uri?.let { pdfUri ->
                scannedPdfUri = pdfUri
                coroutineScope.launch {
                    var savedScanFile: File? = null
                    try {
                        val scansDir = java.io.File(context.filesDir, "scans")
                        if (!scansDir.exists()) scansDir.mkdirs()
                        
                        val timeStamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                        val fileName = "${context.getString(R.string.scan_prefix)}$timeStamp.pdf"
                        val destFile = java.io.File(scansDir, fileName)
                        savedScanFile = destFile
                        
                        withContext(Dispatchers.IO) {
                            requireNotNull(context.contentResolver.openInputStream(pdfUri)) { "Cannot read scanned PDF" }.use { input ->
                                java.io.FileOutputStream(destFile).use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        output.write(buffer, 0, count)
                                    }
                                }
                            }
                        }
                        
                        val internalUri = androidx.core.content.FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            destFile
                        )
                        
                        val historyRepo = com.pdfchemy.app.logic.HistoryRepository(context)
                        historyRepo.addHistoryItem(internalUri, fileName, context.getString(R.string.menu_scan))
                        savedScanFile = null // Successful persistence: later UI cancellation must retain it.
                        
                        // Show snackbar with Export option
                        val snackbarResult = snackbarHostState.showSnackbar(
                            message = context.getString(R.string.msg_scan_saved),
                            actionLabel = context.getString(R.string.action_export),
                            duration = SnackbarDuration.Short
                        )
                        if (snackbarResult == SnackbarResult.ActionPerformed) {
                            saveScannedPdfLauncher.launch(com.pdfchemy.app.logic.FileUtil.generateSuggestedName(null, context.getString(R.string.default_scanned_doc_name)))
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        savedScanFile?.delete()
                        throw cancelled
                    } catch (e: Exception) {
                        savedScanFile?.delete()
                        AppLogger.e("Exception caught in MainActivity", e)
                        snackbarHostState.showSnackbar(context.getString(R.string.msg_failed_scan))
                    }
                }
            }
        }
    }

    fun launchScanner() {
        if (activity == null) return
        val options = com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setResultFormats(com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        val scanner = com.google.mlkit.vision.documentscanner.GmsDocumentScanning.getClient(options)
        scanner.getStartScanIntent(activity).addOnSuccessListener { intentSender ->
            scannerLauncherReal.launch(androidx.activity.result.IntentSenderRequest.Builder(intentSender).build())
        }.addOnFailureListener {
            Toast.makeText(context, context.getString(R.string.msg_scanner_not_available), Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        containerColor = Color.Transparent, 
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.create)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent, scrolledContainerColor = androidx.compose.ui.graphics.Color.Transparent), navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.desc_back))
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 300.dp),
                modifier = Modifier.widthIn(max = 800.dp).fillMaxHeight().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
            item {
                ToolCard(
                    title = stringResource(R.string.menu_scan),
                    subtitle = stringResource(R.string.menu_scan_desc),
                    icon = Icons.Rounded.DocumentScanner,
                    onClick = { launchScanner() }
                )
            }
            item {
                ToolCard(
                    title = stringResource(R.string.images_to_pdf),
                    subtitle = stringResource(R.string.subtitle_images_to_pdf),
                    icon = Icons.Rounded.PictureAsPdf,
                    onClick = { onNavigate(Screen.ImagesToPdf) }
                )
            }
            item {
                ToolCard(
                    title = stringResource(R.string.text_to_pdf),
                    subtitle = stringResource(R.string.subtitle_text_to_pdf),
                    icon = Icons.Rounded.Description,
                    onClick = { onNavigate(Screen.TextToPdf) }
                )
            }
            item {
                ToolCard(
                    title = stringResource(R.string.text_format_converter),
                    subtitle = stringResource(R.string.subtitle_format_converter),
                    icon = Icons.Rounded.SyncAlt,
                    onClick = { onNavigate(Screen.TextConverter) }
                )
            }
            item {
                ToolCard(
                    title = stringResource(R.string.menu_pdf_to_images),
                    subtitle = stringResource(R.string.menu_pdf_to_images_desc),
                    icon = Icons.Rounded.Image,
                    onClick = { onNavigate(Screen.PdfToImages) }
                )
            }

            item {
                ToolCard(
                    title = stringResource(R.string.menu_ocr_pdf),
                    subtitle = stringResource(R.string.menu_ocr_pdf_desc),
                    icon = Icons.Rounded.DocumentScanner,
                    onClick = { onNavigate(Screen.OcrPdf) }
                )
            }
            item {
                ToolCard(
                    title = stringResource(R.string.menu_pdf_to_word),
                    subtitle = stringResource(R.string.menu_pdf_to_word_desc),
                    icon = Icons.Rounded.Article,
                    onClick = { onNavigate(Screen.OfficeExport(com.pdfchemy.app.logic.OfficeFormat.WORD)) }
                )
            }
            item {
                ToolCard(
                    title = stringResource(R.string.menu_pdf_to_excel),
                    subtitle = stringResource(R.string.menu_pdf_to_excel_desc),
                    icon = Icons.Rounded.TableChart,
                    onClick = { onNavigate(Screen.OfficeExport(com.pdfchemy.app.logic.OfficeFormat.EXCEL)) }
                )
            }
            item {
                ToolCard(
                    title = stringResource(R.string.menu_pdf_to_pptx),
                    subtitle = stringResource(R.string.menu_pdf_to_pptx_desc),
                    icon = Icons.Rounded.Slideshow,
                    onClick = { onNavigate(Screen.OfficeExport(com.pdfchemy.app.logic.OfficeFormat.POWERPOINT)) }
                )
            }
            item {
                ToolCard(
                    title = stringResource(R.string.menu_extract_images),
                    subtitle = stringResource(R.string.menu_extract_images_desc),
                    icon = Icons.Rounded.Image,
                    onClick = { onNavigate(Screen.ExtractImages) }
                )
            }
            item {
                ToolCard(
                    title = stringResource(R.string.menu_markdown_studio),
                    subtitle = stringResource(R.string.menu_markdown_studio_desc),
                    icon = Icons.Rounded.EditNote,
                    onClick = { onNavigate(Screen.MarkdownStudio) }
                )
            }
            item {
                ToolCard(
                    title = stringResource(R.string.menu_ebook_suite),
                    subtitle = stringResource(R.string.menu_ebook_suite_desc),
                    icon = Icons.Rounded.AutoStories,
                    onClick = { onNavigate(Screen.EbookConverter) }
                )
            }

            item {
                ToolCard(
                    title = stringResource(R.string.menu_table_extractor),
                    subtitle = stringResource(R.string.menu_table_extractor_desc),
                    icon = Icons.Rounded.TableChart,
                    onClick = { onNavigate(Screen.TableExtractor) }
                )
            }

        }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompressCategoryScreen(onNavigate: (Screen) -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    Scaffold(containerColor = Color.Transparent, 
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compress)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent, scrolledContainerColor = androidx.compose.ui.graphics.Color.Transparent), navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.desc_back))
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 300.dp),
                modifier = Modifier.widthIn(max = 800.dp).fillMaxHeight().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
            item {
                ToolCard(
                    title = stringResource(R.string.compress_title),
                    subtitle = stringResource(R.string.cat_compress_desc),
                    icon = Icons.Rounded.Compress,
                    onClick = { onNavigate(Screen.CompressPdf) }
                )
            }
            item {
                ToolCard(
                    title = stringResource(R.string.compress_batch),
                    subtitle = stringResource(R.string.subtitle_batch_compress),
                    icon = Icons.Rounded.LibraryBooks,
                    onClick = { onNavigate(Screen.BatchCompressPdf) } // Routes to same tool where tab exists
                )
            }
            item {
                ToolCard(
                    title = stringResource(R.string.menu_compress_image),
                    subtitle = stringResource(R.string.menu_compress_image_desc),
                    icon = Icons.Rounded.Image,
                    onClick = { onNavigate(Screen.ImageCompressor) }
                )
            }
            item {
                ToolCard(
                    title = stringResource(R.string.menu_grayscale_optimizer),
                    subtitle = stringResource(R.string.menu_grayscale_optimizer_desc),
                    icon = Icons.Rounded.Draw,
                    onClick = { onNavigate(Screen.GrayscaleOptimizer) }
                )
            }
            item {
                ToolCard(
                    title = stringResource(R.string.menu_fast_web_view),
                    subtitle = stringResource(R.string.menu_fast_web_view_desc),
                    icon = Icons.Rounded.Dashboard,
                    onClick = { onNavigate(Screen.LinearizePdf) }
                )
            }
        }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceholderCategoryScreen(title: String, onBack: () -> Unit) {
    BackHandler { onBack() }
    Scaffold(containerColor = Color.Transparent, 
        topBar = {
            TopAppBar(
                title = { Text(title) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent, scrolledContainerColor = androidx.compose.ui.graphics.Color.Transparent), navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.desc_back))
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Construction, contentDescription = stringResource(R.string.desc_coming_soon), modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(16.dp))
                Text(stringResource(R.string.coming_soon), style = MaterialTheme.typography.headlineMedium)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "scaleAnim"
    )
    val view = androidx.compose.ui.platform.LocalView.current

    Card(
        onClick = { 
            view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            onClick() 
        },
        modifier = modifier
            .fillMaxWidth()
            .height(108.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale },
        interactionSource = interactionSource,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp, pressedElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                    ),
                    shape = RoundedCornerShape(20.dp)
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(20.dp)
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .padding(end = 16.dp)
                        .size(50.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f), shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        modifier = Modifier.size(26.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CompressPdfScreen(viewModel: MainViewModel, initialTab: Int = 0, isScreenshotRun: Boolean = false, onBack: () -> Unit) {
    val context = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    val isHaptic by viewModel.isHapticEnabled.collectAsState()
    val isSfx by viewModel.isSfxEnabled.collectAsState()
    val currentQuality by viewModel.compressionQuality.collectAsState()
    val originalSize by viewModel.selectedFileSize.collectAsState()
    val pdfAnalysis by viewModel.pdfAnalysis.collectAsState()
    val isAnalyzing by viewModel.isAnalyzing.collectAsState()
    val useGrayscale by viewModel.useGrayscale.collectAsState()
    val useLossless by viewModel.useLossless.collectAsState()
    val stripMetadata by viewModel.stripMetadata.collectAsState()
    val isPremium by viewModel.isPremium.collectAsState()
    
    var showOfficialDocWarning by remember { mutableStateOf(false) }

    LaunchedEffect(pdfAnalysis) {
        if (pdfAnalysis?.scenario == com.pdfchemy.app.logic.PdfScenario.SIGNED_OFFICIAL) {
            showOfficialDocWarning = true
        }
    }
    
    val selectedFiles by viewModel.selectedFiles.collectAsState()

    val pagerState = rememberPagerState(initialPage = initialTab, pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()
    val selectedTab = pagerState.currentPage

    val continuityUri by viewModel.continuityDocumentUri.collectAsState()
    var sourceUri by remember { mutableStateOf<Uri?>(continuityUri) }
    var sourceName by remember { mutableStateOf<String?>(null) }
    
    LaunchedEffect(continuityUri) {
        if (continuityUri != null && sourceUri == continuityUri) {
            sourceName = com.pdfchemy.app.utils.FileUtils.getFileName(context, continuityUri!!) ?: context.getString(R.string.label_selected_pdf)
            viewModel.onFileSelected(context, continuityUri!!)
        }
    }

    val pickPdfLauncher = rememberVanguardPdfPicker { uri ->
        sourceUri = uri
        sourceName = com.pdfchemy.app.utils.FileUtils.getFileName(context, uri) ?: context.getString(R.string.label_selected_pdf)
        viewModel.onFileSelected(context, uri)
    }

    val pickMultiplePdfsLauncher = rememberVanguardMultiplePdfPicker { uris ->
        viewModel.onFilesSelected(context, uris)
    }

    val savePdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) {
            val src = sourceUri ?: return@rememberLauncherForActivityResult
            viewModel.compressPdf(context, src, uri)
        }
    }

    val selectDirectoryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.compressBatch(context, uri)
        }
    }

    BackHandler { onBack() }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(containerColor = Color.Transparent, 
        topBar = { PremiumTopAppBar(stringResource(R.string.title_compress_pdf), onBack) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            TabRow(selectedTabIndex = selectedTab, modifier = Modifier.fillMaxWidth(), containerColor = androidx.compose.ui.graphics.Color.Transparent, divider = {}) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { playFeedback(view, isHaptic, isSfx); coroutineScope.launch { pagerState.animateScrollToPage(0) } },
                    text = { Text(stringResource(R.string.compress_single)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { playFeedback(view, isHaptic, isSfx); coroutineScope.launch { pagerState.animateScrollToPage(1) } },
                    text = { Text(stringResource(R.string.compress_batch)) }
                )
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                if (isLandscape) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                        ) {
                            LeftPanel(
                                selectedTab = page,
                                sourceUri = sourceUri,
                                sourceName = sourceName,
                                originalSize = originalSize,
                                selectedFiles = selectedFiles,
                                isAnalyzing = isAnalyzing,
                                pdfAnalysis = pdfAnalysis,
                                currentQuality = currentQuality,
                                useGrayscale = useGrayscale,
                                useLossless = useLossless,
                                viewModel = viewModel,
                                onPickSingle = { 
                                    if (isScreenshotRun) {
                                        val exportsDir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
                                        val file = java.io.File(exportsDir, "Annual_Report_2026.pdf")
                                        if (!file.exists()) {
                                            context.assets.open("Annual_Report_2026.pdf").use { input ->
                                                file.outputStream().use { output ->
                                                    input.copyTo(output)
                                                }
                                            }
                                        }
                                        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                        sourceUri = uri
                                        sourceName = file.name
                                        viewModel.onFileSelected(context, uri)
                                    } else {
                                        pickPdfLauncher.launch(arrayOf("application/pdf", "application/x-pdf")) 
                                    }
                                },
                                onPickMultiple = { pickMultiplePdfsLauncher.launch(arrayOf("application/pdf", "application/x-pdf")) }
                            )
                        }

                        Column(
                            modifier = Modifier
                                .weight(1.2f)
                                .verticalScroll(rememberScrollState())
                        ) {
                            RightPanel(
                                selectedTab = page,
                                sourceUri = sourceUri,
                                sourceName = sourceName,
                                originalSize = originalSize,
                                selectedFiles = selectedFiles,
                                currentQuality = currentQuality,
                                recQuality = pdfAnalysis?.recommendedQuality,
                                useGrayscale = useGrayscale,
                                useLossless = useLossless,
                                stripMetadata = stripMetadata,
                                pdfAnalysis = pdfAnalysis,
                                viewModel = viewModel,
                                    onSaveSingle = { savePdfLauncher.launch(com.pdfchemy.app.logic.FileUtil.generateSuggestedName(sourceUri, context.getString(R.string.compressed_prefix))) },
                                onSaveBatch = { selectDirectoryLauncher.launch(null) }
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LeftPanel(
                            selectedTab = page,
                            sourceUri = sourceUri,
                            sourceName = sourceName,
                            originalSize = originalSize,
                            selectedFiles = selectedFiles,
                            isAnalyzing = isAnalyzing,
                            pdfAnalysis = pdfAnalysis,
                            currentQuality = currentQuality,
                            useGrayscale = useGrayscale,
                            useLossless = useLossless,
                            viewModel = viewModel,
                            onPickSingle = { 
                                if (isScreenshotRun) {
                                    val exportsDir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
                                    val file = java.io.File(exportsDir, "Annual_Report_2026.pdf")
                                    if (!file.exists()) {
                                        context.assets.open("Annual_Report_2026.pdf").use { input ->
                                            file.outputStream().use { output ->
                                                input.copyTo(output)
                                            }
                                        }
                                    }
                                    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                    sourceUri = uri
                                    sourceName = file.name
                                    viewModel.onFileSelected(context, uri)
                                } else {
                                    pickPdfLauncher.launch(arrayOf("application/pdf", "application/x-pdf")) 
                                }
                            },
                            onPickMultiple = { pickMultiplePdfsLauncher.launch(arrayOf("application/pdf", "application/x-pdf")) }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        RightPanel(
                            selectedTab = page,
                            sourceUri = sourceUri,
                            sourceName = sourceName,
                            originalSize = originalSize,
                            selectedFiles = selectedFiles,
                            currentQuality = currentQuality,
                            recQuality = pdfAnalysis?.recommendedQuality,
                            useGrayscale = useGrayscale,
                            useLossless = useLossless,
                            stripMetadata = stripMetadata,
                            pdfAnalysis = pdfAnalysis,
                            viewModel = viewModel,
                            onSaveSingle = { 
                                savePdfLauncher.launch(com.pdfchemy.app.logic.FileUtil.generateSuggestedName(sourceUri, context.getString(R.string.compressed_prefix))) 
                            },
                            onSaveBatch = { 
                                selectDirectoryLauncher.launch(null) 
                            }
                        )
                    }
                }
            }
        }
    }

    if (showOfficialDocWarning) {
        AlertDialog(
            onDismissRequest = { showOfficialDocWarning = false },
            title = { Text(stringResource(R.string.warning_quality_title)) },
            text = { Text(stringResource(R.string.warning_quality_desc)) },
            confirmButton = {
                TextButton(onClick = { showOfficialDocWarning = false }) {
                    Text(stringResource(R.string.warning_understand))
                }
            }
        )
    }
}

@Composable
fun LeftPanel(
    selectedTab: Int,
    sourceUri: Uri?,
    sourceName: String?,
    originalSize: Long,
    selectedFiles: List<MainViewModel.SelectedFile>,
    isAnalyzing: Boolean,
    pdfAnalysis: PdfAnalysis?,
    currentQuality: Float,
    useGrayscale: Boolean,
    useLossless: Boolean,
    viewModel: MainViewModel,
    onPickSingle: () -> Unit,
    onPickMultiple: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (selectedTab == 0) stringResource(R.string.tab_single_pdf_optimization) else stringResource(R.string.tab_batch_pdf_optimization),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { if (selectedTab == 0) onPickSingle() else onPickMultiple() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (selectedTab == 0) {
                        if (sourceUri == null) stringResource(R.string.compress_select_file) else stringResource(R.string.btn_change_file)
                    } else {
                        if (selectedFiles.isEmpty()) stringResource(R.string.btn_select_pdf_files) else stringResource(R.string.btn_add_more_files, selectedFiles.size)
                    }
                )
            }
        }
    }

    if (selectedTab == 0) {
        sourceUri?.let {
            val estCompressedSize = if (originalSize > 0) {
                viewModel.estimateCompressedSize(
                    originalSize = originalSize,
                    quality = currentQuality,
                    useLossless = useLossless,
                    useGrayscale = useGrayscale,
                    scenario = pdfAnalysis?.scenario
                )
            } else 0L

            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = stringResource(R.string.file_name_label, sourceName ?: ""), style = MaterialTheme.typography.bodyLarge)
                    if (originalSize > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isAnalyzing) stringResource(R.string.label_original_size_analyzing, viewModel.formatSize(originalSize)) else stringResource(R.string.label_original_size_to_compressed, viewModel.formatSize(originalSize), viewModel.formatSize(estCompressedSize)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = if (!isAnalyzing) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    } else {
        if (selectedFiles.isNotEmpty()) {
            val targetMb by viewModel.targetMb.collectAsState()
            var isListExpanded by remember { mutableStateOf(selectedFiles.size < 2) }
            
            // Calculate totals
            val totalOriginalSize = selectedFiles.sumOf { it.size }
            val totalEstimatedSize = if (targetMb != null) {
                val tm = targetMb
                if (tm != null) {
                    (tm * 1024 * 1024).toLong() * selectedFiles.size
                } else {
                    0L
                }
            } else {
                selectedFiles.sumOf { file ->
                    if (file.size > 0) {
                        viewModel.estimateCompressedSize(
                            originalSize = file.size,
                            quality = currentQuality,
                            useLossless = useLossless,
                            useGrayscale = useGrayscale,
                            scenario = null
                        )
                    } else 0L
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.selected_batch_files),
                            style = MaterialTheme.typography.titleMedium
                        )
                        TextButton(
                            onClick = { viewModel.clearSelectedFiles() },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(stringResource(R.string.clear_all))
                        }
                    }
                    
                    if (selectedFiles.size >= 2) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(stringResource(R.string.label_total_summary, selectedFiles.size), style = MaterialTheme.typography.titleSmall)
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = viewModel.formatSize(totalOriginalSize),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(stringResource(R.string.empty_str), style = MaterialTheme.typography.bodySmall)
                                    val estimatedText = if (targetMb != null) {
                                    val tm = targetMb
                                    if (tm != null) {
                                        stringResource(R.string.label_target_mb_batch, (tm * selectedFiles.size).toFloat())
                                    } else {
                                        stringResource(R.string.label_target_unknown)
                                    }
                                    } else {
                                        "~${viewModel.formatSize(totalEstimatedSize)}"
                                    }
                                    Text(
                                        text = estimatedText,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        
                        Button(
                            onClick = { isListExpanded = !isListExpanded },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(
                                if (isListExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isListExpanded) stringResource(R.string.action_hide_files) else stringResource(R.string.action_view_files))
                        }
                    }
                    
                    AnimatedVisibility(visible = isListExpanded || selectedFiles.size < 2) {
                        Column {
                            selectedFiles.forEach { file ->
                                val estCompressedSize = if (file.size > 0) {
                                    viewModel.estimateCompressedSize(
                                        originalSize = file.size,
                                        quality = currentQuality,
                                        useLossless = useLossless,
                                        useGrayscale = useGrayscale,
                                        scenario = null
                                    )
                                } else 0L
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Rounded.PictureAsPdf, 
                                        contentDescription = null, 
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = file.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = if (file.isAnalyzing) stringResource(R.string.label_analyzing) else viewModel.formatSize(file.size),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            if (!file.isAnalyzing) {
                                                Text(
                                                    text = "  →  ",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                val estimatedText = if (targetMb != null) {
                                                    stringResource(R.string.label_target_mb, targetMb.toString())
                                                } else {
                                                    "~${viewModel.formatSize(estCompressedSize)}"
                                                }
                                                Text(
                                                    text = estimatedText,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                    
                                    Spacer(modifier = Modifier.width(8.dp))
                                    IconButton(
                                        onClick = { viewModel.removeSelectedFile(file.uri) }, 
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.Close, 
                                            contentDescription = stringResource(R.string.desc_remove_file), 
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
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

    AnimatedVisibility(
        visible = isAnalyzing,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.analyzing_doc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }

    AnimatedVisibility(
        visible = pdfAnalysis != null,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        pdfAnalysis?.let { analysis ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.TipsAndUpdates, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.smart_recommendation),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.detected_type, analysis.scenario.displayName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = stringResource(R.string.doc_stats_pages_images, analysis.pageCount, analysis.imageCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = analysis.recommendationReason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

@Composable
fun RightPanel(
    selectedTab: Int,
    sourceUri: Uri?,
    sourceName: String?,
    originalSize: Long,
    selectedFiles: List<MainViewModel.SelectedFile>,
    currentQuality: Float,
    recQuality: Float?,
    useGrayscale: Boolean,
    useLossless: Boolean,
    stripMetadata: Boolean,
    pdfAnalysis: PdfAnalysis?,
    viewModel: MainViewModel,
    onSaveSingle: () -> Unit,
    onSaveBatch: () -> Unit
) {
    val totalOriginalSize = if (selectedTab == 0) originalSize else selectedFiles.sumOf { it.size }
    val hasSelection = if (selectedTab == 0) sourceUri != null else selectedFiles.isNotEmpty()

    if (!hasSelection) {
        Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.SettingsSuggest, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(16.dp))
                Text(stringResource(R.string.compress_options_empty), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            }
        }
        return
    }

    val view = androidx.compose.ui.platform.LocalView.current
    val isHaptic by viewModel.isHapticEnabled.collectAsState()
    val isSfx by viewModel.isSfxEnabled.collectAsState()

    val targetMb by viewModel.targetMb.collectAsState()
    var isTargetSizeEnabled by remember { mutableStateOf(targetMb != null) }
    val customPresetStr = stringResource(R.string.preset_custom)
    var selectedTargetPreset by remember { mutableStateOf<String?>(if (targetMb != null) customPresetStr else null) }
    var customTargetValue by remember { mutableStateOf(targetMb?.toString() ?: "") }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.compress_target_size), style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = isTargetSizeEnabled,
                    onCheckedChange = { 
                        playFeedback(view, isHaptic, isSfx)
                        isTargetSizeEnabled = it 
                        if (!it) {
                            viewModel.setTargetMb(null)
                        } else {
                            if (selectedTargetPreset != customPresetStr && selectedTargetPreset != null) {
                                viewModel.setTargetMb(selectedTargetPreset?.removeSuffix(" MB")?.toFloatOrNull())
                            }
                        }
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))

            if (!isTargetSizeEnabled) {
                Text(stringResource(R.string.compress_preset), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    CompressionPresetButton(stringResource(R.string.preset_smallest), 0.25f, currentQuality, recQuality == 0.25f) {
                        playFeedback(view, isHaptic, isSfx)
                        viewModel.setQuality(0.25f)
                    }
                    CompressionPresetButton(stringResource(R.string.preset_balanced), 0.50f, currentQuality, recQuality == 0.50f) {
                        playFeedback(view, isHaptic, isSfx)
                        viewModel.setQuality(0.50f)
                    }
                    CompressionPresetButton(stringResource(R.string.preset_best), 0.75f, currentQuality, recQuality == 0.75f) {
                        playFeedback(view, isHaptic, isSfx)
                        viewModel.setQuality(0.75f)
                    }
                }
            } else {
                Text(stringResource(R.string.select_target_size_mb), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                
                // Presets row 1
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TargetPresetChip("2 MB", selectedTargetPreset == "2 MB", Modifier.weight(1f)) { 
                        playFeedback(view, isHaptic, isSfx)
                        selectedTargetPreset = "2 MB"
                        viewModel.setTargetMb(2f)
                    }
                    TargetPresetChip("5 MB", selectedTargetPreset == "5 MB", Modifier.weight(1f)) { 
                        playFeedback(view, isHaptic, isSfx)
                        selectedTargetPreset = "5 MB"
                        viewModel.setTargetMb(5f)
                    }
                    TargetPresetChip("10 MB", selectedTargetPreset == "10 MB", Modifier.weight(1f)) { 
                        playFeedback(view, isHaptic, isSfx)
                        selectedTargetPreset = "10 MB"
                        viewModel.setTargetMb(10f)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                // Presets row 2
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TargetPresetChip("20 MB", selectedTargetPreset == "20 MB", Modifier.weight(1f)) { 
                        playFeedback(view, isHaptic, isSfx)
                        selectedTargetPreset = "20 MB"
                        viewModel.setTargetMb(20f)
                    }
                    TargetPresetChip(stringResource(R.string.preset_custom), selectedTargetPreset == customPresetStr, Modifier.weight(1f)) { 
                        playFeedback(view, isHaptic, isSfx)
                        selectedTargetPreset = customPresetStr
                        viewModel.setTargetMb(customTargetValue.toFloatOrNull())
                    }
                }
                
                if (selectedTargetPreset == customPresetStr) {
                    Spacer(modifier = Modifier.height(12.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = customTargetValue,
                        onValueChange = { newValue ->
                            if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d*$"))) {
                                customTargetValue = newValue
                                viewModel.setTargetMb(newValue.toFloatOrNull())
                            }
                        },
                        label = { Text(stringResource(R.string.exact_mb)) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.compression_options), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Palette, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.grayscale_conversion), style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(stringResource(R.string.convert_color_images_to_black), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(start = 28.dp))
                    if (useGrayscale && pdfAnalysis?.scenario != com.pdfchemy.app.logic.PdfScenario.SCANNED_IMAGE_HEAVY) {
                        Text(
                            text = stringResource(R.string.warn_grayscale),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 2.dp, start = 28.dp)
                        )
                    }
                }
                Switch(checked = useGrayscale, onCheckedChange = { viewModel.setUseGrayscale(it) })
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.FolderZip, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.lossless_zip_compression), style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(stringResource(R.string.skip_jpeg_lossy_compression_no), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(start = 28.dp))
                    if (useLossless && pdfAnalysis?.scenario == com.pdfchemy.app.logic.PdfScenario.SCANNED_IMAGE_HEAVY) {
                        Text(
                            text = stringResource(R.string.warn_lossless),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 2.dp, start = 28.dp)
                        )
                    }
                }
                Switch(checked = useLossless, onCheckedChange = { viewModel.setUseLossless(it) })
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Tag, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.remove_metadata), style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(stringResource(R.string.strip_author_creator_editor_ta), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(start = 28.dp))
                    if (stripMetadata && pdfAnalysis?.scenario == com.pdfchemy.app.logic.PdfScenario.SIGNED_OFFICIAL) {
                        Text(
                            text = stringResource(R.string.warn_signatures),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 2.dp, start = 28.dp)
                        )
                    }
                }
                Switch(checked = stripMetadata, onCheckedChange = { viewModel.setStripMetadata(it) })
            }
        }
    }



    Button(
        onClick = { if (selectedTab == 0) onSaveSingle() else onSaveBatch() },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
            .defaultMinSize(minHeight = 54.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = stringResource(R.string.desc_compress_icon),
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (selectedTab == 0) stringResource(R.string.btn_optimize_and_save) else stringResource(R.string.btn_select_folder_and_compress),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
    Spacer(modifier = Modifier.height(32.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextToPdfScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    val isHaptic by viewModel.isHapticEnabled.collectAsState()
    val isSfx by viewModel.isSfxEnabled.collectAsState()
    val inputText by viewModel.inputText.collectAsState()

    val pickTextLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.loadTextFromFile(context, uri)
        }
    }

    val savePdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) {
            viewModel.convertTextToPdf(context, uri)
        }
    }

    BackHandler { onBack() }

    Scaffold(containerColor = Color.Transparent, 
        topBar = { PremiumTopAppBar(stringResource(R.string.history_text_to_pdf), onBack) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Card(
                modifier = Modifier.fillMaxWidth().weight(1f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { viewModel.setInputText(it) },
                    label = { Text(stringResource(R.string.type_or_paste_text_here)) },
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    placeholder = { Text(stringResource(R.string.enter_the_content_of_your_pdf)) }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { pickTextLauncher.launch(arrayOf("text/plain")) },
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.import_txt),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                Button(
                    onClick = { 
                        if (inputText.isBlank()) {
                            Toast.makeText(context, context.getString(R.string.msg_enter_text), Toast.LENGTH_SHORT).show()
                        } else {
                            savePdfLauncher.launch(com.pdfchemy.app.logic.FileUtil.generateSuggestedName(null, "converted_text"))
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.save_as_pdf),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TargetPresetChip(
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    ElevatedFilterChip(
        selected = isSelected,
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 42.dp),
        label = {
            Text(
                text = label,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    )
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompressionPresetButton(
    label: String,
    quality: Float,
    currentQuality: Float,
    isRecommended: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val isSelected = quality == currentQuality
    ElevatedFilterChip(
        selected = isSelected,
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 42.dp),
        label = {
            Text(
                text = if (isRecommended) "$label ★" else label,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    themeMode: String = "SYSTEM",
    onChangeThemeMode: (String) -> Unit = {},
    onOpenTour: () -> Unit = {},
    onBack: () -> Unit
) {
    val isHapticEnabled by viewModel.isHapticEnabled.collectAsState()
    val isSfxEnabled by viewModel.isSfxEnabled.collectAsState()
    val isHistoryEnabled by viewModel.isHistoryEnabled.collectAsState()
    val isRememberPositionEnabled by viewModel.isRememberPositionEnabled.collectAsState()
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var showPrivacyPolicyDialog by remember { mutableStateOf(false) }
    var showDefaultPdfDialog by remember { mutableStateOf(false) }
    var showRefundPolicyDialog by remember { mutableStateOf(false) }
    var showManifestoDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    if (showManifestoDialog) {
        AndroidManifestoDialog(onDismiss = { showManifestoDialog = false })
    }

    if (showDefaultPdfDialog) {
        AlertDialog(
            onDismissRequest = { showDefaultPdfDialog = false },
            title = { Text(stringResource(R.string.settings_default_pdf_dialog_title)) },
            text = { Text(stringResource(R.string.settings_default_pdf_dialog_body)) },
            confirmButton = {
                Button(onClick = {
                    showDefaultPdfDialog = false
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                            val intent = Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                            context.startActivity(intent)
                        } else {
                            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        }
                    } catch (e: Exception) {
                        try {
                            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        } catch (ex: Exception) {
                            // Ignored
                        }
                    }
                }) {
                    Text(stringResource(R.string.settings_open_android_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDefaultPdfDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text(stringResource(R.string.clear_history)) },
            text = { Text(stringResource(R.string.are_you_sure_you_want_to_clear)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearHistory()
                    showClearHistoryDialog = false
                    Toast.makeText(context, context.getString(R.string.msg_history_cleared), Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showPrivacyPolicyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyPolicyDialog = false },
            title = { Text(stringResource(R.string.consent_dialog_title)) },
            text = { Text(stringResource(R.string.consent_dialog_body)) },
            confirmButton = {
                TextButton(onClick = { showPrivacyPolicyDialog = false }) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                val activity = context as? android.app.Activity
                if (activity != null && UserMessagingPlatform.getConsentInformation(context).privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED) {
                    TextButton(onClick = {
                        showPrivacyPolicyDialog = false
                        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error ->
                            if (error != null) Toast.makeText(context, error.message, Toast.LENGTH_LONG).show()
                        }
                    }) { Text(stringResource(R.string.privacy_choices)) }
                }
            }
        )
    }

    if (showRefundPolicyDialog) {
        AlertDialog(
            onDismissRequest = { showRefundPolicyDialog = false },
            title = { Text(stringResource(R.string.refund_policy_title)) },
            text = { Text(stringResource(R.string.refund_policy_body)) },
            confirmButton = {
                TextButton(onClick = { showRefundPolicyDialog = false }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            PremiumTopAppBar(stringResource(R.string.settings_title), onBack)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 600.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    
                    Text(stringResource(R.string.app_preferences), style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Start))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // Theme Selector
                            var themeExpanded by remember { mutableStateOf(false) }
                            val themeOptions = mapOf(
                                "SYSTEM" to stringResource(R.string.theme_system),
                                "LIGHT" to stringResource(R.string.theme_light),
                                "DARK" to stringResource(R.string.theme_dark)
                            )
                            
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable { themeExpanded = true },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge)
                                    Text(stringResource(R.string.settings_theme_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Box {
                                    Text(themeOptions[themeMode] ?: stringResource(R.string.theme_system), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                                    DropdownMenu(expanded = themeExpanded, onDismissRequest = { themeExpanded = false }) {
                                        themeOptions.forEach { (modeKey, modeName) ->
                                            DropdownMenuItem(
                                                text = { Text(modeName) },
                                                onClick = {
                                                    onChangeThemeMode(modeKey)
                                                    themeExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                            // Language Selector
                            var expanded by remember { mutableStateOf(false) }
                            val currentLocale = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()[0]?.toLanguageTag() ?: "en"
                            val languages = mapOf(
                                "en" to "English",
                                "es" to "Español",
                                "pt-BR" to "Português (Brasil)",
                                "pt" to "Português (Portugal)",
                                "in" to "Bahasa Indonesia",
                                "de" to "Deutsch",
                                "fr" to "Français",
                                "it" to "Italiano",
                                "ro" to "Română",
                                "hi" to "हिन्दी (Hindi)",
                                "ja" to "日本語 (Japanese)",
                                "ko" to "한국어 (Korean)",
                                "ar" to "العربية (Arabic)",
                                "ru" to "Русский (Russian)",
                                "tr" to "Türkçe (Turkish)",
                                "vi" to "Tiếng Việt (Vietnamese)",
                                "th" to "ไทย (Thai)",
                                "pl" to "Polski (Polish)",
                                "zh-CN" to "简体中文 (Simplified Chinese)",
                                "zh-TW" to "繁體中文 (Traditional Chinese)",
                                "nl" to "Nederlands (Dutch)"
                            )
                            
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable { expanded = true },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.bodyLarge)
                                    Text(stringResource(R.string.settings_language_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Box {
                                    Text(languages[currentLocale] ?: "English", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                        languages.forEach { (tag, name) ->
                                            DropdownMenuItem(
                                                text = { Text(name) },
                                                onClick = {
                                                    androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags(tag))
                                                    expanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(stringResource(R.string.haptic_feedback), style = MaterialTheme.typography.bodyLarge)
                                    Text(stringResource(R.string.vibrate_on_interactions), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = isHapticEnabled,
                                    onCheckedChange = { viewModel.setHapticEnabled(it) }
                                )
                            }
                            
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(stringResource(R.string.sound_effects), style = MaterialTheme.typography.bodyLarge)
                                    Text(stringResource(R.string.play_audio_on_button_clicks), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = isSfxEnabled,
                                    onCheckedChange = { viewModel.setSfxEnabled(it) }
                                )
                            }
                            
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                    Text(stringResource(R.string.settings_history), style = MaterialTheme.typography.bodyLarge)
                                    Text(stringResource(R.string.settings_history_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = isHistoryEnabled,
                                    onCheckedChange = { viewModel.setHistoryEnabled(it) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary)
                                )
                            }
                            
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                    Text(stringResource(R.string.settings_remember_position), style = MaterialTheme.typography.bodyLarge)
                                    Text(stringResource(R.string.settings_remember_position_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = isRememberPositionEnabled,
                                    onCheckedChange = { viewModel.setRememberPositionEnabled(it) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary)
                                )
                            }
                            
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                            // Vanguard Zero-Trust Shield
                            val isVanguardEnabled by viewModel.isVanguardEnabled.collectAsState()
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                    Text(stringResource(R.string.settings_vanguard), style = MaterialTheme.typography.bodyLarge)
                                    Text(stringResource(R.string.settings_vanguard_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = isVanguardEnabled,
                                    onCheckedChange = { viewModel.setVanguardEnabled(it) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary)
                                )
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                            // Default PDF Viewer Setting
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showDefaultPdfDialog = true }
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                    Text(stringResource(R.string.settings_default_pdf_app), style = MaterialTheme.typography.bodyLarge)
                                    Text(stringResource(R.string.settings_default_pdf_app_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                            TextButton(
                                onClick = { showClearHistoryDialog = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.settings_clear_history), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    Text(stringResource(R.string.settings_about_legal), style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Start))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // The Lifetime Manifesto
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable { showManifestoDialog = true },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                    Text(stringResource(R.string.settings_manifesto_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                    Text(stringResource(R.string.settings_manifesto_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(
                                    imageVector = Icons.Rounded.AllInclusive,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                            // App Tour
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable { onOpenTour() },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                    Text(stringResource(R.string.settings_app_tour), style = MaterialTheme.typography.bodyLarge)
                                    Text(stringResource(R.string.settings_app_tour_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                            // Send Feedback & Support
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable {
                                    val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                                        data = Uri.parse("mailto:cucosandreiioan@gmail.com")
                                        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.feedback_email_subject))
                                        putExtra(Intent.EXTRA_TEXT, "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\nAndroid: ${android.os.Build.VERSION.RELEASE}\n\nFeedback:\n")
                                    }
                                    try {
                                        context.startActivity(Intent.createChooser(emailIntent, context.getString(R.string.settings_feedback)))
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "No email app found", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                    Text(stringResource(R.string.settings_feedback), style = MaterialTheme.typography.bodyLarge)
                                    Text(stringResource(R.string.settings_feedback_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(
                                    imageVector = Icons.Rounded.Email,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                            // Rate App
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable {
                                    val packageName = context.packageName
                                    try {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
                                    } catch (e: Exception) {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
                                    }
                                },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                    Text(stringResource(R.string.settings_rate_app), style = MaterialTheme.typography.bodyLarge)
                                    Text(stringResource(R.string.settings_rate_app_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(
                                    imageVector = Icons.Rounded.Star,
                                    contentDescription = null,
                                    tint = Color(0xFFFFD700)
                                )
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                            // Share App
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable {
                                    val packageName = context.packageName
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.app_name))
                                        val shareMessage = context.getString(R.string.settings_share_app_message, "https://play.google.com/store/apps/details?id=$packageName")
                                        putExtra(Intent.EXTRA_TEXT, shareMessage)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.settings_share_app)))
                                },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                    Text(stringResource(R.string.settings_share_app), style = MaterialTheme.typography.bodyLarge)
                                    Text(stringResource(R.string.settings_share_app_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(
                                    imageVector = Icons.Rounded.Share,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                            // Privacy Policy
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable {
                                    showPrivacyPolicyDialog = true
                                },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.settings_privacy_policy), style = MaterialTheme.typography.bodyLarge)
                                    Text(stringResource(R.string.settings_privacy_policy_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(
                                    imageVector = Icons.Rounded.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                            // Refund Policy
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable {
                                    showRefundPolicyDialog = true
                                },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.settings_refund_policy), style = MaterialTheme.typography.bodyLarge)
                                    Text(stringResource(R.string.settings_refund_policy_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(
                                    imageVector = Icons.Rounded.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                            // App Version & Check for Updates
                            val appVersion = remember {
                                try {
                                    val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                                    "v${pInfo.versionName ?: "1.0.2"}"
                                } catch (_: Exception) {
                                    "v1.0.2"
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${context.packageName}"))
                                        context.startActivity(intent)
                                    } catch (_: Exception) {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/kiss2oblivion/pdfchemy/releases"))
                                        context.startActivity(intent)
                                    }
                                },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.settings_version_title), style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        stringResource(R.string.settings_version_desc, appVersion),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Rounded.SystemUpdate,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(80.dp)) // Prevents ad banner from clipping the bottom content
                }
            }
        }
    }
}

@Composable
fun AndroidManifestoDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    fun openBrowser(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "Cannot open browser", Toast.LENGTH_SHORT).show()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.manifesto_btn_got_it))
            }
        },
        icon = {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.AllInclusive,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            }
        },
        title = {
            Text(
                stringResource(R.string.manifesto_dialog_title),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.manifesto_quote),
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = FontStyle.Italic,
                            lineHeight = 22.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            stringResource(R.string.manifesto_author),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Text(
                    stringResource(R.string.manifesto_guarantees_title),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GuaranteeItem(stringResource(R.string.manifesto_g1_title), stringResource(R.string.manifesto_g1_desc))
                    GuaranteeItem(stringResource(R.string.manifesto_g2_title), stringResource(R.string.manifesto_g2_desc))
                    GuaranteeItem(stringResource(R.string.manifesto_g3_title), stringResource(R.string.manifesto_g3_desc))
                    GuaranteeItem(stringResource(R.string.manifesto_g4_title), stringResource(R.string.manifesto_g4_desc))
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { openBrowser("https://github.com/kiss2oblivion/pdfchemy/issues/new") },
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Rounded.Lightbulb, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.manifesto_btn_edge_case), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }

                    OutlinedButton(
                        onClick = {
                            val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("mailto:cucosandreiioan@gmail.com")
                                putExtra(Intent.EXTRA_SUBJECT, "PDFchemy Feedback & Feature Request")
                            }
                            try {
                                context.startActivity(emailIntent)
                            } catch (_: Exception) {}
                        },
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Rounded.Email, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.manifesto_btn_email), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { openBrowser("https://ko-fi.com/andreiioancucos") },
                        modifier = Modifier.weight(1f).height(42.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5E5B))
                    ) {
                        Icon(Icons.Rounded.Favorite, contentDescription = null, modifier = Modifier.size(15.dp), tint = Color(0xFFFF5E5B))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.manifesto_btn_kofi), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }

                    OutlinedButton(
                        onClick = { openBrowser("https://revolut.me/andreiy886") },
                        modifier = Modifier.weight(1f).height(42.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF0075EB))
                    ) {
                        Icon(Icons.Rounded.CreditCard, contentDescription = null, modifier = Modifier.size(15.dp), tint = Color(0xFF0075EB))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.manifesto_btn_revolut), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }
    )
}

@Composable
private fun GuaranteeItem(title: String, desc: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp).padding(top = 2.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(desc, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun RecentFilesSection(
    viewModel: MainViewModel,
    onNavigate: ((Screen) -> Unit)? = null
) {
    val history by viewModel.historyList.collectAsState()
    val isVanguardEnabled by viewModel.isVanguardEnabled.collectAsState()
    var showVanguardBlockedDialog by remember { mutableStateOf(false) }
    var showVanguardEncryptedDialog by remember { mutableStateOf(false) }
    var vanguardPendingEncryptedUri by remember { mutableStateOf<Uri?>(null) }
    var isVanguardScanning by remember { mutableStateOf(false) }
    var vanguardScanningFileName by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    if (showVanguardBlockedDialog) {
        AlertDialog(
            onDismissRequest = { showVanguardBlockedDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.vanguard_blocked_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.vanguard_blocked_message),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = { showVanguardBlockedDialog = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }

    if (showVanguardEncryptedDialog) {
        AlertDialog(
            onDismissRequest = {
                showVanguardEncryptedDialog = false
                vanguardPendingEncryptedUri = null
            },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.vanguard_encrypted_title),
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.vanguard_encrypted_message),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showVanguardEncryptedDialog = false
                        vanguardPendingEncryptedUri?.let { encUri ->
                            onNavigate?.invoke(Screen.UnlockPdf(initialUri = encUri))
                        }
                        vanguardPendingEncryptedUri = null
                    }
                ) {
                    Text(stringResource(R.string.vanguard_action_unlock))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showVanguardEncryptedDialog = false
                        vanguardPendingEncryptedUri = null
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    VanguardScanningOverlay(
        visible = isVanguardScanning,
        fileName = vanguardScanningFileName
    )

    if (history.isNotEmpty()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.recent_activity),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    history.take(5).forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val uri = Uri.parse(item.uriString)
                                    val ext = item.name.substringAfterLast('.', "").lowercase()
                                    if (ext == "epub" && onNavigate != null) {
                                        onNavigate(Screen.ReflowReader(uri))
                                    } else if (ext == "pdf" && onNavigate != null) {
                                        scope.launch {
                                            com.pdfchemy.app.ui.guardDocumentLoad(onFailure = {
                                                showVanguardBlockedDialog = true
                                                isVanguardScanning = false
                                                vanguardScanningFileName = null
                                            }) {
                                                val stagedUri = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.pdfchemy.app.utils.DocumentStager.stageDocumentCancellable(context, uri).uri }
                                                if (isVanguardEnabled) {
                                                    isVanguardScanning = true
                                                    vanguardScanningFileName = item.name
                                                    try {
                                                        val threat = com.pdfchemy.app.logic.PdfSanitizerEngine.checkVanguardThreat(context, stagedUri)
                                                        when (threat) {
                                                            is com.pdfchemy.app.logic.VanguardThreatResult.Clean -> {
                                                                onNavigate(Screen.PdfEditor(initialPdfUri = stagedUri))
                                                            }
                                                            is com.pdfchemy.app.logic.VanguardThreatResult.EncryptedCannotVerify -> {
                                                                vanguardPendingEncryptedUri = stagedUri
                                                                showVanguardEncryptedDialog = true
                                                            }
                                                            is com.pdfchemy.app.logic.VanguardThreatResult.ExecutableThreat,
                                                            is com.pdfchemy.app.logic.VanguardThreatResult.ParseFailed -> {
                                                                showVanguardBlockedDialog = true
                                                            }
                                                        }
                                                    } finally {
                                                        isVanguardScanning = false
                                                        vanguardScanningFileName = null
                                                    }
                                                } else {
                                                    onNavigate(Screen.PdfReader(initialPdfUri = stagedUri))
                                                }
                                            }
                                        }
                                    } else {
                                        try {
                                            val mimeType = com.pdfchemy.app.utils.FileUtils.getMimeType(context, uri, item.name)
                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(uri, mimeType)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            try {
                                                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(uri, "*/*")
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                val chooser = Intent.createChooser(fallbackIntent, item.name)
                                                context.startActivity(chooser)
                                            } catch (e2: Exception) {
                                                if (ext == "pdf" && onNavigate != null) {
                                                    onNavigate(Screen.PdfReader(initialPdfUri = uri))
                                                } else {
                                                    AppLogger.e("Cannot open file from recent activity: ${item.name}", e2)
                                                    Toast.makeText(context, "No app available to open ${item.name}", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }
                                }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(item.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                Text(item.action, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BannerAd(isPremium: Boolean, modifier: Modifier = Modifier) {
    if (isPremium || !rememberAdsAllowed()) return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            modifier = Modifier.wrapContentSize(),
            factory = { context ->
                AdView(context).apply {
                    setAdSize(AdSize.BANNER)
                    adUnitId = com.pdfchemy.app.BuildConfig.BANNER_AD_UNIT_ID
                    loadAd(AdRequest.Builder().build())
                }
            },
            onRelease = { adView ->
                adView.destroy()
            }
        )
    }
}

@Composable
fun PremiumUpgradeScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val isPremium by viewModel.isPremium.collectAsState()
    val price by viewModel.premiumPrice.collectAsState()
    val context = LocalContext.current as android.app.Activity

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PremiumTopAppBar(title = stringResource(R.string.premium_title), onBack = onBack)
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                imageVector = Icons.Rounded.WorkspacePremium,
                contentDescription = "Premium",
                modifier = Modifier.size(120.dp),
                tint = Color(0xFFFFD700)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = if (isPremium) "You are a Premium User!" else "Support PDFchemy",
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            if (isPremium) {
                Text(
                    text = "Thank you for supporting our mission of privacy-first, offline document tools. All ads have been permanently removed.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "Unlock the ultimate offline PDF experience.\n\n• No Banner Ads\n• No Interstitial Ads\n• 100% Offline Privacy\n• One-time lifetime purchase",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = { viewModel.purchasePremium(context) },
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text(
                        text = if (price.isNotEmpty()) "Unlock Lifetime for $price" else "Loading price...",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}
}

data class OnboardingSlide(
    val titleRes: Int,
    val descRes: Int,
    val icon: ImageVector,
    val iconTint: Color
)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun OnboardingWalkthroughDialog(
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val slides = listOf(
        OnboardingSlide(
            titleRes = R.string.onboarding_slide1_title,
            descRes = R.string.onboarding_slide1_desc,
            icon = Icons.Rounded.Compress,
            iconTint = MaterialTheme.colorScheme.primary
        ),
        OnboardingSlide(
            titleRes = R.string.onboarding_slide2_title,
            descRes = R.string.onboarding_slide2_desc,
            icon = Icons.Rounded.DocumentScanner,
            iconTint = MaterialTheme.colorScheme.secondary
        ),
        OnboardingSlide(
            titleRes = R.string.onboarding_slide3_title,
            descRes = R.string.onboarding_slide3_desc,
            icon = Icons.Rounded.FolderOpen,
            iconTint = MaterialTheme.colorScheme.tertiary
        ),
        OnboardingSlide(
            titleRes = R.string.onboarding_slide4_title,
            descRes = R.string.onboarding_slide4_desc,
            icon = Icons.Rounded.Security,
            iconTint = Color(0xFF4CAF50)
        )
    )
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { slides.size })

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .padding(vertical = 16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Row with Title and Skip button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "PDFchemy Tools",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    if (pagerState.currentPage < slides.size - 1) {
                        TextButton(onClick = onDismiss) {
                            Text(
                                text = stringResource(R.string.onboarding_skip),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.size(48.dp))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Pager with slides
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                ) { page ->
                    val slide = slides[page]
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .clip(CircleShape)
                                .background(slide.iconTint.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = slide.icon,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = slide.iconTint
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Text(
                            text = stringResource(slide.titleRes),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = stringResource(slide.descRes),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 20.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Page Indicator Dots
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 20.dp)
                ) {
                    repeat(slides.size) { index ->
                        val isSelected = pagerState.currentPage == index
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .height(8.dp)
                                .width(if (isSelected) 24.dp else 8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                                )
                        )
                    }
                }

                // Next or Get Started Button
                val isLastPage = pagerState.currentPage == slides.size - 1
                Button(
                    onClick = {
                        if (isLastPage) {
                            onDismiss()
                        } else {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (isLastPage) stringResource(R.string.onboarding_get_started) else stringResource(R.string.onboarding_next),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun HardwareSafeguardDialog(
    assessment: com.pdfchemy.app.logic.DeviceGuard.CapacityAssessment,
    onDismiss: () -> Unit,
    onAction: (com.pdfchemy.app.logic.DeviceGuard.AlternativeAction) -> Unit,
    onProceedAnyway: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = if (assessment.status == com.pdfchemy.app.logic.DeviceGuard.CapacityStatus.INSUFFICIENT_HARDWARE)
                    MaterialTheme.colorScheme.error
                else
                    Color(0xFFFFA000)
            )
        },
        title = {
            Text(
                stringResource(R.string.device_guard_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(
                        R.string.device_guard_desc,
                        assessment.pageCount,
                        com.pdfchemy.app.logic.DeviceGuard.formatFileSize(assessment.fileSizeBytes),
                        "${assessment.requiredMemMb} MB",
                        "${assessment.availableMemMb} MB"
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val (titleRes, descRes, btnRes) = when (assessment.recommendedAlternative) {
                            com.pdfchemy.app.logic.DeviceGuard.AlternativeAction.SPLIT_FIRST ->
                                Triple(R.string.alt_split_title, R.string.alt_split_desc, R.string.alt_split_btn)
                            com.pdfchemy.app.logic.DeviceGuard.AlternativeAction.PAGE_RANGE ->
                                Triple(R.string.alt_range_title, R.string.alt_range_desc, R.string.alt_range_btn)
                            com.pdfchemy.app.logic.DeviceGuard.AlternativeAction.LIGHTWEIGHT_MODE ->
                                Triple(R.string.alt_lightweight_title, R.string.alt_lightweight_desc, R.string.alt_lightweight_btn)
                            com.pdfchemy.app.logic.DeviceGuard.AlternativeAction.FREE_RAM ->
                                Triple(R.string.alt_free_ram_title, R.string.alt_free_ram_desc, R.string.alt_free_ram_btn)
                        }

                        Text(
                            text = stringResource(titleRes),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(descRes),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(
                            onClick = {
                                onAction(assessment.recommendedAlternative)
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(stringResource(btnRes))
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (assessment.canProceedAnyway) {
                TextButton(onClick = onProceedAnyway) {
                    Text(stringResource(R.string.btn_proceed_anyway))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

