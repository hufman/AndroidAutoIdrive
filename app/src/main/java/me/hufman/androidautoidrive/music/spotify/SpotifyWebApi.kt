package me.hufman.androidautoidrive.music.spotify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.util.Log
import androidx.core.app.NotificationCompat
import com.adamratzman.spotify.*
import com.adamratzman.spotify.models.PlaylistUri
import com.adamratzman.spotify.models.SpotifyImage
import com.adamratzman.spotify.models.SpotifyUri
import com.adamratzman.spotify.models.Token
import kotlinx.coroutines.runBlocking
import me.hufman.androidautoidrive.AppSettings
import me.hufman.androidautoidrive.MutableAppSettings
import me.hufman.androidautoidrive.R
import me.hufman.androidautoidrive.music.MusicAppDiscovery
import me.hufman.androidautoidrive.music.MusicAppInfo
import me.hufman.androidautoidrive.music.MusicMetadata
import me.hufman.androidautoidrive.music.controllers.SpotifyAppController
import me.hufman.androidautoidrive.phoneui.SpotifyAuthorizationActivity
import net.openid.appauth.*
import java.lang.Exception
import java.util.*
import kotlin.collections.ArrayList

/**
 * Handles the logic around the Spotify Web API. This class is a singleton to prevent issues of having
 * multiple [SpotifyClientApi]s.
 */
class SpotifyWebApi private constructor(val context: Context, val appSettings: MutableAppSettings) {
	companion object {
		const val TAG = "SpotifyWebApi"
		const val NOTIFICATION_CHANNEL_ID = "SpotifyAuthorization"
		const val NOTIFICATION_REQ_ID = 56
		const val LIKED_SONGS_PLAYLIST_NAME = "ANDROIDAUTOIDRIVE_LIKED_SONGS"

		private var webApiInstance: SpotifyWebApi? = null

		/**
		 * Retrieves the current [SpotifyWebApi] instance creating one if it there are no instances
		 * currently running.
		 */
		fun getInstance(context: Context, appSettings: MutableAppSettings): SpotifyWebApi {
			if (webApiInstance == null) {
				webApiInstance = SpotifyWebApi(context, appSettings)
			}
			return webApiInstance as SpotifyWebApi
		}
	}

	// will be null if the user is not authenticated
	private var webApi: SpotifyClientApi? = null

	private val authStateManager: SpotifyAuthStateManager
	private val clientId: String
	private var getLikedSongsAttempted: Boolean = false
	private var spotifyAppControllerCaller: SpotifyAppController? = null
	var isUsingSpotify: Boolean = false

	init {
		Log.d(TAG, "Initializing for the first time")
		authStateManager = SpotifyAuthStateManager.getInstance(appSettings)
		clientId = SpotifyAppController.getClientId(context)
	}

	/**
	 * Sets the Liked Songs flag to false and sets the [SpotifyAppController] caller to null.
	 */
	fun clearGetLikedSongsAttemptedFlag() {
		getLikedSongsAttempted = false
		spotifyAppControllerCaller = null
	}

	/**
	 * Creates a private playlist with the provided name and optionally provided description. The
	 * newly created playlist's [PlaylistUri] is returned.
	 */
	suspend fun createPlaylist(playlistName: String, playlistDescription: String? = null): PlaylistUri? {
		try {
			return webApi?.playlists?.createClientPlaylist(name = playlistName, description = playlistDescription, public = false)?.uri
		} catch (e: SpotifyException.AuthenticationException) {
			Log.e(TAG, "Failed to create playlist $playlistName due to authentication error with the message: ${e.message}")
			authStateManager.addAccessTokenAuthorizationException(e)
			createNotAuthorizedNotification()
			webApi = null
		} catch (e: Exception) {
			Log.e(TAG, "Exception occurred when trying to create playlist $playlistName with the message: ${e.message}")
		}
		return null
	}

	/**
	 * Adds the provided list of songs to the specified playlist.
	 */
	suspend fun addSongsToPlaylist(playlistId: String, songs: List<MusicMetadata>) {
		try {
			webApi?.playlists?.addTracksToClientPlaylist(playlistId, *songs.map { it.mediaId!! }.toTypedArray())
		} catch (e: SpotifyException.AuthenticationException) {
			Log.e(TAG, "Failed to add songs to playlist $playlistId due to authentication error with the message: ${e.message}")
			authStateManager.addAccessTokenAuthorizationException(e)
			createNotAuthorizedNotification()
			webApi = null
		} catch (e: Exception) {
			Log.e(TAG, "Exception occurred when trying to add songs to playlist $playlistId with the message: ${e.message}")
		}
	}

