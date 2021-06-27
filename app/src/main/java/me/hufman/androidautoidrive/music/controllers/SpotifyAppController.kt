package me.hufman.androidautoidrive.music.controllers

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Handler
import android.util.Log
import android.util.LruCache
import com.google.gson.Gson
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.*
import kotlinx.coroutines.*
import me.hufman.androidautoidrive.*
import me.hufman.androidautoidrive.Observable
import me.hufman.androidautoidrive.music.*
import me.hufman.androidautoidrive.music.PlaybackPosition
import me.hufman.androidautoidrive.music.spotify.LikedSongsState
import me.hufman.androidautoidrive.music.spotify.SpotifyWebApi
import me.hufman.androidautoidrive.music.spotify.SpotifyMusicMetadata
import java.util.*

class SpotifyAppController(context: Context, val remote: SpotifyAppRemote, val webApi: SpotifyWebApi, val appSettings: MutableAppSettings): MusicAppController {
	companion object {
		const val TAG = "SpotifyAppController"
		const val REDIRECT_URI = "me.hufman.androidautoidrive://spotify_callback"

		fun getClientId(context: Context): String {
			return context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
					.metaData.getString("com.spotify.music.API_KEY", "unavailable")
		}

		fun hasSupport(context: Context): Boolean {
			val clientId = getClientId(context)
			return clientId != "unavailable" && clientId != ""
		}

		fun isSpotifyInstalled(context: Context): Boolean {
			return try {
				context.packageManager.getPackageInfo("com.spotify.music", 0)
				true
			} catch (e: PackageManager.NameNotFoundException) { false }
		}

		fun MusicMetadata.Companion.fromSpotify(track: Track, coverArt: Bitmap? = null): MusicMetadata {
			// general track metadata
			return MusicMetadata(mediaId = track.uri, queueId = track.uri.hashCode().toLong(),
					title = track.name, album = track.album.name, artist = track.artist.name, coverArt = coverArt)
		}

		fun MusicMetadata.toListItem(): ListItem {
			return ListItem(this.mediaId, this.mediaId, null, this.title, this.subtitle,
					this.playable, this.browseable)
		}

		fun CustomAction.Companion.fromSpotify(name: String, icon: Drawable? = null): CustomAction {
			return formatCustomActionDisplay(CustomAction("com.spotify.music", name, name, icon, null))
		}
	}

	class Connector(val context: Context, val prompt: Boolean = true, val isProbing: Boolean = false): MusicAppController.Connector {
		var lastError: Throwable? = null

		fun hasSupport(): Boolean {
			return SpotifyAppController.hasSupport(context)
		}
		fun isSpotifyInstalled(): Boolean {
			return SpotifyAppController.isSpotifyInstalled(context)
		}
		fun previousControlSuccess(): Boolean {
			return AppSettings[AppSettings.KEYS.SPOTIFY_CONTROL_SUCCESS].toBoolean()
		}

		override fun connect(appInfo: MusicAppInfo): Observable<SpotifyAppController> {
			if (appInfo.packageName != "com.spotify.music") {
				val pendingController = MutableObservable<SpotifyAppController>()
				pendingController.value = null
				return pendingController
			}
			return connect()
		}

