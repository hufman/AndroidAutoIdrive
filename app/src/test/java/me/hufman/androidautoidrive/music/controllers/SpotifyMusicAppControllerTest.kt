package me.hufman.androidautoidrive.music.controllers

import android.graphics.Bitmap
import com.nhaarman.mockito_kotlin.*
import com.spotify.android.appremote.api.*
import com.spotify.protocol.client.CallResult
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.*
import junit.framework.Assert.assertEquals
import kotlinx.coroutines.*
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.coroutines.test.runBlockingTest
import me.hufman.androidautoidrive.music.CustomAction
import me.hufman.androidautoidrive.music.MusicAction
import me.hufman.androidautoidrive.music.MusicMetadata
import me.hufman.androidautoidrive.music.RepeatMode
import me.hufman.androidautoidrive.music.spotify.SpotifyMusicMetadata
import me.hufman.androidautoidrive.music.spotify.SpotifyWebApi
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SpotifyMusicAppControllerTest {
	val contentCallback = argumentCaptor<CallResult.ResultCallback<ListItems>>()
	val imagesCallback = argumentCaptor<CallResult.ResultCallback<Bitmap>>()
	val libraryCallback = argumentCaptor<CallResult.ResultCallback<LibraryState>>()
	val spotifyCallback = argumentCaptor<Subscription.EventCallback<PlayerState>>()
	val playlistCallback = argumentCaptor<Subscription.EventCallback<PlayerContext>>()

	val connectApi = mock<ConnectApi>()
	val contentApi = mock<ContentApi> {
		on { getRecommendedContentItems(any()) } doAnswer {
			val result = mock<CallResult<ListItems>>()
			whenever(result.setResultCallback(contentCallback.capture())) doReturn result
			result
		}
		on { getChildrenOfItem(any(), any(), any()) } doAnswer {
			val result = mock<CallResult<ListItems>>()
			whenever(result.setResultCallback(contentCallback.capture())) doReturn result
			result
		}
	}
	val imagesApi = mock<ImagesApi> {
		on { getImage(any()) } doAnswer {
			val result = mock<CallResult<Bitmap>>()
			whenever(result.setResultCallback(imagesCallback.capture())) doReturn result
			result
		}
		on { getImage(any(), any()) } doAnswer {
			val result = mock<CallResult<Bitmap>>()
			whenever(result.setResultCallback(imagesCallback.capture())) doReturn result
			result
		}
	}
	val playerApi = mock<PlayerApi> {
		on { subscribeToPlayerState() } doAnswer {
			val subscription = mock<Subscription<PlayerState>>()
			whenever(subscription.setEventCallback(spotifyCallback.capture())) doReturn subscription
			subscription
		}
		on { subscribeToPlayerContext() } doAnswer {
			val subscription = mock<Subscription<PlayerContext>>()
			whenever(subscription.setEventCallback(playlistCallback.capture())) doReturn subscription
			subscription
		}
	}
	val userApi = mock<UserApi> {
		on { getLibraryState(any()) } doAnswer {
			val result = mock<CallResult<LibraryState>>()
			whenever(result.setResultCallback(libraryCallback.capture())) doReturn result
			result
		}
	}

	val remote = mock<SpotifyAppRemote> {
		on { connectApi } doReturn connectApi
		on { contentApi } doReturn contentApi
		on { imagesApi } doReturn imagesApi
		on { playerApi } doReturn playerApi
		on { userApi } doReturn userApi
	}

	val webApi = mock<SpotifyWebApi> {
	}

	lateinit var controller: SpotifyAppController
	private val testDispatcher = TestCoroutineDispatcher()

	@Before
	fun setup() {
		controller = SpotifyAppController(mock(), remote, webApi)
		controller.defaultDispatcher = testDispatcher

	}

	@After
	fun tearDown() {
		testDispatcher.cleanupTestCoroutines()
	}

	@Test
	fun testControl() {
		controller.play()
		verify(playerApi).resume()
		verify(connectApi).connectSwitchToLocalDevice()

		controller.pause()
		verify(playerApi).pause()

		controller.skipToPrevious()
		verify(playerApi).skipPrevious()

		controller.skipToNext()
		verify(playerApi).skipNext()

		controller.seekTo(200)
		verify(playerApi).seekTo(200)

		val song = MusicMetadata(mediaId = "test", queueId = 2)
		controller.playSong(song)
		verify(playerApi).play(song.mediaId)
	}

	@Test
	fun testCustomActions() {
		controller.customAction(controller.CUSTOM_ACTION_TURN_REPEAT_ALL_ON)
		verify(playerApi).setRepeat(Repeat.ALL)
		controller.customAction(controller.CUSTOM_ACTION_TURN_REPEAT_ONE_ON)
		verify(playerApi).setRepeat(Repeat.ONE)
		controller.customAction(controller.CUSTOM_ACTION_TURN_REPEAT_ONE_OFF)
		verify(playerApi).setRepeat(Repeat.OFF)

		controller.currentTrack = MusicMetadata(mediaId = "mediaId")
		controller.customAction(controller.CUSTOM_ACTION_ADD_TO_COLLECTION)
		verify(userApi).addToLibrary("mediaId")

		controller.customAction(controller.CUSTOM_ACTION_REMOVE_FROM_COLLECTION)
		verify(userApi).removeFromLibrary("mediaId")
	}

	@Test
	fun testCustomActionNames() {
		assertEquals("Like", controller.CUSTOM_ACTION_ADD_TO_COLLECTION.name)
		assertEquals("Dislike", controller.CUSTOM_ACTION_REMOVE_FROM_COLLECTION.name)
		assertEquals("Make Radio Station", controller.CUSTOM_ACTION_START_RADIO.name)
	}

	@Test
	fun testIsShuffling() {
		// test shuffle is started on app start
		run {
			val state = PlayerState(
					Track(
							Artist("artist", "uri"),
							listOf(Artist("artist", "uri")),
							Album("album", "uri"), 300000, "name", "uri", mock(), false, false),
					false, 1.0f, 200,
					PlayerOptions(true, Repeat.OFF),
					PlayerRestrictions(true, true, true, true, true, true)
			)
			spotifyCallback.lastValue.onEvent(state)

			assertTrue(controller.isShuffling())
		}

		// test shuffle is not started on app start
		run {
			val state = PlayerState(
					Track(
							Artist("artist", "uri"),
							listOf(Artist("artist", "uri")),
							Album("album", "uri"), 300000, "name", "uri", mock(), false, false),
					false, 1.0f, 200,
					PlayerOptions(false, Repeat.OFF),
					PlayerRestrictions(true, true, true, true, true, true)
			)
			spotifyCallback.lastValue.onEvent(state)

			assertFalse(controller.isShuffling())
		}

		// test shuffle bool is flipped upon spotify callback changing
		run {
			val initialState = PlayerState(
					Track(
							Artist("artist", "uri"),
							listOf(Artist("artist", "uri")),
							Album("album", "uri"), 300000, "name", "uri", mock(), false, false),
					false, 1.0f, 200,
					PlayerOptions(false, Repeat.OFF),
					PlayerRestrictions(true, true, true, true, true, true)
			)
			spotifyCallback.lastValue.onEvent(initialState)

			assertFalse(controller.isShuffling())

			val newState = PlayerState(
					Track(
							Artist("artist", "uri"),
							listOf(Artist("artist", "uri")),
							Album("album", "uri"), 300000, "name", "uri", mock(), false, false),
					false, 1.0f, 200,
					PlayerOptions(true, Repeat.OFF),
					PlayerRestrictions(true, true, true, true, true, true)
			)
			spotifyCallback.lastValue.onEvent(newState)

			assertTrue(controller.isShuffling())
		}
	}

	@Test
	fun testToggleShuffle() {
		// test shuffle bool is flipped when shuffle is called: false -> true
		run {
			val state = PlayerState(
					Track(
							Artist("artist", "uri"),
							listOf(Artist("artist", "uri")),
							Album("album", "uri"), 300000, "name", "uri", mock(), false, false),
					false, 1.0f, 200,
					PlayerOptions(false, Repeat.OFF),
					PlayerRestrictions(true, true, true, true, true, true)
			)
			spotifyCallback.lastValue.onEvent(state)

			controller.toggleShuffle()
			verify(playerApi).setShuffle(true)
		}

		// test shuffle bool is flipped when shuffle is called: true -> false
		run {
			val state = PlayerState(
					Track(
							Artist("artist", "uri"),
							listOf(Artist("artist", "uri")),
							Album("album", "uri"), 300000, "name", "uri", mock(), false, false),
					false, 1.0f, 200,
					PlayerOptions(true, Repeat.OFF),
					PlayerRestrictions(true, true, true, true, true, true)
			)
			spotifyCallback.lastValue.onEvent(state)

			controller.toggleShuffle()
			verify(playerApi).setShuffle(false)
		}
	}

	@Test
	fun testRepeatMode() {
		// test repeat is off
		run {
			val state = PlayerState(
					Track(
							Artist("artist", "uri"),
							listOf(Artist("artist", "uri")),
							Album("album", "uri"), 300000, "name", "uri", mock(), false, false),
					false, 1.0f, 200,
					PlayerOptions(false, Repeat.OFF),
					PlayerRestrictions(true, true, true, true, true, true)
			)
			spotifyCallback.lastValue.onEvent(state)

			assertEquals(RepeatMode.OFF, controller.getRepeatMode())
		}

		// test repeating all
		run {
			val state = PlayerState(
					Track(
							Artist("artist", "uri"),
							listOf(Artist("artist", "uri")),
							Album("album", "uri"), 300000, "name", "uri", mock(), false, false),
					false, 1.0f, 200,
					PlayerOptions(false, Repeat.ALL),
					PlayerRestrictions(true, true, true, true, true, true)
			)
			spotifyCallback.lastValue.onEvent(state)

			assertEquals(RepeatMode.ALL, controller.getRepeatMode())
		}

		// test repeating one
		run {
			val state = PlayerState(
					Track(
							Artist("artist", "uri"),
							listOf(Artist("artist", "uri")),
							Album("album", "uri"), 300000, "name", "uri", mock(), false, false),
					false, 1.0f, 200,
					PlayerOptions(false, Repeat.ONE),
					PlayerRestrictions(true, true, true, true, true, true)
			)
			spotifyCallback.lastValue.onEvent(state)

			assertEquals(RepeatMode.ONE, controller.getRepeatMode())
		}

		// test when switching to repeating all
		run {
			val state = PlayerState(
					Track(
							Artist("artist", "uri"),
							listOf(Artist("artist", "uri")),
							Album("album", "uri"), 300000, "name", "uri", mock(), false, false),
					false, 1.0f, 200,
					PlayerOptions(false, Repeat.OFF),
					PlayerRestrictions(true, true, true, true, true, true)
			)
			spotifyCallback.lastValue.onEvent(state)

			assertEquals(RepeatMode.OFF, controller.getRepeatMode())

			val newState = PlayerState(
					Track(
							Artist("artist", "uri"),
							listOf(Artist("artist", "uri")),
							Album("album", "uri"), 300000, "name", "uri", mock(), false, false),
					false, 1.0f, 200,
					PlayerOptions(false, Repeat.ALL),
					PlayerRestrictions(true, true, true, true, true, true)
			)
			spotifyCallback.lastValue.onEvent(newState)

			assertEquals(RepeatMode.ALL, controller.getRepeatMode())
		}
	}

	@Test
	fun testToggleRepeatFromOff_CannotRepeatTrackOrContext() {
		// repeat off -> repeat all, cannot repeat track, cannot repeat context
		val state = PlayerState(
				Track(
						Artist("artist", "uri"),
						listOf(Artist("artist", "uri")),
						Album("album", "uri"), 300000, "name", "uri", mock(), false, false),
				false, 1.0f, 200,
				PlayerOptions(false, Repeat.OFF),
				PlayerRestrictions(true, true, false, false, true, true)
		)
		spotifyCallback.lastValue.onEvent(state)
		controller.toggleRepeat()

		verify(playerApi, never()).setRepeat(any())
	}

	@Test
	fun testToggleRepeatFromOff_CanRepeatTrackAndContext() {
		// repeat off -> repeat all, can repeat track, can repeat context
		val state = PlayerState(
				Track(
						Artist("artist", "uri"),
						listOf(Artist("artist", "uri")),
						Album("album", "uri"), 300000, "name", "uri", mock(), false, false),
				false, 1.0f, 200,
				PlayerOptions(false, Repeat.OFF),
				PlayerRestrictions(true, true, true, true, true, true)
		)
		spotifyCallback.lastValue.onEvent(state)
		controller.toggleRepeat()

		verify(playerApi).setRepeat(Repeat.ALL)
	}

	@Test
	fun testToggleRepeatFromOff_CanRepeatTrackCannotRepeatContext() {
		// repeat off -> repeat all, can repeat track, cannot repeat context
		val state = PlayerState(
				Track(
						Artist("artist", "uri"),
						listOf(Artist("artist", "uri")),
						Album("album", "uri"), 300000, "name", "uri", mock(), false, false),
				false, 1.0f, 200,
				PlayerOptions(false, Repeat.OFF),
				PlayerRestrictions(true, true, true, false, true, true)
		)
		spotifyCallback.lastValue.onEvent(state)
		controller.toggleRepeat()

		verify(playerApi).setRepeat(Repeat.ONE)
	}

	@Test
	fun testToggleRepeatFromAll_CanRepeatTrackAndContext() {
		// repeat all -> repeat one, can repeat track, can repeat context
		val state = PlayerState(
				Track(
						Artist("artist", "uri"),
						listOf(Artist("artist", "uri")),
						Album("album", "uri"), 300000, "name", "uri", mock(), false, false),
				false, 1.0f, 200,
				PlayerOptions(false, Repeat.ALL),
				PlayerRestrictions(true, true, true, true, true, true)
		)
		spotifyCallback.lastValue.onEvent(state)
		controller.toggleRepeat()

		verify(playerApi).setRepeat(Repeat.ONE)
	}

	@Test
	fun testToggleRepeatFromAll_CannotRepeatTrackCanRepeatContext() {
		// repeat all -> repeat one, cannot repeat track, can repeat context
		val state = PlayerState(
				Track(
						Artist("artist", "uri"),
						listOf(Artist("artist", "uri")),
						Album("album", "uri"), 300000, "name", "uri", mock(), false, false),
				false, 1.0f, 200,
				PlayerOptions(false, Repeat.ALL),
				PlayerRestrictions(true, true, false, true, true, true)
		)
		spotifyCallback.lastValue.onEvent(state)
		controller.toggleRepeat()

		verify(playerApi).setRepeat(Repeat.OFF)
	}

	@Test
	fun testToggleRepeatFromOne() {
		// repeat one -> repeat off
		val state = PlayerState(
				Track(
						Artist("artist", "uri"),
						listOf(Artist("artist", "uri")),
						Album("album", "uri"), 300000, "name", "uri", mock(), false, false),
				false, 1.0f, 200,
				PlayerOptions(false, Repeat.ONE),
				PlayerRestrictions(true, true, true, true, true, true)
		)
		spotifyCallback.lastValue.onEvent(state)
		controller.toggleRepeat()

		verify(playerApi).setRepeat(Repeat.OFF)
	}

	@Test
	fun testGetCustomActions() {
		// shuffle off, repeat off, can repeat playlist and track
		run {
			val state = PlayerState(
					Track(
							Artist("artist", "uri"),
							listOf(Artist("artist", "uri")),
							Album("album", "uri"), 300000, "name", "uri", mock(), false, false),
					false, 1.0f, 200,
					PlayerOptions(false, Repeat.OFF),
					PlayerRestrictions(true, true, true, true, true, true)
			)
			spotifyCallback.lastValue.onEvent(state)

			assertEquals(setOf(
					controller.CUSTOM_ACTION_TURN_REPEAT_ALL_ON
			), controller.getCustomActions().toSet())

			libraryCallback.lastValue.onResult(LibraryState("uri", false, true))
			assertEquals(setOf(
					controller.CUSTOM_ACTION_TURN_REPEAT_ALL_ON,
					controller.CUSTOM_ACTION_ADD_TO_COLLECTION
			), controller.getCustomActions().toSet())

			libraryCallback.lastValue.onResult(LibraryState("uri", true, true))
			assertEquals(setOf(
					controller.CUSTOM_ACTION_TURN_REPEAT_ALL_ON,
					controller.CUSTOM_ACTION_REMOVE_FROM_COLLECTION
			), controller.getCustomActions().toSet())
		}

		// shuffle off, repeat off, can repeat track but not playlist
		run {
			val state = PlayerState(
					Track(
							Artist("artist", "uri"),
							listOf(Artist("artist", "uri")),
							Album("album", "uri"), 300000, "name", "uri", mock(), false, false),
					false, 1.0f, 200,
					PlayerOptions(false, Repeat.OFF),
					PlayerRestrictions(true, true, true, false, true, true)
			)
			spotifyCallback.lastValue.onEvent(state)

			assertEquals(setOf(
					controller.CUSTOM_ACTION_TURN_REPEAT_ONE_ON
			), controller.getCustomActions().toSet())
		}

		// shuffle off, repeat off, can not repeat
		run {
			val state = PlayerState(
					Track(
							Artist("artist", "uri"),
							listOf(Artist("artist", "uri")),
							Album("album", "uri"), 300000, "name", "uri", mock(), false, false),
					false, 1.0f, 200,
					PlayerOptions(false, Repeat.OFF),
					PlayerRestrictions(true, true, false, false, true, true)
			)
			spotifyCallback.lastValue.onEvent(state)

			assertEquals(emptySet<CustomAction>(), controller.getCustomActions().toSet())
		}

		// shuffle on, repeat all, can repeat playlist and track
		run {
			val state = PlayerState(
					Track(
							Artist("artist", "uri"),
							listOf(Artist("artist", "uri")),
							Album("album", "uri"), 300000, "name", "uri", mock(), false, false),
					false, 1.0f, 200,
					PlayerOptions(true, Repeat.ALL),
					PlayerRestrictions(true, true, true, true, true, true)
			)
			spotifyCallback.lastValue.onEvent(state)

			assertEquals(setOf(
					controller.CUSTOM_ACTION_TURN_REPEAT_ONE_ON
			), controller.getCustomActions().toSet())
		}

		// shuffle on, repeat all, can repeat playlist but not track
		run {
			val state = PlayerState(
					Track(
							Artist("artist", "uri"),
							listOf(Artist("artist", "uri")),
							Album("album", "uri"), 300000, "name", "uri", mock(), false, false),
					false, 1.0f, 200,
					PlayerOptions(true, Repeat.ALL),
					PlayerRestrictions(true, true, false, true, true, true)
			)
			spotifyCallback.lastValue.onEvent(state)

			assertEquals(setOf(
					controller.CUSTOM_ACTION_TURN_REPEAT_ONE_OFF
			), controller.getCustomActions().toSet())
		}

		// shuffle on, repeat one, can repeat playlist and track
		run {
			val state = PlayerState(
					Track(
							Artist("artist", "uri"),
							listOf(Artist("artist", "uri")),
							Album("album", "uri"), 300000, "name", "uri", mock(), false, false),
					false, 1.0f, 200,
					PlayerOptions(true, Repeat.ONE),
					PlayerRestrictions(true, true, true, true, true, true)
			)
			spotifyCallback.lastValue.onEvent(state)

			assertEquals(setOf(
					controller.CUSTOM_ACTION_TURN_REPEAT_ONE_OFF
			), controller.getCustomActions().toSet())
		}
	}

	@Test
	fun testGetCoverArt() {
		val imageUri = ImageUri("test")
		val mockBitmap: Bitmap = mock()

		// cover art cache miss
		val nullCoverArtBitmap = controller.getCoverArt(imageUri)
		assertNull(nullCoverArtBitmap)

		verify(imagesApi).getImage(imageUri, Image.Dimension.THUMBNAIL)
		imagesCallback.lastValue.onResult(mockBitmap)

		// cover art cache hit
		val coverArtBitmap = controller.getCoverArt(imageUri)
		assertEquals(mockBitmap, coverArtBitmap)
	}

	@Test
	fun testQueue() {
		val queueTitle = "title"
		val queueSubtitle = "subtitle"
		val queueImageUri = ImageUri("imageUri")
		val queueCoverArtBitmap: Bitmap = mock()

		// load a queue
		playlistCallback.lastValue.onEvent(PlayerContext("playlisturi", queueTitle, queueSubtitle, "playlist"))
		verify(webApi).clearGetLikedSongsAttemptedFlag()
		verify(contentApi).getChildrenOfItem(ListItem("playlisturi", "playlisturi", null, queueTitle, queueSubtitle, false, true), 200, 0)
		contentCallback.lastValue.onResult(ListItems(200, 0, 2, arrayOf(
				ListItem("id", "uri", null, "Title", "Subtitle", true, false)
		)))
		// it should request again
		verify(contentApi).getChildrenOfItem(ListItem("playlisturi", "playlisturi", null, queueTitle, queueSubtitle, false, true), 200, 1)
		contentCallback.lastValue.onResult(ListItems(200, 1, 2, arrayOf(
				ListItem("id2", "uri2", null, "Title2", "Subtitle", true, false)
		)))

		// it should get the QueueMetadata information
		val recentlyPlayedUri = "com.spotify.recently-played"
		verify(contentApi).getChildrenOfItem(ListItem(recentlyPlayedUri, recentlyPlayedUri, null, null, null, false, true), 1, 0)
		contentCallback.lastValue.onResult(ListItems(1, 0, 1, arrayOf(
				ListItem("queueId", "queueUri", queueImageUri, queueTitle, queueSubtitle, false, true)
		)))

		verify(imagesApi).getImage(queueImageUri, Image.Dimension.THUMBNAIL)
		imagesCallback.lastValue.onResult(queueCoverArtBitmap)

		val queue = controller.getQueue()
		assertNotNull(queue)
		assertEquals(queueTitle, queue!!.title)
		assertEquals(queueSubtitle, queue.subtitle)
		assertEquals(queueCoverArtBitmap, queue.coverArt)
		assertNotNull(queue.songs)

		val songs = queue.songs!!
		assertEquals(2, songs.size)
		assertNotEquals(null, songs[0].queueId)
		assertEquals("Title", songs[0].title)
		assertEquals("Title2", songs[1].title)

		// fail to skip
		controller.playQueue(MusicMetadata(queueId = 345))
		verify(playerApi, never()).skipToIndex(any(), any())
		// try to skip to it
		controller.playQueue(songs[0])
		verify(playerApi).skipToIndex("playlisturi", 0)
	}

	@Test
	fun testQueue_LikedSongsPlaylist_WebAPILoaded() = runBlockingTest {
		val queueTitle = "Liked Songs"
		val queueSubtitle = null
		val queueImageUri = ImageUri("imageUri")
		val queueCoverArtBitmap: Bitmap = mock()

		whenever(webApi.getLikedSongs(controller)) doAnswer {
			listOf(
					SpotifyMusicMetadata(controller, "mediaId1", 1, "coverArtUri1", "Artist 1", "Album 1", "Title 1"),
					SpotifyMusicMetadata(controller, "mediaId2", 2, "coverArtUri2", "Artist 2", "Album 2", "Title 2")
			)
		}

		playlistCallback.lastValue.onEvent(PlayerContext("playlisturi", queueTitle, queueSubtitle, "your_library_tracks"))

		// it should get the QueueMetadata information
		val recentlyPlayedUri = "com.spotify.recently-played"
		verify(contentApi).getChildrenOfItem(ListItem(recentlyPlayedUri, recentlyPlayedUri, null, null, null, false, true), 1, 0)
		contentCallback.lastValue.onResult(ListItems(1, 0, 1, arrayOf(
				ListItem("queueId", "queueUri", queueImageUri, queueTitle, queueSubtitle, false, true)
		)))

		verify(imagesApi).getImage(queueImageUri, Image.Dimension.THUMBNAIL)
		imagesCallback.lastValue.onResult(queueCoverArtBitmap)

		val queue = controller.getQueue()
		assertNotNull(queue)
		assertEquals(queueTitle, queue!!.title)
		assertEquals(queueSubtitle, queue.subtitle)
		assertEquals(queueCoverArtBitmap, queue.coverArt)
		assertNotNull(queue.songs)

		val songs = queue.songs!!
		assertEquals(2, songs.size)
		assertEquals("mediaId1", songs[0].mediaId)
		assertEquals("Title 1", songs[0].title)
		assertEquals("mediaId2", songs[1].mediaId)
		assertEquals("Title 2", songs[1].title)
	}

	@Test
	fun testQueue_LikedSongsPlaylist_WebAPINotLoaded() = runBlockingTest {
		val queueTitle = "Liked Songs"
		val queueSubtitle = null
		val queueImageUri = ImageUri("imageUri")
		val queueCoverArtBitmap: Bitmap = mock()

		whenever(webApi.getLikedSongs(controller)) doAnswer { emptyList() }

		playlistCallback.lastValue.onEvent(PlayerContext("playlisturi", queueTitle, queueSubtitle, "your_library_tracks"))
		verify(contentApi).getChildrenOfItem(ListItem("playlisturi", "playlisturi", null, queueTitle, queueSubtitle, false, true), 200, 0)
		contentCallback.lastValue.onResult(ListItems(200, 0, 2, arrayOf(
				ListItem("mediaId1", "mediaId1", null, "Title 1", "Subtitle 1", true, false),
				ListItem("mediaId2", "mediaId2", null, "Title 2", "Subtitle 2", true, false)
		)))

		// it should get the QueueMetadata information when called through the Spotify App Remote
		val recentlyPlayedUri = "com.spotify.recently-played"
		verify(contentApi).getChildrenOfItem(ListItem(recentlyPlayedUri, recentlyPlayedUri, null, null, null, false, true), 1, 0)
		contentCallback.lastValue.onResult(ListItems(1, 0, 1, arrayOf(
				ListItem("queueId", "queueUri", queueImageUri, queueTitle, queueSubtitle, false, true)
		)))

		verify(imagesApi).getImage(queueImageUri, Image.Dimension.THUMBNAIL)
		imagesCallback.lastValue.onResult(queueCoverArtBitmap)

		val queue = controller.getQueue()
		assertNotNull(queue)
		assertEquals(queueTitle, queue!!.title)
		assertEquals(queueSubtitle, queue.subtitle)
		assertEquals(queueCoverArtBitmap, queue.coverArt)
		assertNotNull(queue.songs)

		val songs = queue.songs!!
		assertEquals(2, songs.size)
		assertEquals("mediaId1", songs[0].mediaId)
		assertEquals("Title 1", songs[0].title)
		assertEquals("mediaId2", songs[1].mediaId)
		assertEquals("Title 2", songs[1].title)
	}

	@Test
	fun testStateUpdate() {
		val state = PlayerState(
				Track(
					Artist("artist", "uri"),
					listOf(Artist("artist", "uri")),
					Album("album", "uri"), 300000, "name", "uri", mock(), false, false),
				false, 1.0f, 200,
				PlayerOptions.DEFAULT, PlayerRestrictions.DEFAULT
		)
		spotifyCallback.lastValue.onEvent(state)

		val metadata = controller.getMetadata()
		val position = controller.getPlaybackPosition()
		assertEquals("name", metadata!!.title)
		assertEquals("artist", metadata.artist)
		assertEquals("album", metadata.album)
		assertEquals(null, metadata.coverArt)
		assertEquals(200, position.lastPosition)
		assertEquals(false, position.isPaused)
		assertFalse(controller.isSupportedAction(MusicAction.SKIP_TO_PREVIOUS))
		assertFalse(controller.isSupportedAction(MusicAction.SKIP_TO_NEXT))
		assertFalse(controller.isSupportedAction(MusicAction.SEEK_TO))
		assertTrue(controller.isSupportedAction(MusicAction.PLAY))
		assertTrue(controller.isSupportedAction(MusicAction.PAUSE))
		assertFalse(controller.isSupportedAction(MusicAction.SET_SHUFFLE_MODE))

		// resolve the cover art
		imagesCallback.lastValue.onResult(mock())
		assertNotEquals(null, controller.getMetadata()?.coverArt)

		// set the player restrictions for skipping and seeking
		val premiumState = PlayerState(
				Track(
						Artist("artist", "uri"),
						listOf(Artist("artist", "uri")),
						Album("album", "uri"), 300000, "name", "uri", mock(), false, false),
				false, 1.0f, 200,
				PlayerOptions.DEFAULT, PlayerRestrictions(true, true, true, true, true, true)
		)
		spotifyCallback.lastValue.onEvent(premiumState)
		assertTrue(controller.isSupportedAction(MusicAction.SKIP_TO_PREVIOUS))
		assertTrue(controller.isSupportedAction(MusicAction.SKIP_TO_NEXT))
		assertTrue(controller.isSupportedAction(MusicAction.SEEK_TO))
	}

	@Test
	fun testBrowse() {
		runBlocking {
			val deferredResults = async {
				controller.browse(null)
			}
			delay(1000)
			assertFalse(deferredResults.isCompleted)
			verify(contentApi).getRecommendedContentItems("default-cars")
			contentCallback.lastValue.onResult(ListItems(1, 0, 1, arrayOf(
					ListItem("id", "uri", null, "Title", "Subtitle", true, false)
			)))
			val results = deferredResults.await()
			assertTrue(deferredResults.isCompleted)
			assertEquals("Title", results[0].title)
		}

		runBlocking {
			val deferredResults = async {
				controller.browse(MusicMetadata(mediaId = "library", browseable = true))
			}
			delay(1000)
			assertFalse(deferredResults.isCompleted)
			verify(contentApi).getChildrenOfItem(ListItem("library", "library", null, null, null, false, true), 200, 0)
			contentCallback.lastValue.onResult(ListItems(1, 0, 2, arrayOf(
					ListItem("id", "uri", null, "Favorite", "Subtitle", true, false)
			)))
			// it should check again
			verify(contentApi).getChildrenOfItem(ListItem("library", "library", null, null, null, false, true), 200, 1)
			contentCallback.lastValue.onResult(ListItems(200, 1, 2, arrayOf(
					ListItem("id2", "uri", null, "Favorite2", "Subtitle", true, false)
			)))
			val results = deferredResults.await()
			assertTrue(deferredResults.isCompleted)
			assertEquals("Favorite", results[0].title)
			assertEquals("Favorite2", results[1].title)
		}
	}

	@Test
	fun testSearch() {
		runBlocking {
			assertEquals(null, controller.search("any"))
		}
	}

	@Test
	fun testDisconnect() {
		controller.subscribe {  }
		assertEquals(1, controller.listeners.size)
		controller.disconnect()
		assertEquals(0, controller.listeners.size)
		verify(controller.spotifySubscription).cancel()
		verify(controller.playlistSubscription).cancel()
		verify(webApi).disconnect()
	}

	@Test
	fun testToString() {
		assertEquals("SpotifyAppController", controller.toString())
	}
}