	/**
	 * Replaces the songs of the specified playlist with the provided list of songs.
	 */
	suspend fun replacePlaylistSongs(playlistId: String, songs: List<MusicMetadata>) {
		try {
			webApi?.playlists?.replaceClientPlaylistTracks(playlistId, *songs.map { it.mediaId!! }.toTypedArray())
		} catch (e: SpotifyException.AuthenticationException) {
			Log.e(TAG, "Failed to replace playlist $playlistId songs due to authentication error with the message: ${e.message}")
			authStateManager.addAccessTokenAuthorizationException(e)
			createNotAuthorizedNotification()
			webApi = null
		} catch (e: Exception) {
			Log.e(TAG, "Exception occurred when trying to replace playlist $playlistId songs with the message: ${e.message}")
		}
	}

	/**
	 * Returns the [SpotifyUri] associated to the provided playlist name or null if it is not found.
	 */
	suspend fun getPlaylistUri(playlistName: String): SpotifyUri? {
		try {
			val playlists = webApi?.playlists?.getClientPlaylists(15,0)
			val matchingPlaylist = playlists?.items?.filter { it.name == playlistName }
			return if (matchingPlaylist.isNullOrEmpty()) {
				null
			} else {
				matchingPlaylist[0].uri
			}
		} catch (e:SpotifyException.AuthenticationException) {
			Log.e(TAG, "Failed to find playlist with name $playlistName due to authentication error with the message: ${e.message}")
			authStateManager.addAccessTokenAuthorizationException(e)
			createNotAuthorizedNotification()
			webApi = null
		} catch (e: Exception) {
			Log.e(TAG, "Exception occurred when trying to find playlist with name $playlistName with the message: ${e.message}")
		}
		return null
	}

	suspend fun getLikedSongs(spotifyAppController: SpotifyAppController): List<SpotifyMusicMetadata>?  {
		if (webApi == null) {
			getLikedSongsAttempted = true
			spotifyAppControllerCaller = spotifyAppController
			return emptyList()
		}
		try {
			val likedSongs = webApi?.library?.getSavedTracks(50)?.getAllItemsNotNull()
			return likedSongs?.map {
				val track = it.track
				val mediaId = track.uri.uri
				val artists = track.artists.map { it.name }.joinToString(", ")
				val album = track.album
				val coverArtUri = getCoverArtUri(album.images)
				SpotifyMusicMetadata(spotifyAppController, mediaId, mediaId.hashCode().toLong(), coverArtUri, artists, album.name, track.name)
			}
		} catch (e: SpotifyException.AuthenticationException) {
			Log.e(TAG, "Failed to get data from Liked Songs library due to authentication error with the message: ${e.message}")
			authStateManager.addAccessTokenAuthorizationException(e)
			createNotAuthorizedNotification()
			webApi = null
		} catch (e: Exception) {
			Log.e(TAG, "Exception occurred while getting Liked Songs library data with message: ${e.message}")
		}
		return null
	}