		fun connect(): Observable<SpotifyAppController> {
			Log.i(TAG, "Attempting to connect to Spotify Remote")
			val params = ConnectionParams.Builder(getClientId(context))
					.setRedirectUri(REDIRECT_URI)
					.showAuthView(prompt)
					.build()

			val pendingController = MutableObservable<SpotifyAppController>()
			val remoteListener = object: com.spotify.android.appremote.api.Connector.ConnectionListener {
				override fun onFailure(e: Throwable?) {
					Log.e(TAG, "Failed to connect to Spotify Remote: $e")
					if (hasSupport(context)) {
						// show an error to the UI, unless we don't have an API key
						this@Connector.lastError = e
					}
					// remember that we failed to connect
					if (pendingController.value == null) {
						MutableAppSettingsReceiver(context)[AppSettings.KEYS.SPOTIFY_CONTROL_SUCCESS] = "false"
					}
					// disconnect an existing session, if any
					pendingController.value?.disconnect()
					pendingController.value = null
				}

				override fun onConnected(remote: SpotifyAppRemote?) {
					if (remote != null) {
						Log.i(TAG, "Successfully connected to Spotify Remote")
						this@Connector.lastError = null

						val appSettings = MutableAppSettingsReceiver(context)
						appSettings[AppSettings.KEYS.SPOTIFY_CONTROL_SUCCESS] = "true"

						val spotifyWebApi = SpotifyWebApi.getInstance(context, appSettings)
						spotifyWebApi.initializeWebApi(isProbing)
						spotifyWebApi.isUsingSpotify = true

						pendingController.value = SpotifyAppController(context, remote, spotifyWebApi, appSettings)

						// if app discovery says we aren't able to connect, discover again
						if (!isProbing) {
							val musicAppDiscovery = MusicAppDiscovery(context, Handler())
							musicAppDiscovery.loadInstalledMusicApps()
							val spotifyAppInfo = musicAppDiscovery.allApps.firstOrNull { it.packageName == "com.spotify.music" }
							if (spotifyAppInfo?.connectable == false) {
								musicAppDiscovery.probeApp(spotifyAppInfo)
							}
						}
					} else {
						Log.e(TAG, "Connected to a null Spotify Remote?")
						pendingController.value = null
					}
				}
			}

			SpotifyAppRemote.connect(context, params, remoteListener)
			return pendingController
		}
	}

	// create the Custom Actions during client creation, so that the language is loaded
	val CUSTOM_ACTION_TURN_REPEAT_ALL_ON = CustomAction.fromSpotify("TURN_REPEAT_ALL_ON",
			context.getDrawable(R.drawable.spotify_repeat_off))
	val CUSTOM_ACTION_TURN_REPEAT_ONE_ON = CustomAction.fromSpotify("TURN_REPEAT_ONE_ON",
			context.getDrawable(R.drawable.spotify_repeat_on))
	val CUSTOM_ACTION_TURN_REPEAT_ONE_OFF = CustomAction.fromSpotify("TURN_REPEAT_ONE_OFF",
			context.getDrawable(R.drawable.spotify_repeat_one))
	val CUSTOM_ACTION_ADD_TO_COLLECTION = CustomAction.fromSpotify("ADD_TO_COLLECTION",
			context.getDrawable(R.drawable.spotify_add_library))
	val CUSTOM_ACTION_REMOVE_FROM_COLLECTION = CustomAction.fromSpotify("REMOVE_FROM_COLLECTION",
			context.getDrawable(R.drawable.spotify_added_library))
	val CUSTOM_ACTION_START_RADIO = CustomAction.fromSpotify("START_RADIO",
			context.getDrawable(R.drawable.spotify_start_radio))

	var connected = true

	// Spotify is very asynchronous, save any subscription state for the getters
	var callback: ((MusicAppController) -> Unit)? = null    // UI listener
	val spotifySubscription: Subscription<PlayerState> = remote.playerApi.subscribeToPlayerState()
	val playlistSubscription: Subscription<PlayerContext> = remote.playerApi.subscribeToPlayerContext()
	var playerActions: PlayerRestrictions? = null
	var playerOptions: PlayerOptions? = null
	var currentTrack: MusicMetadata? = null
	var currentSongCoverArtCache = LruCache<ImageUri, Bitmap>(4)
	var position: PlaybackPosition = PlaybackPosition(true, false, 0, 0, -1)
	var currentTrackLibrary: Boolean? = null
	var queueUri: String? = null
	var queueItems: List<MusicMetadata> = LinkedList()
	var queueMetadata: QueueMetadata? = null
	val coverArtCache = LruCache<ImageUri, Bitmap>(50)
	var createQueueMetadataJob: Job? = null
	var defaultDispatcher = Dispatchers.Default
	var onQueueLoaded: (() -> Unit)? = null
	val gson: Gson = Gson()
	var queueTitle: String? = null

