package com.example.spotifyvoice

import android.content.Context
import android.util.Log

// NOTE: You will need to add the spotify-app-remote-release-xxx.aar to your libs/ folder
// and configure it in build.gradle for these imports to work.
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SpotifyController(private val context: Context, private val clientId: String, private val redirectUri: String) {

    private val TAG = "SpotifyController"
    private var spotifyAppRemote: SpotifyAppRemote? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentSongInfo = MutableStateFlow<String?>(null)
    val currentSongInfo: StateFlow<String?> = _currentSongInfo.asStateFlow()

    fun connect(onConnected: () -> Unit) {
        val connectionParams = ConnectionParams.Builder(clientId)
            .setRedirectUri(redirectUri)
            .showAuthView(true)
            .build()

        SpotifyAppRemote.connect(context, connectionParams, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                spotifyAppRemote = appRemote
                Log.d(TAG, "Connected to Spotify!")
                
                appRemote.playerApi.subscribeToPlayerState().setEventCallback { playerState ->
                    _isPlaying.value = !playerState.isPaused
                    val track = playerState.track
                    if (track != null) {
                        _currentSongInfo.value = "${track.artist.name} - ${track.name}"
                    } else {
                        _currentSongInfo.value = null
                    }
                }

                onConnected()
            }

            override fun onFailure(throwable: Throwable) {
                Log.e(TAG, "Failed to connect to Spotify", throwable)
            }
        })
        Log.d(TAG, "Connect placeholder called. SDK not yet included.")
    }

    fun play(uri: String) {
        spotifyAppRemote?.let {
            it.playerApi.play(uri)
        }
    }

    fun queue(uri: String) {
        spotifyAppRemote?.let {
            it.playerApi.queue(uri)
        }
    }

    fun pause() {
        spotifyAppRemote?.let {
            it.playerApi.pause()
        }
    }

    fun resume() {
        spotifyAppRemote?.let {
            it.playerApi.resume()
        }
    }

    fun next() {
        spotifyAppRemote?.let {
            it.playerApi.skipNext()
        }
    }

    fun previous() {
        spotifyAppRemote?.let { remote ->
            remote.playerApi.seekTo(0).setResultCallback {
                remote.playerApi.skipPrevious()
            }
        }
    }
    
    fun restartTrack() {
        spotifyAppRemote?.let {
            it.playerApi.seekTo(0)
        }
    }

    fun setShuffle(shuffle: Boolean) {
        spotifyAppRemote?.let {
            it.playerApi.setShuffle(shuffle)
        }
    }

    fun setRepeat(repeatMode: Int) {
        spotifyAppRemote?.let {
            it.playerApi.setRepeat(repeatMode)
        }
    }

    fun seekRelative(offsetMs: Long) {
        spotifyAppRemote?.let { remote ->
            remote.playerApi.playerState.setResultCallback { playerState ->
                val newPosition = (playerState.playbackPosition + offsetMs).coerceAtLeast(0L)
                remote.playerApi.seekTo(newPosition)
            }
        }
    }

    fun getCurrentTrackUri(callback: (String?) -> Unit) {
        spotifyAppRemote?.let { remote ->
            remote.playerApi.playerState.setResultCallback { playerState ->
                callback(playerState.track?.uri)
            }.setErrorCallback {
                callback(null)
            }
        } ?: callback(null)
    }

    fun disconnect() {
        SpotifyAppRemote.disconnect(spotifyAppRemote)
    }
}
