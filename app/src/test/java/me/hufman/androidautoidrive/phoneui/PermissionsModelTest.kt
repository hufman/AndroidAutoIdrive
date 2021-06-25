package me.hufman.androidautoidrive.phoneui

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.LiveData
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.verify
import com.nhaarman.mockito_kotlin.whenever
import com.spotify.android.appremote.api.error.*
import me.hufman.androidautoidrive.MutableObservable
import me.hufman.androidautoidrive.R
import me.hufman.androidautoidrive.music.controllers.SpotifyAppController
import me.hufman.androidautoidrive.music.spotify.SpotifyAuthStateManager
import me.hufman.androidautoidrive.phoneui.viewmodels.PermissionsModel
import me.hufman.androidautoidrive.phoneui.viewmodels.PermissionsState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt

class PermissionsModelTest {
	@Rule
	@JvmField
	val instantTaskExecutorRule = InstantTaskExecutorRule()

	val context = mock<Context> {
		on { getString(anyInt()) } doReturn ""
	}
	val notificationListenerState = mock<LiveData<Boolean>>()
	val state = mock<PermissionsState>()
	val spotifyConnector = mock<SpotifyAppController.Connector>()
	val spotifyAuthStateManager = mock<SpotifyAuthStateManager>()
	val viewModel = PermissionsModel(notificationListenerState, state, spotifyConnector, spotifyAuthStateManager)

	@Test
	fun testModelNotification() {
		whenever(notificationListenerState.value) doReturn true
		listOf(true, false).forEach {
			whenever(state.hasNotificationPermission) doReturn it
			viewModel.update()
			assertEquals(it, viewModel.hasNotificationPermission.value)
		}

		whenever(notificationListenerState.value) doReturn false
		whenever(state.hasNotificationPermission) doReturn true
		viewModel.update()
		assertEquals(false, viewModel.hasNotificationPermission.value)
	}

	@Test
	fun testModelSms() {
		listOf(true, false).forEach {
			whenever(state.hasSmsPermission) doReturn it
			viewModel.update()
			assertEquals(it, viewModel.hasSmsPermission.value)
		}
	}

	@Test
	fun testModelLocation() {
		listOf(true, false).forEach {
			whenever(state.hasLocationPermission) doReturn it
			viewModel.update()
			assertEquals(it, viewModel.hasLocationPermission.value)
		}
	}

	@Test
	fun testSpotifyControlMissing() {
		whenever(spotifyConnector.isSpotifyInstalled()) doReturn false
		whenever(spotifyConnector.hasSupport()) doReturn false
		viewModel.updateSpotify()
		assertEquals(false, viewModel.hasSpotify.value)

		whenever(spotifyConnector.isSpotifyInstalled()) doReturn true
		whenever(spotifyConnector.hasSupport()) doReturn false
		viewModel.updateSpotify()
		assertEquals(false, viewModel.hasSpotify.value)

		whenever(spotifyConnector.isSpotifyInstalled()) doReturn false
		whenever(spotifyConnector.hasSupport()) doReturn true
		viewModel.updateSpotify()
		assertEquals(false, viewModel.hasSpotify.value)
	}

	@Test
	fun testSpotifyControlDisconnected() {
		whenever(spotifyConnector.isSpotifyInstalled()) doReturn true
		whenever(spotifyConnector.hasSupport()) doReturn true

		val result = MutableObservable<SpotifyAppController>()
		whenever(spotifyConnector.connect()) doReturn result

		viewModel.updateSpotify()
		result.listeners.keys.first().invoke(mock())     // should trigger _updateSpotify
		assertEquals(false, viewModel.hasSpotifyControlPermission.value)
		assertEquals("", context.run(viewModel.spotifyHint.value!!))
	}

	@Test
	fun testSpotifyControlModded() {
		whenever(spotifyConnector.isSpotifyInstalled()) doReturn true
		whenever(spotifyConnector.hasSupport()) doReturn true
		whenever(spotifyConnector.lastError) doReturn CouldNotFindSpotifyApp()

		val result = MutableObservable<SpotifyAppController>()
		whenever(spotifyConnector.connect()) doReturn result

		viewModel.updateSpotify()
		result.listeners.keys.first().invoke(mock())     // should trigger _updateSpotify
		assertEquals(false, viewModel.hasSpotifyControlPermission.value)
		context.run(viewModel.spotifyHint.value!!)
		verify(context).getString(R.string.musicAppNotes_spotify_apiNotFound)
	}

	@Test
	fun testSpotifyControlOffline() {
		whenever(spotifyConnector.isSpotifyInstalled()) doReturn true
		whenever(spotifyConnector.hasSupport()) doReturn true
		whenever(spotifyConnector.lastError) doReturn OfflineModeException("", mock())

		val result = MutableObservable<SpotifyAppController>()
		whenever(spotifyConnector.connect()) doReturn result

		viewModel.updateSpotify()
		result.listeners.keys.first().invoke(mock())     // should trigger _updateSpotify
		assertEquals(false, viewModel.hasSpotifyControlPermission.value)
		context.run(viewModel.spotifyHint.value!!)
		verify(context).getString(R.string.musicAppNotes_spotify_apiUnavailable)
	}