	init {
		spotifySubscription.setEventCallback { playerState ->
			Log.d(TAG, "Heard an update from Spotify")

			// update the available actions
			playerActions = playerState.playbackRestrictions
			playerOptions = playerState.playbackOptions

			// update the current track info
			val track = playerState.track
			if (track != null) {
				val cachedCoverArt = currentSongCoverArtCache[track.imageUri]
				currentTrack = MusicMetadata.fromSpotify(track, coverArt = cachedCoverArt)
				val loadingTrack = currentTrack
				if (cachedCoverArt == null) {
					// try to load the coverart
					val coverArtLoader = remote.imagesApi.getImage(track.imageUri)
					coverArtLoader.setResultCallback { coverArt ->
						currentSongCoverArtCache.put(track.imageUri, coverArt)
						if (loadingTrack?.mediaId == currentTrack?.mediaId) {   // still playing the same song
							currentTrack = MusicMetadata.fromSpotify(track, coverArt = coverArt)
							callback?.invoke(this)
						}
					}
				}
				currentTrackLibrary = null
				remote.userApi.getLibraryState(track.uri).setResultCallback {
					currentTrackLibrary = it.isAdded
				}
			} else {
				currentTrack = null
			}

			// update a progress bar
			position = PlaybackPosition(playerState.isPaused, false, lastPosition = playerState.playbackPosition, maximumPosition = playerState.track.duration)

			callback?.invoke(this)
		}

		playlistSubscription.setEventCallback { playerContext ->
			Log.d(TAG, "Heard an update from Spotify queue: ${playerContext.uri}")
			// update the current queue
			val uri = playerContext.uri
			if (queueUri != uri) {
				queueUri = uri
				queueItems = emptyList()
				queueTitle = playerContext.title

				val isLikedSongsPlaylist = playerContext.type == "your_library" || playerContext.type == "your_library_tracks"
				if (isLikedSongsPlaylist || playerContext.title == SpotifyWebApi.LIKED_SONGS_PLAYLIST_NAME) {
					createLikedSongsQueueMetadata()
				} else {
					if (createQueueMetadataJob?.isActive == true) {
						createQueueMetadataJob?.cancel()
					}
					createQueueMetadataJob = null
					webApi.clearGetLikedSongsAttemptedFlag()
					createQueueMetadata(playerContext)
				}
			}

			callback?.invoke(this)
		}
	}

