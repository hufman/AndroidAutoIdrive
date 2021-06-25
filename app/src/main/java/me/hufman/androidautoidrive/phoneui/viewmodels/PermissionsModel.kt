package me.hufman.androidautoidrive.phoneui.viewmodels

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import me.hufman.androidautoidrive.MutableAppSettingsReceiver
import me.hufman.androidautoidrive.R
import me.hufman.androidautoidrive.music.controllers.SpotifyAppController
import me.hufman.androidautoidrive.music.spotify.SpotifyAuthStateManager
import me.hufman.androidautoidrive.notifications.NotificationListenerServiceImpl

class PermissionsModel(private val notificationListenerState: LiveData<Boolean>,
                       private val permissionsState: PermissionsState,
                       private val spotifyConnector: SpotifyAppController.Connector,
                       private val spotifyAuthStateManager: SpotifyAuthStateManager): ViewModel(), Observer<Boolean> {
	class Factory(val appContext: Context): ViewModelProvider.Factory {
		@Suppress("UNCHECKED_CAST")
		override fun <T : ViewModel?> create(modelClass: Class<T>): T {
			val viewModel = PermissionsModel(NotificationListenerServiceImpl.serviceState,
					PermissionsState(appContext),
					SpotifyAppController.Connector(appContext, false),
					SpotifyAuthStateManager.getInstance(MutableAppSettingsReceiver(appContext)))
			viewModel.subscribe()
			return viewModel as T
		}
	}

	private val _hasNotificationPermission = MutableLiveData(false)
	val hasNotificationPermission: LiveData<Boolean> = _hasNotificationPermission
	private val _hasSmsPermission = MutableLiveData<Boolean>(false)
	val hasSmsPermission: LiveData<Boolean> = _hasSmsPermission
	private val _hasLocationPermission = MutableLiveData<Boolean>(false)
	val hasLocationPermission: LiveData<Boolean> = _hasLocationPermission

	private val _hasSpotify = MutableLiveData(false)
	val hasSpotify: LiveData<Boolean> = _hasSpotify
	private val _hasSpotifyControlPermission = MutableLiveData(spotifyConnector.previousControlSuccess())
	val hasSpotifyControlPermission: LiveData<Boolean> = _hasSpotifyControlPermission
	private val _spotifyErrorHint = MutableLiveData<Context.() -> String> { "" }
	val spotifyHint: LiveData<Context.() -> String> = _spotifyErrorHint

	private val _isSpotifyWebApiAuthorized = MutableLiveData(false)
	val isSpotifyWebApiAuthorized: LiveData<Boolean> = _isSpotifyWebApiAuthorized

	fun update() {
		_hasNotificationPermission.value = notificationListenerState.value == true && permissionsState.hasNotificationPermission
		_hasSmsPermission.value = permissionsState.hasSmsPermission
		_hasLocationPermission.value = permissionsState.hasLocationPermission
	}

	/** Try connecting to Spotify */
	fun updateSpotify() {
		val hasSpotify = spotifyConnector.isSpotifyInstalled() && spotifyConnector.hasSupport()
		_hasSpotify.value = hasSpotify

		_updateSpotifyWeb()

		if (hasSpotify) {
			spotifyConnector.connect().apply {
				subscribe { _updateSpotify(); it?.disconnect() }
			}
		}
	}

	/** Update the Spotify Web auth LiveData from the cached settings */
	fun _updateSpotifyWeb() {
		_isSpotifyWebApiAuthorized.value = spotifyAuthStateManager.isAuthorized()
	}

	/** Update Spotify Control status LiveData from the connection results */
	private fun _updateSpotify() {
		_hasSpotifyControlPermission.value = spotifyConnector.previousControlSuccess()
		val errorName = spotifyConnector.lastError?.javaClass?.simpleName
		val errorMessage = spotifyConnector.lastError?.message
		when(errorName) {
			"CouldNotFindSpotifyApp" -> _spotifyErrorHint.value = { getString(R.string.musicAppNotes_spotify_apiNotFound) }
			"OfflineModeException" -> _spotifyErrorHint.value = { getString(R.string.musicAppNotes_spotify_apiUnavailable) }
			"UserNotAuthorizedException" -> when {
				// no internet
				errorMessage?.contains("AUTHENTICATION_SERVICE_UNAVAILABLE") == true -> {
					_spotifyErrorHint.value = { getString(R.string.musicAppNotes_spotify_apiUnavailable) }
				}
				// user didn't grant access or user cancelled
				errorMessage?.contains("User authorization required") == true -> {
					_spotifyErrorHint.value = { ""}
				}
				// unknown
				else -> {
					_spotifyErrorHint.value = { errorMessage ?: "" }
				}
			}
			else -> _spotifyErrorHint.value = { errorMessage ?: "" }
		}

		_isSpotifyWebApiAuthorized.value = spotifyAuthStateManager.isAuthorized()
	}

	// subscription management
	private fun subscribe() {
		notificationListenerState.observeForever(this)
	}
	private fun unsubscribe() {
		notificationListenerState.removeObserver(this)
	}

	override fun onChanged(t: Boolean?) {
		// an observed LiveData has updated
		update()
	}

	override fun onCleared() {
		unsubscribe()
	}
}

class PermissionsState(private val appContext: Context) {
	val hasNotificationPermission: Boolean
		get() {
			val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(appContext)
			return enabledListeners.contains(appContext.packageName)
		}

	val hasSmsPermission: Boolean
		get() = ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

	val hasLocationPermission: Boolean
		get() = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
}