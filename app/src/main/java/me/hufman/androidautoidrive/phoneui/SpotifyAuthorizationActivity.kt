package me.hufman.androidautoidrive.phoneui

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import androidx.browser.customtabs.CustomTabsIntent
import android.util.Log
import android.widget.Toast
import com.adamratzman.spotify.SpotifyScope
import com.adamratzman.spotify.getSpotifyPkceCodeChallenge
import kotlinx.coroutines.runBlocking
import me.hufman.androidautoidrive.AppSettings
import me.hufman.androidautoidrive.MutableAppSettingsReceiver
import me.hufman.androidautoidrive.R
import me.hufman.androidautoidrive.music.spotify.SpotifyWebApi
import me.hufman.androidautoidrive.music.controllers.SpotifyAppController
import me.hufman.androidautoidrive.music.spotify.SpotifyAuthStateManager
import net.openid.appauth.*
import net.openid.appauth.connectivity.DefaultConnectionBuilder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * Activity that is used to perform the Spotify authorization process and update the [AuthState] with
 * the resulting authorization code. The activity will be closed once the process has either been
 * canceled, failed, or succeeds.
 */
class SpotifyAuthorizationActivity: Activity() {
	companion object {
		const val TAG = "AuthorizationActivity"

		const val REQUEST_CODE_SPOTIFY_LOGIN = 2356

		const val EXTRA_AUTHORIZATION_RESULT = "authorizationResult"
		const val AUTHORIZATION_CANCELED = 1
		const val AUTHORIZATION_SUCCESS = 0
		const val AUTHORIZATION_FAILED = -1

		val CODE_VERIFIER = generateCodeVerifierString()

		private const val AUTHORIZATION_URI = "https://accounts.spotify.com/authorize"
		private const val ACCESS_TOKEN_URI = "https://accounts.spotify.com/api/token"

		/**
		 * Generates a 45 character random alpha numeric string to be used for the code verifier in
		 * the PKCE authentication flow.
		 */
		private fun generateCodeVerifierString(): String {
			val charPool = ('a'..'z') + ('A'..'Z') + ('0'..'9')
			return (1..45).map { charPool[Random.nextInt(0, charPool.size)] }.joinToString("")
		}
	}