	suspend fun searchForQuery(spotifyAppController: SpotifyAppController, query: String): List<SpotifyMusicMetadata> {
		if (webApi == null) {
			return emptyList()
		}
		try {
			val searchResults = webApi?.search?.searchAllTypes(query, 8)

			// run through each of the search result categories and compile full list
			val searchResultMusicMetadata: ArrayList<SpotifyMusicMetadata> = ArrayList()

			val albumResults = searchResults?.albums
			if (albumResults != null && albumResults.isNotEmpty()) {
				searchResultMusicMetadata.addAll(albumResults.items.map {
					val mediaId = it.uri.uri
					val coverArtUri = getCoverArtUri(it.images)
					val artists = it.artists.map { it.name }.joinToString(", ")
					val type = it.type.capitalize(Locale.getDefault())

					SpotifyMusicMetadata(spotifyAppController, mediaId, mediaId.hashCode().toLong(), coverArtUri, artists, it.name, it.name, type, true, false)
				})
			}

			val songResults = searchResults?.tracks
			if (songResults != null && songResults.isNotEmpty()) {
				searchResultMusicMetadata.addAll(songResults.items.map {
					val mediaId = it.uri.uri
					val coverArtUri = getCoverArtUri(it.album.images)
					val artists = it.artists.map { it.name }.joinToString(", ")
					val albumMediaId = it.album.uri.uri
					val type = it.type.capitalize(Locale.getDefault())

					SpotifyMusicMetadata(spotifyAppController, mediaId, mediaId.hashCode().toLong(), coverArtUri, artists, albumMediaId, it.name, type, true, false)
				})
			}

			val artistResults = searchResults?.artists
			if (artistResults != null && artistResults.isNotEmpty()) {
				searchResultMusicMetadata.addAll(artistResults.items.map {
					val mediaId = it.uri.uri
					val coverArtUri = getCoverArtUri(it.images, 320)
					val type = it.type.capitalize(Locale.getDefault())

					SpotifyMusicMetadata(spotifyAppController, mediaId, mediaId.hashCode().toLong(), coverArtUri, it.name, null, it.name, type, false, true)
				})
			}

			val playlistResults = searchResults?.playlists
			if (playlistResults != null && playlistResults.isNotEmpty()) {
				searchResultMusicMetadata.addAll(playlistResults.items.map {
					val mediaId = it.uri.uri
					val coverArtUri = getCoverArtUri(it.images, 320)
					val type = it.type.capitalize(Locale.getDefault())

					SpotifyMusicMetadata(spotifyAppController, mediaId, mediaId.hashCode().toLong(), coverArtUri, it.name, null, it.name, type, false, true)
				})
			}

			val showResults = searchResults?.shows
			if (showResults != null && showResults.isNotEmpty()) {
				searchResultMusicMetadata.addAll(showResults.items.filterNotNull().map {
					val mediaId = it.uri.uri
					val coverArtUri = getCoverArtUri(it.images)
					val subtitle = it.type.capitalize(Locale.getDefault())

					SpotifyMusicMetadata(spotifyAppController, mediaId, mediaId.hashCode().toLong(), coverArtUri, it.publisher, null, it.name, subtitle, false, true)
				})
			}

			val episodeResults = searchResults?.episodes
			if (episodeResults != null && episodeResults.isNotEmpty()) {
				searchResultMusicMetadata.addAll(episodeResults.items.filterNotNull().map {
					val mediaId = it.uri.uri
					val coverArtUri = getCoverArtUri(it.images)
					val subtitle = it.type.capitalize(Locale.getDefault())

					SpotifyMusicMetadata(spotifyAppController, mediaId, mediaId.hashCode().toLong(), coverArtUri, null, null, it.name, subtitle, true, false)
				})
			}
			return searchResultMusicMetadata
		} catch (e: SpotifyException.AuthenticationException) {
			Log.e(TAG, "Failed to get search results due to authentication error with the message: ${e.message}")
			authStateManager.addAccessTokenAuthorizationException(e)
			createNotAuthorizedNotification()
			webApi = null
		} catch (e: Exception) {
			Log.e(TAG, "Exception occurred while attempting to get search results with the message: ${e.message}")
		}
		return emptyList()
	}

	/**
	 * Initializes the [SpotifyClientApi] instance and updates the [AuthState] with the token used.
	 */
	fun initializeWebApi(isProbing: Boolean = false) {
		if(webApi != null) {
			return
		}
		webApi = createWebApiClient()
		if (webApi != null) {
			authStateManager.updateTokenResponseWithToken(webApi!!.token, clientId)

			if (getLikedSongsAttempted) {
				getLikedSongsAttempted = false
				spotifyAppControllerCaller?.createLikedSongsQueueMetadata()
			}
			if (!isProbing) {
				updateSpotifyAppInfoAsSearchable()
			}
		}
	}

	/**
	 * Returns if the current [AuthState] is authorized.
	 */
	fun isAuthorized(): Boolean {
		return authStateManager.isAuthorized()
	}

	/**
	 * Updates the Spotify [MusicAppInfo] searchable flag to true if it is set to false.
	 */
	private fun updateSpotifyAppInfoAsSearchable() {
		val musicAppDiscovery = MusicAppDiscovery(context, Handler())
		musicAppDiscovery.loadInstalledMusicApps()
		val spotifyAppInfo = musicAppDiscovery.allApps.firstOrNull { it.packageName == "com.spotify.music" }

		// if app discovery says we aren't able to search, discover again
		if (spotifyAppInfo?.searchable == false) {
			musicAppDiscovery.probeApp(spotifyAppInfo)
		}
	}