	@Test
	fun testSpotifyControlUnavailable() {
		whenever(spotifyConnector.isSpotifyInstalled()) doReturn true
		whenever(spotifyConnector.hasSupport()) doReturn true
		whenever(spotifyConnector.lastError) doReturn UserNotAuthorizedException("AUTHENTICATION_SERVICE_UNAVAILABLE", mock())

		val result = MutableObservable<SpotifyAppController>()
		whenever(spotifyConnector.connect()) doReturn result

		viewModel.updateSpotify()
		result.listeners.keys.first().invoke(mock())     // should trigger _updateSpotify
		assertEquals(false, viewModel.hasSpotifyControlPermission.value)
		context.run(viewModel.spotifyHint.value!!)
		verify(context).getString(R.string.musicAppNotes_spotify_apiUnavailable)
	}

	@Test
	fun testSpotifyControlUnauthorized() {
		whenever(spotifyConnector.isSpotifyInstalled()) doReturn true
		whenever(spotifyConnector.hasSupport()) doReturn true
		whenever(spotifyConnector.lastError) doReturn UserNotAuthorizedException("User authorization required", mock())

		val result = MutableObservable<SpotifyAppController>()
		whenever(spotifyConnector.connect()) doReturn result

		viewModel.updateSpotify()
		result.listeners.keys.first().invoke(mock())     // should trigger _updateSpotify
		assertEquals(false, viewModel.hasSpotifyControlPermission.value)
		assertEquals("", context.run(viewModel.spotifyHint.value!!))
	}

	@Test
	fun testSpotifyControlUnauthorizedOther() {
		whenever(spotifyConnector.isSpotifyInstalled()) doReturn true
		whenever(spotifyConnector.hasSupport()) doReturn true
		whenever(spotifyConnector.lastError) doReturn UserNotAuthorizedException("Unknown", mock())

		val result = MutableObservable<SpotifyAppController>()
		whenever(spotifyConnector.connect()) doReturn result

		viewModel.updateSpotify()
		result.listeners.keys.first().invoke(mock())     // should trigger _updateSpotify
		assertEquals(false, viewModel.hasSpotifyControlPermission.value)
		assertEquals("Unknown", context.run(viewModel.spotifyHint.value!!))
	}

	@Test
	fun testSpotifyControlUnknown() {
		whenever(spotifyConnector.isSpotifyInstalled()) doReturn true
		whenever(spotifyConnector.hasSupport()) doReturn true
		whenever(spotifyConnector.lastError) doReturn SpotifyRemoteServiceException("Unknown", mock())

		val result = MutableObservable<SpotifyAppController>()
		whenever(spotifyConnector.connect()) doReturn result

		viewModel.updateSpotify()
		result.listeners.keys.first().invoke(mock())     // should trigger _updateSpotify
		assertEquals(false, viewModel.hasSpotifyControlPermission.value)
		assertEquals("Unknown", context.run(viewModel.spotifyHint.value!!))
	}

	@Test
	fun testSpotifyControlConnectingFalse() {
		whenever(spotifyConnector.isSpotifyInstalled()) doReturn true
		whenever(spotifyConnector.hasSupport()) doReturn true
		whenever(spotifyConnector.previousControlSuccess()) doReturn false
		val viewModel = PermissionsModel(notificationListenerState, state, spotifyConnector, mock())
		assertEquals(false, viewModel.hasSpotifyControlPermission.value)
	}

	@Test
	fun testSpotifyControlConnectingTrue() {
		whenever(spotifyConnector.isSpotifyInstalled()) doReturn true
		whenever(spotifyConnector.hasSupport()) doReturn true
		whenever(spotifyConnector.previousControlSuccess()) doReturn true
		val viewModel = PermissionsModel(notificationListenerState, state, spotifyConnector, mock())
		assertEquals(true, viewModel.hasSpotifyControlPermission.value)
	}

	@Test
	fun testSpotifyControlConnected() {
		whenever(spotifyConnector.isSpotifyInstalled()) doReturn true
		whenever(spotifyConnector.hasSupport()) doReturn true
		whenever(spotifyConnector.previousControlSuccess()) doReturn false

		val result = MutableObservable<SpotifyAppController>()
		result.value = mock()
		whenever(spotifyConnector.connect()) doReturn result

		viewModel.updateSpotify()   // tries to connect
		result.listeners.keys.first().invoke(mock())     // should trigger _updateSpotify
		assertEquals(false, viewModel.hasSpotifyControlPermission.value)

		// connection succeeded
		whenever(spotifyConnector.previousControlSuccess()) doReturn true
		result.listeners.keys.first().invoke(mock())     // should trigger _updateSpotify
		assertEquals(true, viewModel.hasSpotifyControlPermission.value)
	}

	@Test
	fun testSpotifyWebApiNotAuthorized() {
		whenever(spotifyConnector.isSpotifyInstalled()) doReturn true
		whenever(spotifyConnector.hasSupport()) doReturn true

		whenever(spotifyAuthStateManager.isAuthorized()) doReturn false

		val result = MutableObservable<SpotifyAppController>()
		result.value = mock()
		whenever(spotifyConnector.connect()) doReturn result

		viewModel.updateSpotify()
		result.listeners.keys.first().invoke(mock())

		assertEquals(false, viewModel.isSpotifyWebApiAuthorized.value)
	}

	@Test
	fun testSpotifyWebApiAuthorized() {
		whenever(spotifyConnector.isSpotifyInstalled()) doReturn true
		whenever(spotifyConnector.hasSupport()) doReturn true

		whenever(spotifyAuthStateManager.isAuthorized()) doReturn true

		val result = MutableObservable<SpotifyAppController>()
		result.value = mock()
		whenever(spotifyConnector.connect()) doReturn result

		viewModel.updateSpotify()
		result.listeners.keys.first().invoke(mock())

		assertEquals(true, viewModel.isSpotifyWebApiAuthorized.value)
	}
}