	/**
	 * Creates the [QueueMetadata] for the "Liked Songs" playlist using the Web API. If the web API is
	 * not authorized then the the [QueueMetadata] is created from the app remote API.
	 */
	fun createLikedSongsQueueMetadata() {
		if (createQueueMetadataJob?.isActive == true) {
			createQueueMetadataJob?.cancel()
		}

		createQueueMetadataJob = GlobalScope.launch(defaultDispatcher) {
			queueItems = webApi.getLikedSongs(this@SpotifyAppController) ?: emptyList()

			if (queueItems.isNotEmpty()) {
				queueMetadata = QueueMetadata(queueTitle, null, queueItems)

				val hashCode = queueItems.hashCode().toString()
				val likedSongsStateJson = appSettings[AppSettings.KEYS.SPOTIFY_LIKED_SONGS_PLAYLIST_STATE]
				val likedSongsState: LikedSongsState
				if (likedSongsStateJson.isBlank()) {
					Log.d(TAG, "No previous liked songs state found, checking user playlists.")
					val existingPlaylistUri = webApi.getPlaylistUri(SpotifyWebApi.LIKED_SONGS_PLAYLIST_NAME)
					val playlistUri: String
					val playlistId: String
					if (existingPlaylistUri == null) {
						Log.d(TAG, "No user playlist for ${SpotifyWebApi.LIKED_SONGS_PLAYLIST_NAME} found, creating a new one.")
						val uri = webApi.createPlaylist(SpotifyWebApi.LIKED_SONGS_PLAYLIST_NAME, L.MUSIC_TEMPORARY_PLAYLIST_DESCRIPTION)
						if (uri == null) {
							Log.e(TAG, "Error creating liked songs playlist, falling back to app remote API")
							queueItems = emptyList()
							createQueueMetadata(PlayerContext(queueUri, queueTitle, null, null))
							return@launch
						}
						webApi.addSongsToPlaylist(uri.id, queueItems)
						playlistUri = uri.uri
						playlistId = uri.id
					} else {
						Log.d(TAG, "User playlist for ${SpotifyWebApi.LIKED_SONGS_PLAYLIST_NAME} found, using existing playlist.")
						playlistUri = existingPlaylistUri.uri
						playlistId = existingPlaylistUri.id
					}
					likedSongsState = LikedSongsState(hashCode, playlistUri, playlistId, null)
					saveLikedSongsState(likedSongsState)
				} else {
					Log.d(TAG, "Found previous liked songs state.")
					likedSongsState = gson.fromJson(likedSongsStateJson, LikedSongsState::class.java)

					if (hashCode != likedSongsState.hashCode) {
						Log.d(TAG, "Previous liked songs state is no longer valid, updating liked songs playlist and writing new hash.")
						webApi.replacePlaylistSongs(likedSongsState.playlistId, queueItems)
						likedSongsState.hashCode = hashCode
						saveLikedSongsState(likedSongsState)
					}
				}

				queueUri = likedSongsState.playlistUri

				if (likedSongsState.queueCoverArtUri == null) {
					Log.d(TAG, "No previously stored queue cover art uri found.")
					val recentlyPlayedUri = "com.spotify.recently-played"
					val li = ListItem(recentlyPlayedUri, recentlyPlayedUri, null, null, null, false, true)
					remote.contentApi.getChildrenOfItem(li, 1, 0).setResultCallback { recentlyPlayed ->
						val item = recentlyPlayed?.items?.get(0)
						if (item != null) {
							likedSongsState.queueCoverArtUri = item.imageUri.raw
							remote.imagesApi.getImage(item.imageUri, Image.Dimension.THUMBNAIL).setResultCallback { coverArt ->
								queueMetadata?.coverArt = coverArt
							}
							saveLikedSongsState(likedSongsState)
						}
					}
				} else {
					Log.d(TAG, "Found previously stored queue cover art uri.")
					remote.imagesApi.getImage(ImageUri(likedSongsState.queueCoverArtUri), Image.Dimension.THUMBNAIL).setResultCallback { coverArt ->
						queueMetadata?.coverArt = coverArt
					}
				}

				callback?.invoke(this@SpotifyAppController)
			} else {
				createQueueMetadata(PlayerContext(queueUri, queueTitle, null, null))
			}
		}
	}

	/**
	 * Saves the provided [LikedSongsState] to the [AppSettings].
	 */
	private fun saveLikedSongsState(likedSongsState: LikedSongsState) {
		appSettings[AppSettings.KEYS.SPOTIFY_LIKED_SONGS_PLAYLIST_STATE] = gson.toJson(likedSongsState)
	}

	/**
	 * Retrieves the cover art Bitmap for the provided ImageUri. If it is present in the coverArtCache
	 * then the Bitmap is returned otherwise the imagesApi is called to asynchronously add the cover
	 * art Bitmap to the cache and returns null in the meantime.
	 */
	fun getCoverArt(imageUri: ImageUri): Bitmap? {
		val coverArt = coverArtCache.get(imageUri)
		if (coverArt != null) {
			return coverArt
		} else {
			remote.imagesApi.getImage(imageUri, Image.Dimension.THUMBNAIL).setResultCallback { coverArtResponse ->
				coverArtCache.put(imageUri, coverArtResponse)
			}
		}
		return null
	}

