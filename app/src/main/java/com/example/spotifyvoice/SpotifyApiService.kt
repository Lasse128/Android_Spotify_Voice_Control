package com.example.spotifyvoice

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

data class SearchResponse(
    val tracks: Tracks?,
    val artists: Artists?
)

data class Artists(
    val items: List<ArtistItem>
)

data class ArtistItem(
    val id: String,
    val name: String,
    val uri: String
)

data class Tracks(
    val items: List<TrackItem>
)

data class TrackItem(
    val id: String,
    val name: String,
    val uri: String
)

data class PlaylistsResponse(
    val items: List<PlaylistItem>
)

data class PlaylistItem(
    val id: String,
    val name: String,
    val uri: String,
    val owner: PlaylistOwner
)

data class PlaylistOwner(
    val id: String
)

data class UserProfile(
    val id: String
)

data class SnapshotResponse(
    val snapshot_id: String
)

data class AddTrackRequest(
    @com.google.gson.annotations.SerializedName("uris")
    val uris: List<String>
)

data class CurrentlyPlayingResponse(
    val item: TrackItem?
)

interface SpotifyApiService {
    @GET("v1/search")
    suspend fun search(
        @Header("Authorization") token: String,
        @Query("q") query: String,
        @Query("type") type: String = "track,artist",
        @Query("limit") limit: Int = 1
    ): SearchResponse

    @GET("v1/me")
    suspend fun getMe(
        @Header("Authorization") token: String
    ): UserProfile

    @GET("v1/me/player/currently-playing")
    suspend fun getCurrentlyPlaying(
        @Header("Authorization") token: String
    ): CurrentlyPlayingResponse?

    @GET("v1/me/playlists")
    suspend fun getMyPlaylists(
        @Header("Authorization") token: String,
        @Query("limit") limit: Int = 50
    ): PlaylistsResponse

    @retrofit2.http.Headers("Content-Type: application/json")
    @retrofit2.http.POST("v1/playlists/{playlist_id}/items")
    suspend fun addTrackToPlaylist(
        @Header("Authorization") token: String,
        @retrofit2.http.Path("playlist_id") playlistId: String,
        @retrofit2.http.Body request: AddTrackRequest
    ): retrofit2.Response<SnapshotResponse>

    companion object {
        private const val BASE_URL = "https://api.spotify.com/"

        fun create(): SpotifyApiService {
            val retrofit = Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            return retrofit.create(SpotifyApiService::class.java)
        }
    }
}

data class TokenResponse(
    val access_token: String,
    val refresh_token: String?,
    val expires_in: Int
)

interface SpotifyAuthService {
    @retrofit2.http.FormUrlEncoded
    @retrofit2.http.POST("api/token")
    suspend fun getToken(
        @retrofit2.http.Field("client_id") clientId: String,
        @retrofit2.http.Field("grant_type") grantType: String,
        @retrofit2.http.Field("code") code: String,
        @retrofit2.http.Field("redirect_uri") redirectUri: String,
        @retrofit2.http.Field("code_verifier") codeVerifier: String
    ): TokenResponse
    @retrofit2.http.FormUrlEncoded
    @retrofit2.http.POST("api/token")
    suspend fun refreshToken(
        @retrofit2.http.Field("client_id") clientId: String,
        @retrofit2.http.Field("grant_type") grantType: String = "refresh_token",
        @retrofit2.http.Field("refresh_token") refreshToken: String
    ): TokenResponse

    companion object {
        fun create(): SpotifyAuthService {
            val retrofit = Retrofit.Builder()
                .baseUrl("https://accounts.spotify.com/")
                .addConverterFactory(GsonConverterFactory.create())
                .build()
            return retrofit.create(SpotifyAuthService::class.java)
        }
    }
}