	/**
	 * Creates the Web API client using the PKCE authentication flow. If the [SpotifyClientApi] creation
	 * and validation process encounters a authentication failure, a null instance is returned and the
	 * [AuthState] updated with the exception.
	 */
	private fun createWebApiClient(): SpotifyClientApi? {
		val accessToken = authStateManager.getAccessToken()
		if (accessToken != null) {
			return try {
				val expirationIn = authStateManager.getAccessTokenExpirationIn()
				val refreshToken = authStateManager.getRefreshToken()
				val scopes = authStateManager.getScopeString()
				val token = Token(accessToken, "Bearer", expirationIn.toInt(), refreshToken, scopes)
				val apiBuilder = SpotifyClientApiBuilderHelper.createApiBuilderWithAccessToken(clientId, token)
				apiBuilder.options.onTokenRefresh = {
					Log.d(TAG, "Updating AuthState with refreshed token")
					authStateManager.updateTokenResponseWithToken(it.token, clientId)
				}
				runBlocking {
					apiBuilder.build()
				}
			} catch (e: SpotifyException.AuthenticationException) {
				Log.e(SpotifyAppController.TAG, "Failed to create the web API with an access token. Access token is invalid")
				authStateManager.addAccessTokenAuthorizationException(e)
				createNotAuthorizedNotification()
				null
			} catch (e: Exception) {
				Log.e(SpotifyAppController.TAG, "Failed to create the web API due to the error: ${e.message}")
				null
			}
		} else {
			val authorizationCode = authStateManager.getAuthorizationCode()
			if (authorizationCode == null) {
				Log.e(SpotifyAppController.TAG, "Failed to create the Web API with an authorization code. Authorization code is invalid or not found")
				createNotAuthorizedNotification()
				return null
			}

			return try {
				val apiBuilder = SpotifyClientApiBuilderHelper.createApiBuilderWithAuthorizationCode(clientId, authorizationCode)
				apiBuilder.options.onTokenRefresh = {
					Log.d(TAG, "Updating AuthState with refreshed token")
					authStateManager.updateTokenResponseWithToken(it.token, clientId)
				}
				runBlocking {
					apiBuilder.build()
				}
			} catch (e: SpotifyException.AuthenticationException) {
				authStateManager.addAuthorizationCodeAuthorizationException(e)
				Log.e(SpotifyAppController.TAG, "Failed to create the web API with an authorization code. Authorization failed with the error: ${e.message}")
				createNotAuthorizedNotification()
				null
			} catch (e: Exception) {
				Log.e(SpotifyAppController.TAG, "Failed to create the web API due to the error: ${e.message}")
				null
			}
		}
	}

	/**
	 * Creates and displays a notification stating that the web API needs to be authorized only if the
	 * user has previously successfully authorized.
	 */
	private fun createNotAuthorizedNotification() {
		if (appSettings[AppSettings.KEYS.SPOTIFY_SHOW_UNAUTHENTICATED_NOTIFICATION] == "false") {
			return
		}
		val notifyIntent = Intent(context, SpotifyAuthorizationActivity::class.java)
		notifyIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
		val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
				.setContentTitle(context.getString(R.string.notification_title))
				.setContentText(context.getString(R.string.txt_spotify_auth_notification))
				.setSmallIcon(R.drawable.ic_notify)
				.setContentIntent(PendingIntent.getActivity(context, NOTIFICATION_REQ_ID, notifyIntent, PendingIntent.FLAG_UPDATE_CURRENT))
				.build()
		val notificationManager = context.getSystemService(NotificationManager::class.java)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID,
					context.getString(R.string.notification_channel_spotify),
					NotificationManager.IMPORTANCE_HIGH)
			notificationManager.createNotificationChannel(channel)
		}
		notificationManager.notify(NOTIFICATION_REQ_ID, notification)
	}

	/**
	 * Constructs the cover art uri from the list of [SpotifyImage]s that works with the Spotify App
	 * Remote ImagesApi. If there are no images that are found that match the dimensions then null is
	 * returned.
	 */
	private fun getCoverArtUri(images: List<SpotifyImage>, heightToMatch: Int = 300): String? {
		val coverArtIndex = images.indexOfFirst { it.height == heightToMatch }
		return if (coverArtIndex != -1) {
			val imageUrl = images[coverArtIndex].url
			val coverArtCode = imageUrl.substring(imageUrl.lastIndexOf("/")+1)
			"spotify:image:$coverArtCode"
		} else {
			null
		}
	}

	/**
	 * Disconnect process for shutting down the [SpotifyClientApi].
	 */
	fun disconnect() {
		Log.d(TAG, "Disconnecting Web API")
		webApi?.shutdown()
		isUsingSpotify = false
		clearGetLikedSongsAttemptedFlag()
	}
}