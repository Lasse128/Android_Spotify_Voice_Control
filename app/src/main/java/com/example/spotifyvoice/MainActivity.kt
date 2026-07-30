package com.example.spotifyvoice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import android.content.Intent
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import android.util.Log
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.basicMarquee
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

enum class AppScreen {
    Main, Settings
}

class AppThemeState(
    var isDark: Boolean,
    var primaryColor: Color
) {
    val backgroundColor: Color get() = if (isDark) Color(0xFF131313) else Color(0xFFF5F5F5)
    val surfaceColor: Color get() = if (isDark) Color(0xFF1C1B1B) else Color(0xFFFFFFFF)
    val surfaceHighColor: Color get() = if (isDark) Color(0xFF2A2A2A) else Color(0xFFE0E0E0)
    val onSurfaceColor: Color get() = if (isDark) Color(0xFFE5E2E1) else Color(0xFF131313)
    val onSurfaceVariant: Color get() = if (isDark) Color(0xFFBCCBB9) else Color(0xFF505050)
    val onPrimaryColor: Color get() = if (isDark) Color(0xFF003914) else Color.White
    val dividerColor: Color get() = if (isDark) Color(0xFF3D4A3D) else Color(0xFFCCCCCC)
}
 
class MainActivity : ComponentActivity() {

    private val clientId = "e6d0e16edeba44d99476f54032860e25"
    private val redirectUri = "spotifyvoicecontrol://callback"
    private lateinit var spotifyController: SpotifyController
    private lateinit var voiceController: VoiceController
    private lateinit var audioFeedbackController: AudioFeedbackController
    companion object {
        private val _accessToken = MutableStateFlow<String?>(null)
        val accessTokenFlow: StateFlow<String?> = _accessToken
        var currentCodeVerifier: String = ""
        const val PREFS_NAME = "SpotifyVoicePrefs"
        const val KEY_REFRESH_TOKEN = "refresh_token"
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { uri ->
            if (uri.scheme == "spotifyvoicecontrol" && uri.host == "callback") {
                val code = uri.getQueryParameter("code")
                if (code != null) {
                    Log.d("SpotifyVoice", "Auth Code obtained successfully")
                    lifecycleScope.launch {
                        try {
                            val authService = SpotifyAuthService.create()
                            val response = authService.getToken(
                                clientId = clientId,
                                grantType = "authorization_code",
                                code = code,
                                redirectUri = redirectUri,
                                codeVerifier = currentCodeVerifier
                            )
                            _accessToken.value = response.access_token
                            if (response.refresh_token != null) {
                                getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                                    .edit()
                                    .putString(KEY_REFRESH_TOKEN, response.refresh_token)
                                    .apply()
                            }
                            Log.d("SpotifyVoice", "Access Token obtained via PKCE")
                        } catch (e: Exception) {
                            Log.e("SpotifyVoice", "Failed to exchange code for token", e)
                        }
                    }
                } else {
                    val error = uri.getQueryParameter("error")
                    Log.e("SpotifyVoice", "Auth failed with error: $error")
                }
            }
        }
    }