	/**
	 * Creates the [QueueMetadata] for the current player context using the app remote API.
	 */
	private fun createQueueMetadata(playerContext: PlayerContext) {
		if (queueUri != null && queueItems.isEmpty()) {
			val listItem = ListItem(queueUri, queueUri, null, playerContext.title, playerContext.subtitle, false, true)
			loadPaginatedItems(listItem, { queueUri == playerContext.uri }) {
				queueItems = removeShufflePlayButtonMetadata(it)

				// builds the QueueMetadata while waiting for cover art to load
				queueMetadata = QueueMetadata(playerContext.title, playerContext.subtitle, queueItems, mediaId = playerContext.uri)
				loadQueueCoverart()

				onQueueLoaded?.invoke()
				callback?.invoke(this)
			}
		}
	}

	/**
	 * Updates the [QueueMetadata] with the cover art of the current queue.
	 */
	private fun loadQueueCoverart() {
		val recentlyPlayedUri = "com.spotify.recently-played"
		val li = ListItem(recentlyPlayedUri, recentlyPlayedUri, null, null, null, false, true)
		remote.contentApi.getChildrenOfItem(li, 1, 0).setResultCallback { recentlyPlayed ->
			val item = recentlyPlayed?.items?.get(0)
			if (item != null) {
				remote.imagesApi.getImage(item.imageUri, Image.Dimension.THUMBNAIL).setResultCallback { coverArt ->
					queueMetadata?.coverArt = coverArt
				}
			}
		}
	}

	/**
	 * Loads the complete children contents of the given parentItem
	 * checking if stillValid at each step
	 * and then passing it to onComplete at the end
	 */
	private fun loadPaginatedItems(parentItem: ListItem,
	                               stillValid: () -> Boolean, onComplete: (List<MusicMetadata>) -> Unit) {
		loadPaginatedItems(LinkedList(), parentItem, stillValid, onComplete)
	}

	/**
	 * Loads the complete children contents of the given parentItem
	 * into the destination list
	 * checking if stillValid at each step
	 * and passing the destination list to onComplete at the end
	 */
	private fun loadPaginatedItems(destination: MutableList<MusicMetadata>, parentItem: ListItem,
	                               stillValid: () -> Boolean, onComplete: (List<MusicMetadata>) -> Unit) {
		remote.contentApi.getChildrenOfItem(parentItem, 200, destination.size).setResultCallback { items ->
			if (items != null && stillValid()) {    // this is still a valid request
				destination.addAll(items.items.filterNotNull().map { listItem: ListItem ->
					SpotifyMusicMetadata.fromSpotifyQueueListItem(this, listItem)
				})
				if (destination.size < items.total && items.items.isNotEmpty()) {   // more tracks to load
					loadPaginatedItems(destination, parentItem, stillValid, onComplete)
				} else {
					onComplete(destination)
				}
			}
		}.setErrorCallback {
			Log.w(TAG, "Unable to fetch Spotify content", it)
			onComplete(destination)
		}
	}

	override fun play() {
		remote.connectApi.connectSwitchToLocalDevice()
		remote.playerApi.resume()
	}

	override fun pause() {
		remote.playerApi.pause()
	}

	override fun skipToPrevious() {
		remote.playerApi.skipPrevious()
	}

	override fun skipToNext() {
		remote.playerApi.skipNext()
	}

	override fun seekTo(newPos: Long) {
		remote.playerApi.seekTo(newPos)
	}