	private val authRequest = AtomicReference<AuthorizationRequest>()
	private val authIntent = AtomicReference<CustomTabsIntent>()
	private var authIntentLatch = CountDownLatch(1)
	private val scopes = listOf(
			SpotifyScope.USER_MODIFY_PLAYBACK_STATE.uri,
			SpotifyScope.USER_LIBRARY_READ.uri,
			SpotifyScope.PLAYLIST_MODIFY_PRIVATE.uri,
			SpotifyScope.PLAYLIST_READ_PRIVATE.uri)
	private val lazyAuthService = lazy {
		createAuthorizationService()
	}
	private val authService: AuthorizationService by lazyAuthService
	private val appSettingsReceiver: MutableAppSettingsReceiver by lazy {
		MutableAppSettingsReceiver(applicationContext)
	}
	private val authStateManager: SpotifyAuthStateManager by lazy {
		SpotifyAuthStateManager.getInstance(appSettingsReceiver)
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		initializeAppAuth()
		doAuth()
	}

	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
		super.onActivityResult(requestCode, resultCode, data)
		processAuthorizationCode(requestCode, resultCode, data)
	}

	override fun onDestroy() {
		super.onDestroy()
		if (lazyAuthService.isInitialized()) {
			authService.dispose()
		}
	}

	/**
	 * Performs the authorization request
	 */
	private fun doAuth() {
		Log.d(TAG, "Authorization started")
		try {
			authIntentLatch.await()
		} catch (ex: InterruptedException) {
			Log.w(TAG, "Interrupted while waiting for auth intent")
		}

		val intent = authService.getAuthorizationRequestIntent(
				authRequest.get(),
				authIntent.get())

		startActivityForResult(intent, REQUEST_CODE_SPOTIFY_LOGIN)
	}

	private fun warmUpBrowser() {
		authIntentLatch = CountDownLatch(1)
		Log.d(TAG, "Warming up browser instance for auth request")
		val intentBuilder = authService.createCustomTabsIntentBuilder(authRequest.get().toUri())
		authIntent.set(intentBuilder.build())
		authIntentLatch.countDown()
	}

	private fun createAuthRequest() {
		Log.d(TAG, "Creating auth request")
		val codeChallenge = getSpotifyPkceCodeChallenge(CODE_VERIFIER)
		val codeChallengeMethod = "S256"
		val clientId = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
				.metaData.getString("com.spotify.music.API_KEY", "unavailable")
		val authRequestBuilder = AuthorizationRequest.Builder(
				authStateManager.getAuthorizationServiceConfiguration()!!,
				clientId,
				ResponseTypeValues.CODE,
				Uri.parse(SpotifyAppController.REDIRECT_URI))
				.setScopes(scopes)
				.setCodeVerifier(CODE_VERIFIER, codeChallenge, codeChallengeMethod)
		authRequest.set(authRequestBuilder.build())
	}

	private fun initializeAppAuth() {
		Log.d(TAG, "Initializing AppAuth")
		recreateAuthorizationService()

		if (authStateManager.getAuthorizationServiceConfiguration() == null) {
			val authEndpointUri = Uri.parse(AUTHORIZATION_URI)
			val tokenEndpointUri = Uri.parse(ACCESS_TOKEN_URI)
			val config = AuthorizationServiceConfiguration(authEndpointUri, tokenEndpointUri)
			authStateManager.replaceAuthState(AuthState(config))
		}

		runBlocking {
			createAuthRequest()
			warmUpBrowser()
		}
	}

	private fun recreateAuthorizationService() {
		if (lazyAuthService.isInitialized()) {
			Log.d(TAG, "Discarding existing AuthService instance")
			authService.dispose()
		}
		authRequest.set(null)
		authIntent.set(null)
	}

	private fun createAuthorizationService(): AuthorizationService {
		Log.d(TAG, "Creating authorization service")
		val builder = AppAuthConfiguration.Builder()
		builder.setConnectionBuilder(DefaultConnectionBuilder.INSTANCE)
		return AuthorizationService(this, builder.build())
	}

	private fun processAuthorizationCode(requestCode: Int, resultCode: Int, data: Intent?) {
		if (requestCode == REQUEST_CODE_SPOTIFY_LOGIN) {
			if (resultCode == RESULT_CANCELED) {
				onAuthorizationCanceled()
				return
			}

			data?.let { intent ->
				val response = AuthorizationResponse.fromIntent(intent)
				val ex = AuthorizationException.fromIntent(intent)
				when {
					response?.authorizationCode != null -> {
						authStateManager.updateAuthorizationResponse(response, ex)
						onAuthorizationSucceed()
					}
					ex != null -> {
						onAuthorizationFailed("Authorization flow failed: " + ex.message)
					}
					else -> {
						onAuthorizationFailed("No authorization state retained - reauthorization required")
					}
				}
			}
		}
	}

	/**
	 * Authorized canceled workflow. This is called if the authorization process activity was canceled.
	 */
	private fun onAuthorizationCanceled() {
		Log.d(TAG, "Authorization canceled")
		Toast.makeText(this, getString(R.string.txt_spotify_api_authorization_canceled), Toast.LENGTH_SHORT).show()
		finishActivityWithResult(AUTHORIZATION_CANCELED)
	}

	/**
	 * Authorized failed workflow. This is called if the authorization process failed.
	 */
	private fun onAuthorizationFailed(error: String?) {
		Log.d(TAG, "Authorization failed with the error: $error")
		Toast.makeText(this, getString(R.string.txt_spotify_api_authorization_failed), Toast.LENGTH_SHORT).show()
		finishActivityWithResult(AUTHORIZATION_FAILED)
	}

	/**
	 * Authorization success workflow. The [SpotifyWebApi] web API client instance will be initialized
	 * with the authorized code obtained.
	 */
	private fun onAuthorizationSucceed() {
		Log.d(TAG, "Authorization process completed successfully. AuthState updated")
		clearNotAuthorizedNotification()
		appSettingsReceiver[AppSettings.KEYS.SPOTIFY_SHOW_UNAUTHENTICATED_NOTIFICATION] = "true"
		SpotifyWebApi.getInstance(applicationContext, appSettingsReceiver).initializeWebApi()
		Toast.makeText(this, getString(R.string.txt_spotify_api_authorization_success), Toast.LENGTH_SHORT).show()
		finishActivityWithResult(AUTHORIZATION_SUCCESS)
	}

	/**
	 * Finishes the activity with the specified result constant.
	 */
	private fun finishActivityWithResult(authorizationResult: Int) {
		val returnIntent = Intent()
		returnIntent.putExtra(EXTRA_AUTHORIZATION_RESULT, authorizationResult)
		setResult(RESULT_OK, returnIntent)
		finish()
	}

	/**
	 * Clears the notification that is displayed when the user is not authenticated.
	 */
	private fun clearNotAuthorizedNotification() {
		val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
		notificationManager.cancel(SpotifyWebApi.NOTIFICATION_REQ_ID)
	}
}