    private fun generateCodeVerifier(): String {
        val secureRandom = java.security.SecureRandom()
        val codeVerifier = ByteArray(32)
        secureRandom.nextBytes(codeVerifier)
        return android.util.Base64.encodeToString(codeVerifier, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    }

    private fun generateCodeChallenge(codeVerifier: String): String {
        val bytes = codeVerifier.toByteArray(Charsets.US_ASCII)
        val messageDigest = java.security.MessageDigest.getInstance("SHA-256")
        messageDigest.update(bytes, 0, bytes.size)
        val digest = messageDigest.digest()
        return android.util.Base64.encodeToString(digest, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    }

    private fun startLoginFlow() {
        currentCodeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(currentCodeVerifier)
        val url = "https://accounts.spotify.com/authorize?client_id=$clientId&response_type=code&redirect_uri=$redirectUri&code_challenge_method=S256&code_challenge=$codeChallenge&scope=app-remote-control%20user-read-playback-state%20user-read-currently-playing%20user-modify-playback-state%20playlist-read-private%20playlist-read-collaborative%20playlist-modify-public%20playlist-modify-private&show_dialog=true"
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
        startActivity(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        spotifyController = SpotifyController(this, clientId, redirectUri)
        audioFeedbackController = AudioFeedbackController(this)
        
        // Always connect to Spotify App Remote on startup
        spotifyController.connect {}

        setContent {
            val prefs = getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)

            var isDark by remember { mutableStateOf(prefs.getBoolean("isDark", true)) }
            var primaryColor by remember { mutableStateOf(Color(prefs.getInt("primaryColorArgb", android.graphics.Color.parseColor("#1DB954")))) } 
            
            val themeState = remember(isDark, primaryColor) {
                AppThemeState(isDark, primaryColor)
            }

            var recognizedText by remember { mutableStateOf("") }
            var showRecognizedText by remember { mutableStateOf(false) }

            LaunchedEffect(recognizedText) {
                if (recognizedText.isNotEmpty()) {
                    showRecognizedText = true
                    kotlinx.coroutines.delay(4000)
                    showRecognizedText = false
                } else {
                    showRecognizedText = false
                }
            }
            
            var isListening by remember { mutableStateOf(false) }
            val commandParser = remember { CommandParser() }
            
            var showMediaButtons by remember { mutableStateOf(prefs.getBoolean("showMediaButtons", true)) }
            var showSongInfo by remember { mutableStateOf(prefs.getBoolean("showSongInfo", true)) }
            var appLanguage by remember { mutableStateOf(prefs.getString("appLanguage", "Deutsch") ?: "Deutsch") }
            var autoMute by remember { mutableStateOf(prefs.getBoolean("autoMute", false)) }
            var voiceActivation by remember { mutableStateOf(prefs.getBoolean("voiceActivation", false)) }

            LaunchedEffect(isDark, primaryColor, showMediaButtons, showSongInfo, appLanguage, autoMute, voiceActivation) {
                prefs.edit().apply {
                    putBoolean("isDark", isDark)
                    putInt("primaryColorArgb", primaryColor.toArgb())
                    putBoolean("showMediaButtons", showMediaButtons)
                    putBoolean("showSongInfo", showSongInfo)
                    putString("appLanguage", appLanguage)
                    putBoolean("autoMute", autoMute)
                    putBoolean("voiceActivation", voiceActivation)
                }.apply()
            }
            
            val accessToken by accessTokenFlow.collectAsState()
            val isPlaying by spotifyController.isPlaying.collectAsState()
            val currentSongInfo by spotifyController.currentSongInfo.collectAsState()
            val coroutineScope = rememberCoroutineScope()
            val spotifyApiService = remember { SpotifyApiService.create() }
            
            val context = LocalContext.current
            
            // Apply Auto-Mute setting
            LaunchedEffect(autoMute) {
                val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && notificationManager.isNotificationPolicyAccessGranted) {
                    if (autoMute) {
                        notificationManager.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_NONE)
                    } else {
                        notificationManager.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
                    }
                }
            }

            // Authenticate on startup to get Web API token
            LaunchedEffect(Unit) {
                if (accessTokenFlow.value == null) {
                    val prefs = getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                    val savedRefreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)
                    
                    if (savedRefreshToken != null) {
                        try {
                            val authService = SpotifyAuthService.create()
                            val response = authService.refreshToken(
                                clientId = clientId,
                                refreshToken = savedRefreshToken
                            )
                            _accessToken.value = response.access_token
                            if (response.refresh_token != null) {
                                prefs.edit().putString(KEY_REFRESH_TOKEN, response.refresh_token).apply()
                            }
                        } catch (e: Exception) {
                            Log.e("SpotifyVoice", "Refresh token failed", e)
                        }
                    }
                }
            }

            DisposableEffect(Unit) {
                voiceController = VoiceController(
                    context = this@MainActivity,
                    onStateChanged = { listening -> isListening = listening },
                    onErrorState = { errorMsg -> recognizedText = errorMsg },
                    onCommandRecognized = { command ->
                        val currentToken = _accessToken.value
                        if (currentToken == null) {
                            recognizedText = "Bitte verbinde dich zuerst mit Spotify in den Einstellungen."
                        } else {
                            val action = commandParser.parse(command)
                            val actionDetails = when(action) {
                                is SpotifyAction.PlaySpecific -> "Spiele: ${action.query}"
                                is SpotifyAction.AddToQueue -> "Zur Warteschlange hinzugefügt: ${action.query}"
                                is SpotifyAction.AddCurrentTrackToPlaylist -> "Zu Playlist hinzugefügt: ${action.playlistName}"
                                is SpotifyAction.SeekRelative -> "Spule ${if (action.offsetMs > 0) "vor" else "zurück"} (${Math.abs(action.offsetMs / 1000)}s)"
                                is SpotifyAction.Play -> "Wiedergabe fortgesetzt"
                                is SpotifyAction.Pause -> "Wiedergabe pausiert"
                                is SpotifyAction.Next -> "Nächster Titel"
                                is SpotifyAction.Previous -> "Vorheriger Titel"
                                is SpotifyAction.RestartTrack -> "Titel neu gestartet"
                                is SpotifyAction.ShuffleOn -> "Shuffle aktiviert"
                                is SpotifyAction.ShuffleOff -> "Shuffle deaktiviert"
                                is SpotifyAction.Repeat -> "Wiederholung aktiviert"
                                is SpotifyAction.VolumeUp -> "Lautstärke erhöht"
                                is SpotifyAction.VolumeDown -> "Lautstärke verringert"
                                is SpotifyAction.VolumeUpDouble -> "Lautstärke stark erhöht"
                                is SpotifyAction.VolumeDownDouble -> "Lautstärke stark verringert"
                                is SpotifyAction.WhatIsPlaying -> "Songinfo aktualisiert"
                                is SpotifyAction.Unknown -> "Befehl nicht erkannt: $command"
                            }
                            recognizedText = actionDetails
                        
                            // Execute basic Spotify actions
                            when (action) {
                                is SpotifyAction.Play -> { spotifyController.resume(); audioFeedbackController.playSuccessTone() }
                                is SpotifyAction.Pause -> { spotifyController.pause(); audioFeedbackController.playSuccessTone() }
                                is SpotifyAction.Next -> { spotifyController.next(); audioFeedbackController.playSuccessTone() }
                                is SpotifyAction.Previous -> { spotifyController.previous(); audioFeedbackController.playSuccessTone() }
                                is SpotifyAction.RestartTrack -> { spotifyController.restartTrack(); audioFeedbackController.playSuccessTone() }
                                is SpotifyAction.SeekRelative -> { spotifyController.seekRelative(action.offsetMs); audioFeedbackController.playSuccessTone() }
                                is SpotifyAction.ShuffleOn -> { spotifyController.setShuffle(true); audioFeedbackController.playSuccessTone() }
                                is SpotifyAction.ShuffleOff -> { spotifyController.setShuffle(false); audioFeedbackController.playSuccessTone() }
                                is SpotifyAction.Repeat -> { spotifyController.setRepeat(2); audioFeedbackController.playSuccessTone() }
                                is SpotifyAction.VolumeUp -> {
                                    val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                                    audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI)
                                    audioFeedbackController.playSuccessTone()
                                }
                                is SpotifyAction.VolumeDown -> {
                                    val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                                    audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI)
                                    audioFeedbackController.playSuccessTone()
                                }
                                is SpotifyAction.VolumeUpDouble -> {
                                    val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                                    audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI)
                                    audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI)
                                    audioFeedbackController.playSuccessTone()
                                }
                                is SpotifyAction.VolumeDownDouble -> {
                                    val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                                    audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI)
                                    audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI)
                                    audioFeedbackController.playSuccessTone()
                                }
                                is SpotifyAction.WhatIsPlaying -> {
                                    val songInfo = currentSongInfo
                                    if (songInfo != null) {
                                        val parts = songInfo.split(" - ", limit = 2)
                                        val cleanSongInfo = if (parts.size == 2) "${parts[1]} von ${parts[0]}" else songInfo
                                        audioFeedbackController.speak("Es läuft gerade: $cleanSongInfo")
                                    } else {
                                        audioFeedbackController.speak("Ich weiß leider nicht, was gerade läuft.")
                                    }
                                }
                                is SpotifyAction.PlaySpecific -> {
                                    coroutineScope.launch {
                                        try {
                                            if (action.type == "my_playlist") {
                                                val playlistsResponse = spotifyApiService.getMyPlaylists("Bearer $currentToken")
                                                val matchedPlaylist = playlistsResponse.items.find { 
                                                    it.name.contains(action.query, ignoreCase = true) 
                                                }
                                                if (matchedPlaylist != null) {
                                                    spotifyController.play(matchedPlaylist.uri)
                                                    recognizedText = "$recognizedText\n-> Spiele deine Playlist: ${matchedPlaylist.name}"
                                                    audioFeedbackController.playSuccessTone()
                                                } else {
                                                    recognizedText = "$recognizedText\n-> Deine Playlist nicht gefunden."
                                                }
                                            } else {
                                                val response = spotifyApiService.search("Bearer $currentToken", action.query)
                                                val firstArtist = response.artists?.items?.firstOrNull { 
                                                    it.name.contains(action.query, ignoreCase = true) || action.query.contains(it.name, ignoreCase = true) 
                                                }
                                                if (firstArtist != null) {
                                                    spotifyController.play(firstArtist.uri)
                                                    recognizedText = "$recognizedText\n-> Spiele Künstler: ${firstArtist.name}"
                                                    audioFeedbackController.playSuccessTone()
                                                } else {
                                                    val firstTrack = response.tracks?.items?.firstOrNull()
                                                    if (firstTrack != null) {
                                                        spotifyController.play(firstTrack.uri)
                                                        recognizedText = "$recognizedText\n-> Spiele: ${firstTrack.name}"
                                                        audioFeedbackController.playSuccessTone()
                                                    } else {
                                                        recognizedText = "$recognizedText\n-> Nichts gefunden."
                                                    }
                                                }
                                            }
                                        } catch (e: Exception) {
                                            Log.e("SpotifyVoice", "Search failed", e)
                                            recognizedText = "$recognizedText\n-> Fehler bei der Suche."
                                        }
                                    }
                                }
                                is SpotifyAction.AddToQueue -> {
                                    coroutineScope.launch {
                                        try {
                                            val response = spotifyApiService.search("Bearer $currentToken", action.query)
                                            val firstArtist = response.artists?.items?.firstOrNull { 
                                                it.name.contains(action.query, ignoreCase = true) || action.query.contains(it.name, ignoreCase = true) 
                                            }
                                            if (firstArtist != null) {
                                                spotifyController.play(firstArtist.uri)
                                                recognizedText = "$recognizedText\n-> Spiele Künstler: ${firstArtist.name} (Warteschlange nicht möglich für ganze Künstler)"
                                                audioFeedbackController.playSuccessTone()
                                            } else {
                                                val firstTrack = response.tracks?.items?.firstOrNull()
                                                if (firstTrack != null) {
                                                    spotifyController.queue(firstTrack.uri)
                                                    recognizedText = "$recognizedText\n-> In Warteschlange: ${firstTrack.name}"
                                                    audioFeedbackController.playSuccessTone()
                                                } else {
                                                    recognizedText = "$recognizedText\n-> Nichts gefunden."
                                                }
                                            }
                                        } catch (e: Exception) {
                                            Log.e("SpotifyVoice", "Search failed", e)
                                            recognizedText = "$recognizedText\n-> Fehler bei der Suche."
                                        }
                                    }
                                }
                                is SpotifyAction.AddCurrentTrackToPlaylist -> {
                                    recognizedText = "$recognizedText\n-> Lade aktuellen Song..."
                                    coroutineScope.launch {
                                        try {
                                            val currentlyPlaying = spotifyApiService.getCurrentlyPlaying("Bearer $currentToken")
                                            val currentTrackUri = currentlyPlaying?.item?.uri
                                            
                                            if (currentTrackUri != null) {
                                                recognizedText = "$recognizedText\n-> Lade Playlists..."
                                                val userProfile = spotifyApiService.getMe("Bearer $currentToken")
                                                val playlistsResponse = spotifyApiService.getMyPlaylists("Bearer $currentToken")
                                                val matchedPlaylist = playlistsResponse.items.find { 
                                                    it.owner.id == userProfile.id && it.name.contains(action.playlistName, ignoreCase = true) 
                                                }
                                                if (matchedPlaylist != null) {
                                                    recognizedText = "$recognizedText\n-> Füge Song hinzu ($currentTrackUri)..."
                                                    val resultMessage = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                        try {
                                                            kotlinx.coroutines.withTimeout(5000) {
                                                                val response = spotifyApiService.addTrackToPlaylist(
                                                                    "Bearer $currentToken", 
                                                                    matchedPlaylist.id, 
                                                                    AddTrackRequest(listOf(currentTrackUri))
                                                                )
                                                                if (response.isSuccessful) {
                                                                    audioFeedbackController.playSuccessTone()
                                                                    "-> Zu Playlist '${matchedPlaylist.name}' hinzugefügt.\n(Track: $currentTrackUri)"
                                                                } else {
                                                                    val errorBody = response.errorBody()?.string() ?: ""
                                                                    val msg = try {
                                                                        org.json.JSONObject(errorBody).getJSONObject("error").getString("message")
                                                                    } catch (parseEx: Exception) {
                                                                        errorBody.take(100)
                                                                    }
                                                                    "-> Fehler beim Hinzufügen:\nHTTP ${response.code()} - $msg"
                                                                }
                                                            }
                                                        } catch (e: Throwable) {
                                                            "-> Fehler beim Hinzufügen:\n${e.javaClass.simpleName} - ${e.message?.take(50)}"
                                                        }
                                                    }
                                                    recognizedText = "$recognizedText\n$resultMessage"
                                                } else {
                                                    recognizedText = "$recognizedText\n-> Playlist '${action.playlistName}' nicht gefunden."
                                                }
                                            } else {
                                                recognizedText = "$recognizedText\n-> Konnte aktuellen Song nicht ermitteln."
                                            }
                                        } catch (e: Throwable) {
                                            Log.e("SpotifyVoice", "Add to playlist failed", e)
                                            recognizedText = "$recognizedText\n-> Fehler beim Vorbereiten: ${e.javaClass.simpleName}"
                                        }
                                    }
                                }
                                else -> {}
                            }
                        }
                })
                onDispose { voiceController.destroy() }
            }

            MaterialTheme(
                colorScheme = if (isDark) darkColorScheme(background = themeState.backgroundColor, primary = themeState.primaryColor)
                              else lightColorScheme(background = themeState.backgroundColor, primary = themeState.primaryColor)
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var currentScreen by remember { mutableStateOf(AppScreen.Main) }

                    when (currentScreen) {
                        AppScreen.Main -> {
                            val isPlaying by spotifyController.isPlaying.collectAsState()
                            DriveSafeScreen(
                                themeState = themeState,
                                recognizedText = recognizedText,
                                showRecognizedText = showRecognizedText,
                                isListening = isListening,
                                isPlaying = isPlaying,
                                showConnectTooltip = accessToken == null,
                                showMediaButtons = showMediaButtons,
                                showSongInfo = showSongInfo,
                                currentSongInfo = currentSongInfo,
                                onMicClick = {
                                    if (isListening) {
                                        voiceController.stopListening()
                                        isListening = false
                                        recognizedText = "Abgebrochen"
                                    } else {
                                        isListening = true
                                        recognizedText = ""
                                        voiceController.startListening()
                                    }
                                },
                                onPlayClick = { spotifyController.resume() },
                                onPauseClick = { spotifyController.pause() },
                                onResumeClick = { spotifyController.resume() },
                                onNextClick = { spotifyController.next() },
                                onPrevClick = { spotifyController.previous() },
                                onSettingsClick = { currentScreen = AppScreen.Settings }
                            )
                        }
                        AppScreen.Settings -> {
                            SettingsScreen(
                                themeState = themeState,
                                isConnected = accessToken != null,
                                showMediaButtons = showMediaButtons,
                                onShowMediaButtonsChange = { showMediaButtons = it },
                                showSongInfo = showSongInfo,
                                onShowSongInfoChange = { showSongInfo = it },
                                appLanguage = appLanguage,
                                onAppLanguageChange = { appLanguage = it },
                                autoMute = autoMute,
                                onAutoMuteChange = { autoMute = it },
                                voiceActivation = voiceActivation,
                                onVoiceActivationChange = { voiceActivation = it },
                                onThemeChange = { dark -> isDark = dark },
                                onColorChange = { color -> primaryColor = color },
                                onConnectClick = { startLoginFlow() },
                                onDisconnectClick = {
                                    val prefs = getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                                    prefs.edit().remove(KEY_REFRESH_TOKEN).apply()
                                    _accessToken.value = null
                                    spotifyController.disconnect()
                                },
                                onBackClick = { currentScreen = AppScreen.Main }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        spotifyController.disconnect()
        if (::voiceController.isInitialized) {
            voiceController.destroy()
        }
        if (::audioFeedbackController.isInitialized) {
            audioFeedbackController.destroy()
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DriveSafeScreen(
    themeState: AppThemeState,
    recognizedText: String,
    showRecognizedText: Boolean,
    isListening: Boolean,
    isPlaying: Boolean,
    showConnectTooltip: Boolean,
    showMediaButtons: Boolean,
    showSongInfo: Boolean,
    currentSongInfo: String?,
    onMicClick: () -> Unit,
    onPlayClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onNextClick: () -> Unit,
    onPrevClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val context = LocalContext.current

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val scaleFactor = (configuration.screenHeightDp / 850f).coerceIn(0.75f, 1.1f)
    val textScale = (configuration.screenHeightDp / 850f).coerceIn(0.85f, 1.05f)

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            onMicClick()
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val radialGradient = androidx.compose.ui.graphics.Brush.radialGradient(
        colors = listOf(
            themeState.primaryColor.copy(alpha = if (themeState.isDark) 0.15f else 0.1f), 
            themeState.backgroundColor
        ),
        radius = 1500f
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(radialGradient)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top App Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .background(themeState.onSurfaceColor.copy(alpha = 0.05f))
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.SignalCellularAlt,
                        contentDescription = "Signal",
                        tint = themeState.primaryColor,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "DriveSafe",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = themeState.onSurfaceColor,
                        letterSpacing = (-0.5).sp
                    )
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showConnectTooltip) {
                        Surface(
                            color = themeState.primaryColor,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(
                                text = "Connect to Spotify \u2192", 
                                color = themeState.onPrimaryColor,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                    IconButton(
                        onClick = onSettingsClick,
                        modifier = Modifier
                            .size(48.dp)
                            .background(themeState.onSurfaceColor.copy(alpha = 0.05f), CircleShape)
                            .clip(CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "Settings",
                            tint = themeState.onSurfaceColor.copy(alpha = 0.8f),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
            
            Divider(color = themeState.onSurfaceColor.copy(alpha = 0.05f))

            // Main Content Canvas
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                // Background ambient glow
                val ambientGlowAlpha = if (themeState.isDark) 0.15f else 0.35f
                val ambientGlowGradient = androidx.compose.ui.graphics.Brush.radialGradient(
                    colors = listOf(themeState.primaryColor.copy(alpha = ambientGlowAlpha), Color.Transparent),
                    radius = 600f
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ambientGlowGradient)
                )

                // Central Voice Control
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxSize().padding(vertical = 16.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    ) {
                        BoxWithConstraints(contentAlignment = Alignment.Center) {
                            val maxMicSize = 280.dp
                            val availableSize = minOf(maxWidth, maxHeight)
                            // Keep enough padding so the 1.3x pulse doesn't clip
                            val buttonSize = minOf(maxMicSize, availableSize / 1.35f)
                            
                            if (isListening) {
                                Box(
                                    modifier = Modifier
                                        .size(buttonSize)
                                        .scale(pulseScale)
                                        .background(themeState.primaryColor.copy(alpha = 0.2f), CircleShape)
                                )
                            }
                            
                            val micBgColor = if (themeState.isDark) {
                                if (isListening) Color.White.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.03f)
                            } else {
                                if (isListening) themeState.onSurfaceColor.copy(alpha = 0.15f) else themeState.surfaceColor.copy(alpha = 0.75f)
                            }
                            
                            Box(
                                modifier = Modifier
                                    .size(buttonSize)
                                    .background(micBgColor, CircleShape)
                                    .border(4.dp, themeState.primaryColor, CircleShape)
                                    .clip(CircleShape)
                                    .clickable {
                                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                            onMicClick()
                                        } else {
                                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Mic,
                                    contentDescription = "Voice Control",
                                    tint = themeState.primaryColor,
                                    modifier = Modifier.size(buttonSize * 0.4f)
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(0.dp))
                    Text(
                        text = if (isListening) "Hört zu..." else "Bereit",
                        fontSize = (32 * textScale).sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isListening) themeState.onSurfaceColor else themeState.primaryColor,
                        letterSpacing = (-0.5).sp
                    )
                    
                    val activeBoxesCount = listOf(showSongInfo, showMediaButtons).count { it }
                    val textSpacing = when (activeBoxesCount) {
                        0 -> 48.dp
                        1 -> 24.dp
                        else -> 8.dp
                    }
                    Spacer(modifier = Modifier.height(textSpacing))
                    
                    Box(
                        modifier = Modifier.height(32.dp * textScale),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = showRecognizedText,
                            enter = fadeIn(animationSpec = tween(durationMillis = 300)),
                            exit = fadeOut(animationSpec = tween(durationMillis = 1500))
                        ) {
                            Text(
                                text = recognizedText,
                                fontSize = (20 * textScale).sp,
                                color = themeState.onSurfaceColor.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }

            // Bottom Section: Media Controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp, top = 8.dp)
            ) {
                if (showSongInfo && currentSongInfo != null) {
                    val parts = currentSongInfo.split(" - ", limit = 2)
                    val artist = if (parts.size > 1) parts[0] else ""
                    val track = if (parts.size > 1) parts[1] else parts[0]
                    
                    val boxBgAlpha = if (themeState.isDark) 0.03f else 0.12f
                    val boxBorderAlpha = if (themeState.isDark) 0.1f else 0.25f

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .padding(bottom = 16.dp)
                            .background(themeState.onSurfaceColor.copy(alpha = boxBgAlpha), RoundedCornerShape(24.dp))
                            .border(1.dp, themeState.onSurfaceColor.copy(alpha = boxBorderAlpha), RoundedCornerShape(24.dp))
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = track, 
                            fontWeight = FontWeight.Bold, 
                            color = themeState.primaryColor, 
                            fontSize = (20 * textScale).sp, 
                            letterSpacing = (-0.5).sp,
                            maxLines = 1,
                            modifier = Modifier.basicMarquee()
                        )
                        if (artist.isNotEmpty()) {
                            Text(
                                text = artist, 
                                color = themeState.onSurfaceColor.copy(alpha = 0.8f), 
                                fontSize = (18 * textScale).sp,
                                maxLines = 1,
                                modifier = Modifier.basicMarquee()
                            )
                        }
                    }
                }
                
                if (showMediaButtons) {
                    val boxBgAlpha = if (themeState.isDark) 0.03f else 0.12f
                    val boxBorderAlpha = if (themeState.isDark) 0.1f else 0.25f

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .background(themeState.onSurfaceColor.copy(alpha = boxBgAlpha), RoundedCornerShape(40.dp))
                            .border(1.dp, themeState.onSurfaceColor.copy(alpha = boxBorderAlpha), RoundedCornerShape(40.dp))
                            .padding(horizontal = 16.dp, vertical = 24.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onPrevClick,
                            modifier = Modifier.size(80.dp * scaleFactor)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SkipPrevious,
                                contentDescription = "Previous",
                                tint = themeState.primaryColor,
                                modifier = Modifier.size(48.dp * scaleFactor)
                            )
                        }
                        
                        Box(
                            modifier = Modifier
                                .size(96.dp * scaleFactor)
                                .background(themeState.primaryColor.copy(alpha = 0.3f), CircleShape)
                                .border(1.dp, themeState.primaryColor.copy(alpha = 0.3f), CircleShape)
                                .clip(CircleShape)
                                .clickable { if (isPlaying) onPauseClick() else onPlayClick() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = themeState.primaryColor,
                                modifier = Modifier.size(56.dp * scaleFactor)
                            )
                        }

                        IconButton(
                            onClick = onNextClick,
                            modifier = Modifier.size(80.dp * scaleFactor)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SkipNext,
                                contentDescription = "Next",
                                tint = themeState.primaryColor,
                                modifier = Modifier.size(48.dp * scaleFactor)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    themeState: AppThemeState,
    isConnected: Boolean,
    showMediaButtons: Boolean,
    onShowMediaButtonsChange: (Boolean) -> Unit,
    showSongInfo: Boolean,
    onShowSongInfoChange: (Boolean) -> Unit,
    appLanguage: String,
    onAppLanguageChange: (String) -> Unit,
    autoMute: Boolean,
    onAutoMuteChange: (Boolean) -> Unit,
    voiceActivation: Boolean,
    onVoiceActivationChange: (Boolean) -> Unit,
    onThemeChange: (Boolean) -> Unit,
    onColorChange: (Color) -> Unit,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(themeState.backgroundColor)
    ) {
        // Top App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = themeState.onSurfaceColor,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "Settings",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = themeState.onSurfaceColor
            )
        }

        Divider(color = themeState.dividerColor)

        // Scrollable Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            // Connect to Spotify
            if (!isConnected) {
                Button(
                    onClick = onConnectClick,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = themeState.primaryColor),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Connect to Spotify", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = themeState.onPrimaryColor)
                }
            }

            // Appearance
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("APPEARANCE", color = themeState.onSurfaceColor.copy(alpha = 0.6f), fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text("Choose your preferred visual mode for driving.", color = themeState.onSurfaceVariant, fontSize = 16.sp)
                
                Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val darkBtnColor = if (themeState.isDark) themeState.surfaceHighColor else themeState.surfaceColor
                    val darkBorderColor = if (themeState.isDark) themeState.primaryColor else themeState.dividerColor
                    val lightBtnColor = if (!themeState.isDark) themeState.surfaceHighColor else themeState.surfaceColor
                    val lightBorderColor = if (!themeState.isDark) themeState.primaryColor else themeState.dividerColor
                    
                    Button(
                        onClick = { onThemeChange(true) },
                        modifier = Modifier.weight(1f).height(80.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = darkBtnColor),
                        border = BorderStroke(if (themeState.isDark) 2.dp else 1.dp, darkBorderColor)
                    ) {
                        Text("Dark Mode", color = if (themeState.isDark) themeState.onSurfaceColor else themeState.onSurfaceVariant, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { onThemeChange(false) },
                        modifier = Modifier.weight(1f).height(80.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = lightBtnColor),
                        border = BorderStroke(if (!themeState.isDark) 2.dp else 1.dp, lightBorderColor)
                    ) {
                        Text("Light Mode", color = if (!themeState.isDark) themeState.onSurfaceColor else themeState.onSurfaceVariant, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Theme Accent
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("THEME ACCENT", color = themeState.onSurfaceColor.copy(alpha = 0.6f), fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text("Select a primary color for navigation and controls.", color = themeState.onSurfaceVariant, fontSize = 16.sp)
                
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val colors = listOf(
                        Color(0xFF1DB954) to "DRIVE",
                        Color(0xFF3D5AFE) to "OCEAN",
                        Color(0xFFFF1744) to "RUSH",
                        Color(0xFFFF9100) to "SUNSET",
                        Color(0xFFD500F9) to "NEON"
                    )
                    colors.forEachIndexed { index, (color, name) ->
                        val isSelected = themeState.primaryColor == color
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable { onColorChange(color) }
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .background(color, CircleShape)
                                    .border(if (isSelected) 3.dp else 1.dp, if (isSelected) (if (themeState.isDark) Color.White else Color.Black) else themeState.dividerColor, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(Icons.Rounded.Check, contentDescription = null, tint = if (themeState.isDark) Color.Black else Color.White)
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(name, color = if (isSelected) themeState.primaryColor else themeState.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Interface Settings
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("INTERFACE & LANGUAGE", color = themeState.onSurfaceColor.copy(alpha = 0.6f), fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                
                // Language Selection
                var expanded by remember { mutableStateOf(false) }
                val languages = listOf("Deutsch", "English", "Español", "Français")
                
                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(themeState.surfaceColor, RoundedCornerShape(16.dp))
                            .border(1.dp, themeState.dividerColor, RoundedCornerShape(16.dp))
                            .clickable { expanded = true }
                            .padding(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("App Language", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = themeState.onSurfaceColor)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Current: $appLanguage", fontSize = 16.sp, color = themeState.onSurfaceVariant)
                        }
                        Icon(
                            imageVector = Icons.Rounded.ArrowDropDown,
                            contentDescription = "Select Language",
                            tint = themeState.onSurfaceColor,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.background(themeState.surfaceHighColor)
                    ) {
                        languages.forEach { language ->
                            DropdownMenuItem(
                                text = { Text(language, color = themeState.onSurfaceColor) },
                                onClick = {
                                    onAppLanguageChange(language)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
                
                // Show Media Buttons Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(themeState.surfaceColor, RoundedCornerShape(16.dp))
                        .border(1.dp, themeState.dividerColor, RoundedCornerShape(16.dp))
                        .clickable { onShowMediaButtonsChange(!showMediaButtons) }
                        .padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Show Media Buttons", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = themeState.onSurfaceColor)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Display Prev/Play/Next buttons.", fontSize = 16.sp, color = themeState.onSurfaceVariant)
                    }
                    Switch(
                        checked = showMediaButtons,
                        onCheckedChange = { onShowMediaButtonsChange(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = themeState.primaryColor, checkedThumbColor = themeState.onPrimaryColor)
                    )
                }
                
                // Show Song Info Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(themeState.surfaceColor, RoundedCornerShape(16.dp))
                        .border(1.dp, themeState.dividerColor, RoundedCornerShape(16.dp))
                        .clickable { onShowSongInfoChange(!showSongInfo) }
                        .padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Show Song Info", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = themeState.onSurfaceColor)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Display current track name on main screen.", fontSize = 16.sp, color = themeState.onSurfaceVariant)
                    }
                    Switch(
                        checked = showSongInfo,
                        onCheckedChange = { onShowSongInfoChange(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = themeState.primaryColor, checkedThumbColor = themeState.onPrimaryColor)
                    )
                }
            }

            // Safety Controls
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("SAFETY CONTROLS", color = themeState.onSurfaceColor.copy(alpha = 0.6f), fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(themeState.surfaceColor, RoundedCornerShape(16.dp))
                        .border(1.dp, themeState.dividerColor, RoundedCornerShape(16.dp))
                        .clickable { 
                            val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && !notificationManager.isNotificationPolicyAccessGranted) {
                                android.widget.Toast.makeText(context, "Bitte erlaube 'DriveSafe' den Zugriff auf 'Nicht stören', um Benachrichtigungen stummzuschalten.", android.widget.Toast.LENGTH_LONG).show()
                                val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                                context.startActivity(intent)
                            } else {
                                onAutoMuteChange(!autoMute)
                            }
                        }
                        .padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-Mute Notifications", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = themeState.onSurfaceColor)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Requires Do Not Disturb permission.", fontSize = 16.sp, color = themeState.onSurfaceVariant)
                    }
                    Switch(
                        checked = autoMute,
                        onCheckedChange = { 
                            val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && !notificationManager.isNotificationPolicyAccessGranted) {
                                android.widget.Toast.makeText(context, "Bitte erlaube 'DriveSafe' den Zugriff auf 'Nicht stören', um Benachrichtigungen stummzuschalten.", android.widget.Toast.LENGTH_LONG).show()
                                val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                                context.startActivity(intent)
                            } else {
                                onAutoMuteChange(it)
                            }
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = themeState.primaryColor, checkedThumbColor = themeState.onPrimaryColor)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(themeState.surfaceColor, RoundedCornerShape(16.dp))
                        .border(1.dp, themeState.dividerColor, RoundedCornerShape(16.dp))
                        .clickable { onVoiceActivationChange(!voiceActivation) }
                        .padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Voice Activation", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = themeState.onSurfaceColor)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Enable 'Hey DriveSafe' wake word.", fontSize = 16.sp, color = themeState.onSurfaceVariant)
                    }
                    Switch(
                        checked = voiceActivation,
                        onCheckedChange = { onVoiceActivationChange(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = themeState.primaryColor, checkedThumbColor = themeState.onPrimaryColor)
                    )
                }
            }

            // Account
            if (isConnected) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ACCOUNT", color = themeState.onSurfaceColor.copy(alpha = 0.6f), fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Button(
                        onClick = onDisconnectClick,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("Disconnect from Spotify", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }

            // System Info Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .background(themeState.surfaceColor, RoundedCornerShape(16.dp))
                    .border(1.dp, themeState.dividerColor, RoundedCornerShape(16.dp))
                    .padding(24.dp),
                contentAlignment = Alignment.BottomStart
            ) {
                Column {
                    Text("SYSTEM CORE V4.2", color = themeState.primaryColor, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Optimized for Automotive Hardware", color = themeState.onSurfaceColor, fontSize = 16.sp)
                }
            }
        }
    }
}