	override fun playSong(song: MusicMetadata) {
		// if the item is a track then load the album context for it so the rest of the album is queued up
		if (song.subtitle == "Track") {
			// album queue loaded is not the correct context for the song, will need to load the correct album into the queue
			if (queueMetadata?.mediaId != song.album) {
				remote.playerApi.play(song.album)

				// queue is loaded async but is needed before playing the song from the queue
				onQueueLoaded = {
					playQueue(song)
				}
			} else {
				playQueue(song)
			}
		} else {
			remote.playerApi.play(song.mediaId)
		}
	}

	override fun playQueue(song: MusicMetadata) {
		// queue item equality is based on queueId, which we are storing as the uri hashCode
		// we don't know the index to jump to
		// so, we have to iterate through the queue to find the user's selected queueId
		val queueUri = this.queueUri
		if (song.queueId != null) {
			queueItems.forEachIndexed { index, it ->
				if (it.queueId == song.queueId) {
					remote.playerApi.skipToIndex(queueUri, index)
					return
				}
			}
		}
	}

	override fun playFromSearch(search: String) {
	}

	override fun customAction(action: CustomAction) {
		when (action) {
			CUSTOM_ACTION_TURN_REPEAT_ALL_ON -> remote.playerApi.setRepeat(Repeat.ALL)
			CUSTOM_ACTION_TURN_REPEAT_ONE_ON -> remote.playerApi.setRepeat(Repeat.ONE)
			CUSTOM_ACTION_TURN_REPEAT_ONE_OFF -> remote.playerApi.setRepeat(Repeat.OFF)
			CUSTOM_ACTION_ADD_TO_COLLECTION -> currentTrack?.mediaId?.let { remote.userApi.addToLibrary(it) }
			CUSTOM_ACTION_REMOVE_FROM_COLLECTION -> currentTrack?.mediaId?.let { remote.userApi.removeFromLibrary(it) }
		}
	}

	override fun getQueue(): QueueMetadata? {
		// unreliable per https://github.com/spotify/android-sdk/issues/10
		return queueMetadata
	}

	override fun getMetadata(): MusicMetadata? {
		return currentTrack
	}

	override fun getPlaybackPosition(): PlaybackPosition {
		return position
	}

	override fun isSupportedAction(action: MusicAction): Boolean {
		val playerActions = playerActions
		return when (action) {
			MusicAction.SKIP_TO_PREVIOUS -> playerActions?.canSkipPrev == true
			MusicAction.SKIP_TO_NEXT -> playerActions?.canSkipNext == true
			MusicAction.PLAY -> true
			MusicAction.PAUSE -> true
			MusicAction.SEEK_TO -> playerActions?.canSeek == true
			MusicAction.SKIP_TO_QUEUE_ITEM -> false
			MusicAction.SET_SHUFFLE_MODE -> playerActions?.canToggleShuffle == true
			MusicAction.SET_REPEAT_MODE -> playerActions?.canRepeatContext == true || playerActions?.canRepeatTrack == true
			else -> false
		}
	}

	override fun getCustomActions(): List<CustomAction> {
		val actions = LinkedList<CustomAction>()

		// Needed for when the repeat button isn't supported by the hmiState
		if (getRepeatMode() == RepeatMode.ALL) {
			if (playerActions?.canRepeatTrack == true) {
				actions.add(CUSTOM_ACTION_TURN_REPEAT_ONE_ON)
			}
			else {
				actions.add(CUSTOM_ACTION_TURN_REPEAT_ONE_OFF)
			}
		} else if (getRepeatMode() == RepeatMode.ONE) {
			actions.add(CUSTOM_ACTION_TURN_REPEAT_ONE_OFF)
		}
		else {
			if (playerActions?.canRepeatContext == true) {
				actions.add(CUSTOM_ACTION_TURN_REPEAT_ALL_ON)
			}
			else if (playerActions?.canRepeatTrack == true) {
				actions.add(CUSTOM_ACTION_TURN_REPEAT_ONE_ON)
			}
		}

		if (currentTrackLibrary == false) actions.add(CUSTOM_ACTION_ADD_TO_COLLECTION)
		if (currentTrackLibrary == true) actions.add(CUSTOM_ACTION_REMOVE_FROM_COLLECTION)

		// START_RADIO is blocked on https://github.com/spotify/android-sdk/issues/66

		return actions
	}

	override fun toggleShuffle() {
		val shuffling = isShuffling()
		remote.playerApi.setShuffle(!shuffling)
	}

	override fun toggleRepeat() {
		if (playerOptions?.repeatMode == Repeat.OFF) {
			if (playerActions?.canRepeatContext == true) {
				remote.playerApi.setRepeat(Repeat.ALL)
			} else if (playerActions?.canRepeatTrack == true) {
				remote.playerApi.setRepeat(Repeat.ONE)
			}
		} else if (playerOptions?.repeatMode == Repeat.ALL) {
			if (playerActions?.canRepeatTrack == true) {
				remote.playerApi.setRepeat(Repeat.ONE)
			} else {
				remote.playerApi.setRepeat(Repeat.OFF)
			}
		} else {
			remote.playerApi.setRepeat(Repeat.OFF)
		}
	}

	override fun isShuffling(): Boolean {
		return playerOptions?.isShuffling == true
	}

	override fun getRepeatMode(): RepeatMode {
		return when(playerOptions?.repeatMode) {
			Repeat.ALL -> RepeatMode.ALL
			Repeat.ONE -> RepeatMode.ONE
			else -> RepeatMode.OFF
		}
	}

	override suspend fun browse(directory: MusicMetadata?): List<MusicMetadata> {
		val deferred = CompletableDeferred<List<MusicMetadata>>()
		if (directory?.mediaId == null) {
			remote.contentApi.getRecommendedContentItems("default-cars").setResultCallback { results ->
				deferred.complete(results?.items?.map {
					SpotifyMusicMetadata.fromBrowseItem(this, it)
				} ?: LinkedList())
			}.setErrorCallback {
				deferred.complete(LinkedList())
			}
		} else {
			loadPaginatedItems(directory.toListItem(), { !deferred.isCancelled }) {
				deferred.complete(it)
			}
		}
		return deferred.await()
	}

	/**
	 * Removes the shuffle play button [MusicMetadata] if it is present in the supplied list.
	 *
	 * When loading a list of tracks such as an album, a shuffle play button [MusicMetadata] object is
	 * sometimes present. Call this method to get the list of [MusicMetadata]s that omit the shuffle
	 * play button.
	 */
	private fun removeShufflePlayButtonMetadata(items: List<MusicMetadata>): List<MusicMetadata> {
		return if (items.isNotEmpty() && items[0].mediaId == queueUri) {
			items.drop(1)
		} else {
			items
		}
	}

	override suspend fun search(query: String): List<MusicMetadata> {
		val deferred = CompletableDeferred<List<MusicMetadata>>()
		GlobalScope.launch(defaultDispatcher) {
			deferred.complete(webApi.searchForQuery(this@SpotifyAppController, query))
		}
		return deferred.await()
	}

	override fun subscribe(callback: (MusicAppController) -> Unit) {
		this.callback = callback
	}

	override fun isConnected(): Boolean {
		return this.connected
	}

	override fun disconnect() {
		Log.d(TAG, "Disconnecting from Spotify")
		this.connected = false
		this.callback = null
		try {
			spotifySubscription.cancel()
		} catch (e: Exception) {
			Log.w(TAG, "Exception while disconnecting from Spotify: $e")
		}
		try {
			playlistSubscription.cancel()
		} catch (e: Exception) {
			Log.w(TAG, "Exception while disconnecting from Spotify: $e")
		}
		try {
			SpotifyAppRemote.disconnect(remote)
		} catch (e: Exception) {
			Log.w(TAG, "Exception while disconnecting from Spotify: $e")
		}

		webApi.disconnect()
	}

	override fun toString(): String {
		return "SpotifyAppController"
	}
}