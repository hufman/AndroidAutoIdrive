package me.hufman.androidautoidrive.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import androidx.core.app.NotificationCompat
import com.adamratzman.spotify.*
import com.adamratzman.spotify.endpoints.client.ClientLibraryApi
import com.adamratzman.spotify.endpoints.client.ClientPlaylistApi
import com.adamratzman.spotify.endpoints.client.ClientSearchApi
import com.adamratzman.spotify.models.*
import com.nhaarman.mockito_kotlin.*
import kotlinx.coroutines.runBlocking
import me.hufman.androidautoidrive.AppSettings
import me.hufman.androidautoidrive.MockAppSettings
import me.hufman.androidautoidrive.MutableAppSettings
import me.hufman.androidautoidrive.R
import me.hufman.androidautoidrive.music.controllers.SpotifyAppController
import me.hufman.androidautoidrive.music.spotify.SpotifyWebApi
import me.hufman.androidautoidrive.music.spotify.SpotifyAuthStateManager
import me.hufman.androidautoidrive.music.spotify.SpotifyClientApiBuilderHelper
import me.hufman.androidautoidrive.music.spotify.SpotifyMusicMetadata
import me.hufman.androidautoidrive.phoneui.SpotifyAuthorizationActivity
import net.openid.appauth.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.internal.util.reflection.FieldSetter
import org.powermock.api.mockito.PowerMockito
import org.powermock.core.classloader.annotations.PrepareForTest
import org.powermock.modules.junit4.PowerMockRunner
import org.powermock.reflect.Whitebox
import java.lang.Exception

@RunWith(PowerMockRunner::class)
@PrepareForTest(SpotifyWebApi::class, SpotifyAppController::class, PendingIntent::class, SpotifyAuthStateManager::class, SpotifyClientApiBuilderHelper::class)
class SpotifyWebApiTest {

	lateinit var context: Context
	lateinit var spotifyAuthStateManager: SpotifyAuthStateManager
	lateinit var spotifyWebApi: SpotifyWebApi
	lateinit var appSettings: MutableAppSettings
	val clientId: String = "clientId"

	@Before
	fun setup()
	{
		context = mock()
		spotifyAuthStateManager = mock()
		appSettings = MockAppSettings()

		PowerMockito.mockStatic(SpotifyAuthStateManager::class.java)
		val companion = PowerMockito.mock(SpotifyAuthStateManager.Companion::class.java)
		Whitebox.setInternalState(SpotifyAuthStateManager::class.java, "Companion", companion)
		PowerMockito.`when`(SpotifyAuthStateManager.getInstance(appSettings)).thenReturn(spotifyAuthStateManager)

		PowerMockito.mockStatic(SpotifyAppController::class.java)
		val spotifyAppControllerCompanion = PowerMockito.mock(SpotifyAppController.Companion::class.java)
		Whitebox.setInternalState(SpotifyAppController::class.java, "Companion", spotifyAppControllerCompanion)
		PowerMockito.`when`(spotifyAppControllerCompanion.getClientId(context)).thenReturn(clientId)

		spotifyWebApi = Whitebox.invokeConstructor(SpotifyWebApi::class.java, context, appSettings)
	}

	@Test
	fun testInitializeWebApi_WebApiExisting()
	{
		val webApi: SpotifyClientApi = mock()
		FieldSetter.setField(spotifyWebApi, spotifyWebApi::class.java.getDeclaredField("webApi"), webApi)

		spotifyWebApi.initializeWebApi()

		assertEquals(webApi, Whitebox.getInternalState(spotifyWebApi, "webApi"))
	}

	@Test
	fun testInitializeWebApi_ValidAccessToken() = runBlocking {
		val accessToken = "validAccessToken"
		val expirationIn: Long = 5
		val refreshToken = "refreshToken"
		val scopeString = "scopeString"
		whenever(spotifyAuthStateManager.getAccessToken()).thenReturn(accessToken)
		whenever(spotifyAuthStateManager.getAccessTokenExpirationIn()).thenReturn(expirationIn)
		whenever(spotifyAuthStateManager.getRefreshToken()).thenReturn(refreshToken)
		whenever(spotifyAuthStateManager.getScopeString()).thenReturn(scopeString)

		val expiresAt: Long = 2
		val token: Token = mock()
		whenever(token.refreshToken).thenReturn(refreshToken)
		whenever(token.accessToken).thenReturn(accessToken)
		whenever(token.expiresAt).thenReturn(expiresAt)
		whenever(token.scopes).thenReturn(listOf(SpotifyScope.USER_LIBRARY_READ))
		PowerMockito.whenNew(Token::class.java).withArguments(accessToken, "Bearer", expirationIn.toInt(), refreshToken, scopeString).thenReturn(token)

		val webApi: SpotifyClientApi = mock()
		whenever(webApi.token).thenReturn(token)

		val apiBuilder: SpotifyClientApiBuilder = mock()
		whenever(apiBuilder.options).doAnswer { mock() }
		whenever(apiBuilder.build()).thenReturn(webApi)

		PowerMockito.mockStatic(SpotifyClientApiBuilderHelper::class.java)
		val companion = PowerMockito.mock(SpotifyClientApiBuilderHelper.Companion::class.java)
		Whitebox.setInternalState(SpotifyClientApiBuilderHelper::class.java, "Companion", companion)
		PowerMockito.`when`(companion.createApiBuilderWithAccessToken(clientId, token)).thenReturn(apiBuilder)

		doNothing().whenever(spotifyAuthStateManager).updateTokenResponseWithToken(token, clientId)

		val handler: Handler = mock()
		PowerMockito.whenNew(Handler::class.java).withNoArguments().thenReturn(handler)
		val musicAppDiscovery: MusicAppDiscovery = mock()
		PowerMockito.whenNew(MusicAppDiscovery::class.java).withArguments(context, handler).thenReturn(musicAppDiscovery)

		spotifyWebApi.initializeWebApi()

		verify(spotifyAuthStateManager).updateTokenResponseWithToken(token, clientId)
	}

	@Test
	fun testInitializeWebApi_InvalidAccessToken() = runBlocking {
		val accessToken = "inValidAccessToken"
		val expirationIn: Long = 5
		val refreshToken = "refreshToken"
		val scopeString = "scopeString"
		whenever(spotifyAuthStateManager.getAccessToken()).thenReturn(accessToken)
		whenever(spotifyAuthStateManager.getAccessTokenExpirationIn()).thenReturn(expirationIn)
		whenever(spotifyAuthStateManager.getRefreshToken()).thenReturn(refreshToken)
		whenever(spotifyAuthStateManager.getScopeString()).thenReturn(scopeString)

		val expiresAt: Long = 2
		val token: Token = mock()
		whenever(token.refreshToken).thenReturn(refreshToken)
		whenever(token.accessToken).thenReturn(accessToken)
		whenever(token.expiresAt).thenReturn(expiresAt)
		whenever(token.scopes).thenReturn(listOf(SpotifyScope.USER_LIBRARY_READ))
		PowerMockito.whenNew(Token::class.java).withArguments(accessToken, "Bearer", expirationIn.toInt(), refreshToken, scopeString).thenReturn(token)

		val exception = SpotifyException.AuthenticationException("message")
		val apiBuilder: SpotifyClientApiBuilder = mock()
		whenever(apiBuilder.options).doAnswer { mock() }
		whenever(apiBuilder.build()).doAnswer { throw exception }

		PowerMockito.mockStatic(SpotifyClientApiBuilderHelper::class.java)
		val companion = PowerMockito.mock(SpotifyClientApiBuilderHelper.Companion::class.java)
		Whitebox.setInternalState(SpotifyClientApiBuilderHelper::class.java, "Companion", companion)
		PowerMockito.`when`(companion.createApiBuilderWithAccessToken(clientId, token)).thenReturn(apiBuilder)

		doNothing().whenever(spotifyAuthStateManager).addAccessTokenAuthorizationException(exception)

		val notificationManager: NotificationManager = mock()
		whenever(context.getSystemService(NotificationManager::class.java)).thenReturn(notificationManager)

		spotifyWebApi.initializeWebApi()

		verify(spotifyAuthStateManager).addAccessTokenAuthorizationException(exception)
		verify(notificationManager, never()).notify(any(), any())
	}

	@Test
	fun testInitializeWebApi_InvalidAccessToken_ExceptionThrown() = runBlocking {
		val accessToken = "validAccessToken"
		val expirationIn: Long = 5
		val refreshToken = "refreshToken"
		val scopeString = "scopeString"
		whenever(spotifyAuthStateManager.getAccessToken()).thenReturn(accessToken)
		whenever(spotifyAuthStateManager.getAccessTokenExpirationIn()).thenReturn(expirationIn)
		whenever(spotifyAuthStateManager.getRefreshToken()).thenReturn(refreshToken)
		whenever(spotifyAuthStateManager.getScopeString()).thenReturn(scopeString)

		val expiresAt: Long = 2
		val token: Token = mock()
		whenever(token.refreshToken).thenReturn(refreshToken)
		whenever(token.accessToken).thenReturn(accessToken)
		whenever(token.expiresAt).thenReturn(expiresAt)
		whenever(token.scopes).thenReturn(listOf(SpotifyScope.USER_LIBRARY_READ))
		PowerMockito.whenNew(Token::class.java).withArguments(accessToken, "Bearer", expirationIn.toInt(), refreshToken, scopeString).thenReturn(token)

		val exception: Exception = mock()
		val apiBuilder: SpotifyClientApiBuilder = mock()
		whenever(apiBuilder.options).doAnswer { mock() }
		whenever(apiBuilder.build()).doAnswer { throw exception }

		PowerMockito.mockStatic(SpotifyClientApiBuilderHelper::class.java)
		val companion = PowerMockito.mock(SpotifyClientApiBuilderHelper.Companion::class.java)
		Whitebox.setInternalState(SpotifyClientApiBuilderHelper::class.java, "Companion", companion)
		PowerMockito.`when`(companion.createApiBuilderWithAccessToken(clientId, token)).thenReturn(apiBuilder)

		val notificationManager: NotificationManager = mock()
		whenever(context.getSystemService(NotificationManager::class.java)).thenReturn(notificationManager)

		spotifyWebApi.initializeWebApi()

		verify(notificationManager, never()).notify(any(), any())
		verify(spotifyAuthStateManager, never()).addAccessTokenAuthorizationException(any())
		verify(spotifyAuthStateManager, never()).updateTokenResponseWithToken(token, clientId)
	}

	@Test
	fun testInitializeWebApi_NullAuthorizationCode() {
		whenever(spotifyAuthStateManager.getAccessToken()).thenReturn(null)
		whenever(spotifyAuthStateManager.getAuthorizationCode()).thenReturn(null)

		val notificationManager: NotificationManager = mock()
		whenever(context.getSystemService(NotificationManager::class.java)).thenReturn(notificationManager)

		spotifyWebApi.initializeWebApi()

		verify(notificationManager, never()).notify(any(), any())
	}

	@Test
	fun testInitializeWebApi_ValidAuthorizationCode() = runBlocking {
		val authorizationCode = "authorizationCode"
		whenever(spotifyAuthStateManager.getAccessToken()).thenReturn(null)
		whenever(spotifyAuthStateManager.getAuthorizationCode()).thenReturn(authorizationCode)

		val refreshToken = "refreshToken"
		val accessToken = "accessToken"
		val expiresAt: Long = 2
		val token: Token = mock()
		whenever(token.refreshToken).thenReturn(refreshToken)
		whenever(token.accessToken).thenReturn(accessToken)
		whenever(token.expiresAt).thenReturn(expiresAt)
		whenever(token.scopes).thenReturn(listOf(SpotifyScope.USER_LIBRARY_READ))

		val webApi: SpotifyClientApi = mock()
		whenever(webApi.token).thenReturn(token)

		val apiBuilder: SpotifyClientApiBuilder = mock()
		whenever(apiBuilder.options).doAnswer { mock() }
		whenever(apiBuilder.build()).thenReturn(webApi)

		PowerMockito.mockStatic(SpotifyClientApiBuilderHelper::class.java)
		val companion = PowerMockito.mock(SpotifyClientApiBuilderHelper.Companion::class.java)
		Whitebox.setInternalState(SpotifyClientApiBuilderHelper::class.java, "Companion", companion)
		PowerMockito.`when`(companion.createApiBuilderWithAuthorizationCode(clientId, authorizationCode)).thenReturn(apiBuilder)

		val authorizationServiceConfig: AuthorizationServiceConfiguration = mock()
		whenever(spotifyAuthStateManager.getAuthorizationServiceConfiguration()).thenReturn(authorizationServiceConfig)

		doNothing().whenever(spotifyAuthStateManager).updateTokenResponseWithToken(token, clientId)

		val handler: Handler = mock()
		PowerMockito.whenNew(Handler::class.java).withNoArguments().thenReturn(handler)
		val musicAppDiscovery: MusicAppDiscovery = mock()
		PowerMockito.whenNew(MusicAppDiscovery::class.java).withArguments(context, handler).thenReturn(musicAppDiscovery)

		spotifyWebApi.initializeWebApi()

		verify(spotifyAuthStateManager).updateTokenResponseWithToken(token, clientId)
	}

	@Test
	fun testInitializeWebApi_InvalidAuthorizationCode() = runBlocking {
		val authorizationCode = "authorizationCode"
		whenever(spotifyAuthStateManager.getAccessToken()).thenReturn(null)
		whenever(spotifyAuthStateManager.getAuthorizationCode()).thenReturn(authorizationCode)

		val exception = SpotifyException.AuthenticationException("message")
		val apiBuilder: SpotifyClientApiBuilder = mock()
		whenever(apiBuilder.options).doAnswer { mock() }
		whenever(apiBuilder.build()).doAnswer { throw exception }

		PowerMockito.mockStatic(SpotifyClientApiBuilderHelper::class.java)
		val companion = PowerMockito.mock(SpotifyClientApiBuilderHelper.Companion::class.java)
		Whitebox.setInternalState(SpotifyClientApiBuilderHelper::class.java, "Companion", companion)
		PowerMockito.`when`(companion.createApiBuilderWithAuthorizationCode(clientId, authorizationCode)).thenReturn(apiBuilder)

		doNothing().whenever(spotifyAuthStateManager).addAuthorizationCodeAuthorizationException(exception)

		val notificationManager: NotificationManager = mock()
		whenever(context.getSystemService(NotificationManager::class.java)).thenReturn(notificationManager)

		spotifyWebApi.initializeWebApi()

		verify(spotifyAuthStateManager).addAuthorizationCodeAuthorizationException(exception)
		verify(notificationManager, never()).notify(any(), any())
	}

	@Test
	fun testInitializeWebApi_InvalidAuthorizationCode_ExceptionThrown() = runBlocking {
		val authorizationCode = "authorizationCode"
		whenever(spotifyAuthStateManager.getAccessToken()).thenReturn(null)
		whenever(spotifyAuthStateManager.getAuthorizationCode()).thenReturn(authorizationCode)

		val exception: Exception = mock()
		val apiBuilder: SpotifyClientApiBuilder = mock()
		whenever(apiBuilder.options).doAnswer { mock() }
		whenever(apiBuilder.build()).doAnswer { throw exception }

		PowerMockito.mockStatic(SpotifyClientApiBuilderHelper::class.java)
		val companion = PowerMockito.mock(SpotifyClientApiBuilderHelper.Companion::class.java)
		Whitebox.setInternalState(SpotifyClientApiBuilderHelper::class.java, "Companion", companion)
		PowerMockito.`when`(companion.createApiBuilderWithAuthorizationCode(clientId, authorizationCode)).thenReturn(apiBuilder)

		val notificationManager: NotificationManager = mock()
		whenever(context.getSystemService(NotificationManager::class.java)).thenReturn(notificationManager)

		spotifyWebApi.initializeWebApi()

		verify(notificationManager, never()).notify(any(), any())
		verify(spotifyAuthStateManager, never()).addAuthorizationCodeAuthorizationException(any())
		verify(spotifyAuthStateManager, never()).updateTokenResponseWithToken(any(), any())
	}

	@Test
	fun testInitializeWebApi_GetLikedSongsAttemptedTrue() = runBlocking {
		val authorizationCode = "authorizationCode"
		whenever(spotifyAuthStateManager.getAccessToken()).thenReturn(null)
		whenever(spotifyAuthStateManager.getAuthorizationCode()).thenReturn(authorizationCode)

		val refreshToken = "refreshToken"
		val accessToken = "accessToken"
		val expiresAt: Long = 2
		val token: Token = mock()
		whenever(token.refreshToken).thenReturn(refreshToken)
		whenever(token.accessToken).thenReturn(accessToken)
		whenever(token.expiresAt).thenReturn(expiresAt)
		whenever(token.scopes).thenReturn(listOf(SpotifyScope.USER_LIBRARY_READ))

		val webApi: SpotifyClientApi = mock()
		whenever(webApi.token).thenReturn(token)

		val apiBuilder: SpotifyClientApiBuilder = mock()
		whenever(apiBuilder.options).doAnswer { mock() }
		whenever(apiBuilder.build()).thenReturn(webApi)

		PowerMockito.mockStatic(SpotifyClientApiBuilderHelper::class.java)
		val companion = PowerMockito.mock(SpotifyClientApiBuilderHelper.Companion::class.java)
		Whitebox.setInternalState(SpotifyClientApiBuilderHelper::class.java, "Companion", companion)
		PowerMockito.`when`(companion.createApiBuilderWithAuthorizationCode(clientId, authorizationCode)).thenReturn(apiBuilder)

		val authorizationServiceConfig: AuthorizationServiceConfiguration = mock()
		whenever(spotifyAuthStateManager.getAuthorizationServiceConfiguration()).thenReturn(authorizationServiceConfig)

		doNothing().whenever(spotifyAuthStateManager).updateTokenResponseWithToken(token, clientId)

		FieldSetter.setField(spotifyWebApi, spotifyWebApi::class.java.getDeclaredField("getLikedSongsAttempted"), true)

		val spotifyAppController: SpotifyAppController = mock()
		doNothing().whenever(spotifyAppController).createLikedSongsQueueMetadata()
		FieldSetter.setField(spotifyWebApi, spotifyWebApi::class.java.getDeclaredField("spotifyAppControllerCaller"), spotifyAppController)

		val handler: Handler = mock()
		PowerMockito.whenNew(Handler::class.java).withNoArguments().thenReturn(handler)
		val musicAppDiscovery: MusicAppDiscovery = mock()
		PowerMockito.whenNew(MusicAppDiscovery::class.java).withArguments(context, handler).thenReturn(musicAppDiscovery)

		spotifyWebApi.initializeWebApi()

		verify(spotifyAuthStateManager).updateTokenResponseWithToken(token, clientId)
		verify(spotifyAppController).createLikedSongsQueueMetadata()
	}

	@Test
	fun testInitializeWebApi_NullAuthenticationCode_ShowUnauthenticatedNotificationSettingTrue() {
		whenever(spotifyAuthStateManager.getAccessToken()).thenReturn(null)
		whenever(spotifyAuthStateManager.getAuthorizationCode()).thenReturn(null)

		appSettings[AppSettings.KEYS.SPOTIFY_SHOW_UNAUTHENTICATED_NOTIFICATION] = "true"

		val notifyIntent: Intent = mock()
		PowerMockito.whenNew(Intent::class.java).withArguments(context, SpotifyAuthorizationActivity::class.java).thenReturn(notifyIntent)

		val getStringText = "string"
		whenever(context.getString(any())).thenReturn(getStringText)

		val contentIntent: PendingIntent = mock()
		PowerMockito.mockStatic(PendingIntent::class.java)
		PowerMockito.`when`(PendingIntent.getActivity(context, SpotifyWebApi.NOTIFICATION_REQ_ID, notifyIntent, PendingIntent.FLAG_UPDATE_CURRENT)).thenAnswer { contentIntent }

		val notification: Notification = mock()
		val notificationBuilder: NotificationCompat.Builder = mock()
		whenever(notificationBuilder.setContentTitle(getStringText)).thenReturn(notificationBuilder)
		whenever(notificationBuilder.setContentText(getStringText)).thenReturn(notificationBuilder)
		whenever(notificationBuilder.setSmallIcon(anyInt())).thenReturn(notificationBuilder)
		whenever(notificationBuilder.setContentIntent(contentIntent)).thenReturn(notificationBuilder)
		whenever(notificationBuilder.build()).thenReturn(notification)
		PowerMockito.whenNew(NotificationCompat.Builder::class.java).withArguments(context, SpotifyWebApi.NOTIFICATION_CHANNEL_ID).thenReturn(notificationBuilder)

		val notificationChannel: NotificationChannel = mock()
		PowerMockito.whenNew(NotificationChannel::class.java).withArguments(SpotifyWebApi.NOTIFICATION_CHANNEL_ID, "Spotify Authorization", NotificationManager.IMPORTANCE_HIGH).thenReturn(notificationChannel)

		val notificationManager: NotificationManager = mock()
		doNothing().whenever(notificationManager).createNotificationChannel(notificationChannel)
		whenever(context.getString(R.string.notification_channel_spotify)).thenReturn("Spotify Authorization")
		whenever(context.getSystemService(NotificationManager::class.java)).thenReturn(notificationManager)

		doNothing().whenever(notificationManager).notify(SpotifyWebApi.NOTIFICATION_REQ_ID, notification)

		spotifyWebApi.initializeWebApi()

		verify(notificationManager).createNotificationChannel(notificationChannel)
		verify(notificationManager).notify(SpotifyWebApi.NOTIFICATION_REQ_ID, notification)
	}

	@Test
	fun testInitializeWebApi_MusicAppDiscoveryNotSearchable() = runBlocking {
		val accessToken = "validAccessToken"
		val expirationIn: Long = 5
		val refreshToken = "refreshToken"
		val scopeString = "scopeString"
		whenever(spotifyAuthStateManager.getAccessToken()).thenReturn(accessToken)
		whenever(spotifyAuthStateManager.getAccessTokenExpirationIn()).thenReturn(expirationIn)
		whenever(spotifyAuthStateManager.getRefreshToken()).thenReturn(refreshToken)
		whenever(spotifyAuthStateManager.getScopeString()).thenReturn(scopeString)

		val expiresAt: Long = 2
		val token: Token = mock()
		whenever(token.refreshToken).thenReturn(refreshToken)
		whenever(token.accessToken).thenReturn(accessToken)
		whenever(token.expiresAt).thenReturn(expiresAt)
		whenever(token.scopes).thenReturn(listOf(SpotifyScope.USER_LIBRARY_READ))
		PowerMockito.whenNew(Token::class.java).withArguments(accessToken, "Bearer", expirationIn.toInt(), refreshToken, scopeString).thenReturn(token)

		val webApi: SpotifyClientApi = mock()
		whenever(webApi.token).thenReturn(token)

		val apiBuilder: SpotifyClientApiBuilder = mock()
		whenever(apiBuilder.options).doAnswer { mock() }
		whenever(apiBuilder.build()).thenReturn(webApi)

		PowerMockito.mockStatic(SpotifyClientApiBuilderHelper::class.java)
		val companion = PowerMockito.mock(SpotifyClientApiBuilderHelper.Companion::class.java)
		Whitebox.setInternalState(SpotifyClientApiBuilderHelper::class.java, "Companion", companion)
		PowerMockito.`when`(companion.createApiBuilderWithAccessToken(clientId, token)).thenReturn(apiBuilder)

		doNothing().whenever(spotifyAuthStateManager).updateTokenResponseWithToken(token, clientId)

		val handler: Handler = mock()
		PowerMockito.whenNew(Handler::class.java).withNoArguments().thenReturn(handler)
		val spotifyMusicApp = MusicAppInfo("Spotify", mock(), "com.spotify.music", null)
		spotifyMusicApp.searchable = false
		val musicAppInfoList = listOf(spotifyMusicApp)
		val musicAppDiscovery: MusicAppDiscovery = mock()
		doNothing().whenever(musicAppDiscovery).loadInstalledMusicApps()
		whenever(musicAppDiscovery.allApps).thenReturn(musicAppInfoList)
		PowerMockito.whenNew(MusicAppDiscovery::class.java).withArguments(context, handler).thenReturn(musicAppDiscovery)

		spotifyWebApi.initializeWebApi()

		verify(spotifyAuthStateManager).updateTokenResponseWithToken(token, clientId)
		verify(musicAppDiscovery).probeApp(spotifyMusicApp)
	}

	@Test
	fun testInitializeWebApi_MusicAppDiscoverySearchable() = runBlocking {
		val accessToken = "validAccessToken"
		val expirationIn: Long = 5
		val refreshToken = "refreshToken"
		val scopeString = "scopeString"
		whenever(spotifyAuthStateManager.getAccessToken()).thenReturn(accessToken)
		whenever(spotifyAuthStateManager.getAccessTokenExpirationIn()).thenReturn(expirationIn)
		whenever(spotifyAuthStateManager.getRefreshToken()).thenReturn(refreshToken)
		whenever(spotifyAuthStateManager.getScopeString()).thenReturn(scopeString)

		val expiresAt: Long = 2
		val token: Token = mock()
		whenever(token.refreshToken).thenReturn(refreshToken)
		whenever(token.accessToken).thenReturn(accessToken)
		whenever(token.expiresAt).thenReturn(expiresAt)
		whenever(token.scopes).thenReturn(listOf(SpotifyScope.USER_LIBRARY_READ))
		PowerMockito.whenNew(Token::class.java).withArguments(accessToken, "Bearer", expirationIn.toInt(), refreshToken, scopeString).thenReturn(token)

		val webApi: SpotifyClientApi = mock()
		whenever(webApi.token).thenReturn(token)

		val apiBuilder: SpotifyClientApiBuilder = mock()
		whenever(apiBuilder.options).doAnswer { mock() }
		whenever(apiBuilder.build()).thenReturn(webApi)

		PowerMockito.mockStatic(SpotifyClientApiBuilderHelper::class.java)
		val companion = PowerMockito.mock(SpotifyClientApiBuilderHelper.Companion::class.java)
		Whitebox.setInternalState(SpotifyClientApiBuilderHelper::class.java, "Companion", companion)
		PowerMockito.`when`(companion.createApiBuilderWithAccessToken(clientId, token)).thenReturn(apiBuilder)

		doNothing().whenever(spotifyAuthStateManager).updateTokenResponseWithToken(token, clientId)

		val handler: Handler = mock()
		PowerMockito.whenNew(Handler::class.java).withNoArguments().thenReturn(handler)
		val spotifyMusicApp = MusicAppInfo("Spotify", mock(), "com.spotify.music", null)
		spotifyMusicApp.searchable = true
		val musicAppInfoList = listOf(spotifyMusicApp)
		val musicAppDiscovery: MusicAppDiscovery = mock()
		doNothing().whenever(musicAppDiscovery).loadInstalledMusicApps()
		whenever(musicAppDiscovery.allApps).thenReturn(musicAppInfoList)
		PowerMockito.whenNew(MusicAppDiscovery::class.java).withArguments(context, handler).thenReturn(musicAppDiscovery)

		spotifyWebApi.initializeWebApi()

		verify(spotifyAuthStateManager).updateTokenResponseWithToken(token, clientId)
		verify(musicAppDiscovery, never()).probeApp(spotifyMusicApp)
	}

	@Test
	fun testDisconnect() {
		val webApi: SpotifyClientApi = mock()
		doNothing().whenever(webApi).shutdown()
		FieldSetter.setField(spotifyWebApi, spotifyWebApi::class.java.getDeclaredField("webApi"), webApi)

		spotifyWebApi.isUsingSpotify = true

		spotifyWebApi.disconnect()

		verify(webApi).shutdown()
		assertEquals(false, spotifyWebApi.isUsingSpotify)
	}

	@Test
	fun testGetLikedSongs_NullWebApi() = runBlocking {
		FieldSetter.setField(spotifyWebApi, spotifyWebApi::class.java.getDeclaredField("webApi"), null)

		val spotifyAppController: SpotifyAppController = mock()
		val likedSongs = spotifyWebApi.getLikedSongs(spotifyAppController)

		assertEquals(emptyList<SpotifyMusicMetadata>(), likedSongs)
	}

	@Test
	fun testGetLikedSongs_Success() = runBlocking {
		val uriId1 = "uriId1"
		val trackName1 = "Track 1"
		val artistName1 = "Artist 1"
		val albumName1 = "Album 1"
		val coverArtCode1 = "/coverArtCode1"

		val uriId2 = "uriId2"
		val trackName2 = "Track 2"
		val artistName2 = "Artist 2"
		val albumName2 = "Album 2"
		val coverArtCode2 = "/coverArtCode2"

		val savedTracks = listOf(
				createSavedTrack(uriId1, trackName1, artistName1, albumName1, coverArtCode1),
				createSavedTrack(uriId2, trackName2, artistName2, albumName2, coverArtCode2, 600)
		)

		val pagingObject: PagingObject<SavedTrack> = mock()
		whenever(pagingObject.getAllItemsNotNull()).thenReturn(savedTracks)

		val clientLibraryApi: ClientLibraryApi = mock()
		whenever(clientLibraryApi.getSavedTracks(50)).doAnswer { pagingObject }

		val webApi: SpotifyClientApi = mock()
		whenever(webApi.library).thenReturn(clientLibraryApi)
		FieldSetter.setField(spotifyWebApi, spotifyWebApi::class.java.getDeclaredField("webApi"), webApi)

		val spotifyAppController: SpotifyAppController = mock()
		val likedSongs = spotifyWebApi.getLikedSongs(spotifyAppController)

		assertNotNull(likedSongs)
		assertEquals(2, likedSongs!!.size)

		val metadata1 = createSpotifyMusicMetadata(spotifyAppController, "spotify:track:${uriId1}", coverArtCode1, artistName1, albumName1, trackName1, null, false, false)
		assertEquals(metadata1, likedSongs[0])

		val metadata2 = createSpotifyMusicMetadata(spotifyAppController, "spotify:track:${uriId2}", null, artistName2, albumName2, trackName2, null, false, false)
		assertEquals(metadata2, likedSongs[1])
	}

	@Test
	fun testGetLikedSongs_AuthenticationException() = runBlocking {
		val exception = SpotifyException.AuthenticationException("message")
		val clientLibraryApi: ClientLibraryApi = mock()
		whenever(clientLibraryApi.getSavedTracks(50)).doAnswer { throw exception }

		val webApi: SpotifyClientApi = mock()
		whenever(webApi.library).thenReturn(clientLibraryApi)
		FieldSetter.setField(spotifyWebApi, spotifyWebApi::class.java.getDeclaredField("webApi"), webApi)

		doNothing().whenever(spotifyAuthStateManager).addAccessTokenAuthorizationException(exception)

		val notificationManager: NotificationManager = mock()
		whenever(context.getSystemService(NotificationManager::class.java)).thenReturn(notificationManager)

		val spotifyAppController: SpotifyAppController = mock()
		val likedSongs = spotifyWebApi.getLikedSongs(spotifyAppController)

		verify(notificationManager, never()).notify(any(), any())

		assertEquals(null, likedSongs)
		val internalWebApi: SpotifyClientApi? = Whitebox.getInternalState(spotifyWebApi, "webApi")
		assertNull(internalWebApi)
	}

	@Test
	fun testGetLikedSongs_SpotifyException() = runBlocking {
		val exception = SpotifyException.BadRequestException("message")
		val clientLibraryApi: ClientLibraryApi = mock()
		whenever(clientLibraryApi.getSavedTracks(50)).doAnswer { throw exception }

		val webApi: SpotifyClientApi = mock()
		whenever(webApi.library).thenReturn(clientLibraryApi)
		FieldSetter.setField(spotifyWebApi, spotifyWebApi::class.java.getDeclaredField("webApi"), webApi)

		val spotifyAppController: SpotifyAppController = mock()
		val likedSongs = spotifyWebApi.getLikedSongs(spotifyAppController)

		assertEquals(null, likedSongs)
	}

	@Test
	fun testIsAuthorized() {
		whenever(spotifyAuthStateManager.isAuthorized()).thenReturn(true)

		assertEquals(true, spotifyWebApi.isAuthorized())
	}

	@Test
	fun testSearchForQuery_NullWebApi() = runBlocking {
		val searchResults = spotifyWebApi.searchForQuery(mock(), "")

		assertEquals(emptyList<SpotifyMusicMetadata>(), searchResults)
	}

	@Test
	fun testSearchForQuery_AlbumResults() = runBlocking {
		val query = "query"

		val uriId1 = "uriId1"
		val artistName1 = "Artist 1"
		val albumName1 = "Album 1"
		val coverArtCode1 = "/coverArtCode1"

		val uriId2 = "uriId2"
		val artistName2 = "Artist 2"
		val albumName2 = "Album 2"
		val coverArtCode2 = "/coverArtCode2"

		val album1 = createSimpleAlbum(uriId1, artistName1, albumName1, coverArtCode1)
		val album2 = createSimpleAlbum(uriId2, artistName2, albumName2, coverArtCode2)

		val pagingObject: PagingObject<SimpleAlbum> = mock()
		whenever(pagingObject.items).thenReturn(listOf(album1, album2))

		val spotifySearchResult: SpotifySearchResult = mock()
		whenever(spotifySearchResult.albums).doAnswer { pagingObject }

		val clientSearchApi: ClientSearchApi = mock()
		whenever(clientSearchApi.searchAllTypes(query, 8)).doAnswer { spotifySearchResult }

		val webApi: SpotifyClientApi = mock()
		whenever(webApi.search).thenReturn(clientSearchApi)
		FieldSetter.setField(spotifyWebApi, spotifyWebApi::class.java.getDeclaredField("webApi"), webApi)

		val spotifyAppController: SpotifyAppController = mock()
		val searchResults = spotifyWebApi.searchForQuery(spotifyAppController, query)

		assertEquals(2, searchResults.size)
		val metadata1 = createSpotifyMusicMetadata(spotifyAppController, "spotify:album:${uriId1}", coverArtCode1, artistName1, albumName1, albumName1, "Album", true, false)
		assertEquals(metadata1, searchResults[0])
		val metadata2 = createSpotifyMusicMetadata(spotifyAppController, "spotify:album:${uriId2}", coverArtCode2, artistName2, albumName2, albumName2, "Album", true, false)
		assertEquals(metadata2, searchResults[1])
	}

	@Test
	fun testSearchForQuery_SongResults() = runBlocking {
		val query = "query"

		val uriId1 = "uriId1"
		val albumUriId1 = "albumUriId1"
		val artistName1 = "Artist 1"
		val trackName1 = "Track 1"
		val coverArtCode1 = "/coverArtCode1"

		val uriId2 = "uriId2"
		val albumUriId2 = "albumUriId2"
		val artistName2 = "Artist 2"
		val trackName2 = "Track 2"
		val coverArtCode2 = "/coverArtCode2"

		val track1 = createTrack(uriId1, albumUriId1, coverArtCode1, artistName1, trackName1)
		val track2 = createTrack(uriId2, albumUriId2, coverArtCode2, artistName2, trackName2)

		val pagingObject: PagingObject<Track> = mock()
		whenever(pagingObject.items).thenReturn(listOf(track1, track2))

		val spotifySearchResult: SpotifySearchResult = mock()
		whenever(spotifySearchResult.tracks).doAnswer { pagingObject }

		val clientSearchApi: ClientSearchApi = mock()
		whenever(clientSearchApi.searchAllTypes(query, 8)).doAnswer { spotifySearchResult }

		val webApi: SpotifyClientApi = mock()
		whenever(webApi.search).thenReturn(clientSearchApi)
		FieldSetter.setField(spotifyWebApi, spotifyWebApi::class.java.getDeclaredField("webApi"), webApi)

		val spotifyAppController: SpotifyAppController = mock()
		val searchResults = spotifyWebApi.searchForQuery(spotifyAppController, query)

		assertEquals(2, searchResults.size)
		val metadata1 = createSpotifyMusicMetadata(spotifyAppController, "spotify:track:${uriId1}", coverArtCode1, artistName1, "spotify:album:${albumUriId1}", trackName1, "Track", true, false)
		assertEquals(metadata1, searchResults[0])
		val metadata2 = createSpotifyMusicMetadata(spotifyAppController, "spotify:track:${uriId2}", coverArtCode2, artistName2, "spotify:album:${albumUriId2}", trackName2, "Track", true, false)
		assertEquals(metadata2, searchResults[1])
	}

	@Test
	fun testSearchForQuery_ArtistResults() = runBlocking {
		val query = "query"

		val uriId1 = "uriId1"
		val artistName1 = "Artist 1"
		val coverArtCode1 = "/coverArtCode1"

		val uriId2 = "uriId2"
		val artistName2 = "Artist 2"
		val coverArtCode2 = "/coverArtCode2"

		val artist1 = createArtist(uriId1, coverArtCode1, artistName1)
		val artist2 = createArtist(uriId2, coverArtCode2, artistName2)

		val pagingObject: PagingObject<Artist> = mock()
		whenever(pagingObject.items).thenReturn(listOf(artist1, artist2))

		val spotifySearchResult: SpotifySearchResult = mock()
		whenever(spotifySearchResult.artists).doAnswer { pagingObject }

		val clientSearchApi: ClientSearchApi = mock()
		whenever(clientSearchApi.searchAllTypes(query, 8)).doAnswer { spotifySearchResult }

		val webApi: SpotifyClientApi = mock()
		whenever(webApi.search).thenReturn(clientSearchApi)
		FieldSetter.setField(spotifyWebApi, spotifyWebApi::class.java.getDeclaredField("webApi"), webApi)

		val spotifyAppController: SpotifyAppController = mock()
		val searchResults = spotifyWebApi.searchForQuery(spotifyAppController, query)

		assertEquals(2, searchResults.size)
		val metadata1 = createSpotifyMusicMetadata(spotifyAppController, "spotify:artist:${uriId1}", coverArtCode1, artistName1, null, artistName1, "Artist", false, true)
		assertEquals(metadata1, searchResults[0])
		val metadata2 = createSpotifyMusicMetadata(spotifyAppController, "spotify:artist:${uriId2}", coverArtCode2, artistName2, null, artistName2, "Artist", false, true)
		assertEquals(metadata2, searchResults[1])
	}

	@Test
	fun testSearchForQuery_ShowResults() = runBlocking {
		val query = "query"

		val uriId1 = "uriId1"
		val showName1 = "Show 1"
		val publisherName1 = "Publisher 1"
		val coverArtCode1 = "/coverArtCode1"

		val uriId2 = "uriId2"
		val showName2 = "Show 2"
		val publisherName2 = "Publisher 2"
		val coverArtCode2 = "/coverArtCode2"

		val show1 = createSimpleShow(uriId1, showName1, publisherName1, coverArtCode1)
		val show2 = createSimpleShow(uriId2, showName2, publisherName2, coverArtCode2)

		val pagingObject: NullablePagingObject<SimpleShow> = mock()
		whenever(pagingObject.items).thenReturn(listOf(show1, show2))

		val spotifySearchResult: SpotifySearchResult = mock()
		whenever(spotifySearchResult.shows).doAnswer { pagingObject }

		val clientSearchApi: ClientSearchApi = mock()
		whenever(clientSearchApi.searchAllTypes(query, 8)).doAnswer { spotifySearchResult }

		val webApi: SpotifyClientApi = mock()
		whenever(webApi.search).thenReturn(clientSearchApi)
		FieldSetter.setField(spotifyWebApi, spotifyWebApi::class.java.getDeclaredField("webApi"), webApi)

		val spotifyAppController: SpotifyAppController = mock()
		val searchResults = spotifyWebApi.searchForQuery(spotifyAppController, query)

		assertEquals(2, searchResults.size)
		val metadata1 = createSpotifyMusicMetadata(spotifyAppController, "spotify:show:${uriId1}", coverArtCode1, publisherName1, null, showName1, "Show", false, true)
		assertEquals(metadata1, searchResults[0])
		val metadata2 = createSpotifyMusicMetadata(spotifyAppController, "spotify:show:${uriId2}", coverArtCode2, publisherName2, null, showName2, "Show", false, true)
		assertEquals(metadata2, searchResults[1])
	}

	@Test
	fun testSearchForQuery_EpisodeResults() = runBlocking {
		val query = "query"

		val uriId1 = "uriId1"
		val episodeName1 = "Episode 1"
		val coverArtCode1 = "/coverArtCode1"

		val uriId2 = "uriId2"
		val episodeName2 = "Episode 2"
		val coverArtCode2 = "/coverArtCode2"

		val episode1 = createSimpleEpisode(uriId1, episodeName1, coverArtCode1)
		val episode2 = createSimpleEpisode(uriId2, episodeName2, coverArtCode2)

		val pagingObject: NullablePagingObject<SimpleEpisode> = mock()
		whenever(pagingObject.items).thenReturn(listOf(episode1, episode2))

		val spotifySearchResult: SpotifySearchResult = mock()
		whenever(spotifySearchResult.episodes).doAnswer { pagingObject }

		val clientSearchApi: ClientSearchApi = mock()
		whenever(clientSearchApi.searchAllTypes(query, 8)).doAnswer { spotifySearchResult }

		val webApi: SpotifyClientApi = mock()
		whenever(webApi.search).thenReturn(clientSearchApi)
		FieldSetter.setField(spotifyWebApi, spotifyWebApi::class.java.getDeclaredField("webApi"), webApi)

		val spotifyAppController: SpotifyAppController = mock()
		val searchResults = spotifyWebApi.searchForQuery(spotifyAppController, query)

		assertEquals(2, searchResults.size)
		val metadata1 = createSpotifyMusicMetadata(spotifyAppController, "spotify:episode:${uriId1}", coverArtCode1, null, null, episodeName1, "Episode", true, false)
		assertEquals(metadata1, searchResults[0])
		val metadata2 = createSpotifyMusicMetadata(spotifyAppController, "spotify:episode:${uriId2}", coverArtCode2, null, null, episodeName2, "Episode", true, false)
		assertEquals(metadata2, searchResults[1])
	}

	@Test
	fun testSearchForQuery_NoResults() = runBlocking {
		val query = "query"

		val spotifySearchResult: SpotifySearchResult = mock()

		val clientSearchApi: ClientSearchApi = mock()
		whenever(clientSearchApi.searchAllTypes(query, 8)).doAnswer { spotifySearchResult }

		val webApi: SpotifyClientApi = mock()
		whenever(webApi.search).thenReturn(clientSearchApi)
		FieldSetter.setField(spotifyWebApi, spotifyWebApi::class.java.getDeclaredField("webApi"), webApi)

		val spotifyAppController: SpotifyAppController = mock()
		val searchResults = spotifyWebApi.searchForQuery(spotifyAppController, query)

		assertTrue(searchResults.isEmpty())
	}

	@Test
	fun testSearchForQuery_OneOfEachResult() = runBlocking {
		val query = "query"

		val albumUriId = "albumUriId"
		val albumArtistName = "album Artist"
		val albumName = "album Album"
		val albumCoverArtCode = "/albumCoverArtCode"
		val album = createSimpleAlbum(albumUriId, albumArtistName, albumName, albumCoverArtCode)
		val albumPagingObject: PagingObject<SimpleAlbum> = mock()
		whenever(albumPagingObject.items).thenReturn(listOf(album))

		val trackUriId = "trackUriId1"
		val trackAlbumUriId = "trackAlbumUriId"
		val trackArtistName = "track Artist 1"
		val trackName = "track Track 1"
		val trackCoverArtCode = "/trackCoverArtCode"
		val track = createTrack(trackUriId, trackAlbumUriId, trackCoverArtCode, trackArtistName, trackName)
		val trackPagingObject: PagingObject<Track> = mock()
		whenever(trackPagingObject.items).thenReturn(listOf(track))

		val artistUriId = "artistUriId"
		val artistName = "artist Artist 1"
		val artistCoverArtCode = "/artistCoverArtCode"
		val artist = createArtist(artistUriId, artistCoverArtCode, artistName)
		val artistPagingObject: PagingObject<Artist> = mock()
		whenever(artistPagingObject.items).thenReturn(listOf(artist))

		val showUriId = "showUriId"
		val showName = "Show 1"
		val showPublisherName = "Publisher 1"
		val showCoverArtCode = "/showCoverArtCode"
		val show = createSimpleShow(showUriId, showName, showPublisherName, showCoverArtCode)
		val showPagingObject: NullablePagingObject<SimpleShow> = mock()
		whenever(showPagingObject.items).thenReturn(listOf(show))

		val episodeUriId = "episodeUriId"
		val episodeName = "Episode 1"
		val episodeCoverArtCode = "/episodeCoverArtCode"
		val episode = createSimpleEpisode(episodeUriId, episodeName, episodeCoverArtCode)
		val episodePagingObject: NullablePagingObject<SimpleEpisode> = mock()
		whenever(episodePagingObject.items).thenReturn(listOf(episode))

		val spotifySearchResult: SpotifySearchResult = mock()
		whenever(spotifySearchResult.albums).doAnswer { albumPagingObject }
		whenever(spotifySearchResult.tracks).doAnswer { trackPagingObject }
		whenever(spotifySearchResult.artists).doAnswer { artistPagingObject }
		whenever(spotifySearchResult.shows).doAnswer { showPagingObject }
		whenever(spotifySearchResult.episodes).doAnswer { episodePagingObject }

		val clientSearchApi: ClientSearchApi = mock()
		whenever(clientSearchApi.searchAllTypes(query, 8)).doAnswer { spotifySearchResult }

		val webApi: SpotifyClientApi = mock()
		whenever(webApi.search).thenReturn(clientSearchApi)
		FieldSetter.setField(spotifyWebApi, spotifyWebApi::class.java.getDeclaredField("webApi"), webApi)

		val spotifyAppController: SpotifyAppController = mock()
		val searchResults = spotifyWebApi.searchForQuery(spotifyAppController, query)

		assertEquals(5, searchResults.size)
		val metadata1 = createSpotifyMusicMetadata(spotifyAppController, "spotify:album:${albumUriId}", albumCoverArtCode, albumArtistName, albumName, albumName, "Album", true, false)
		assertEquals(metadata1, searchResults[0])
		val metadata2 = createSpotifyMusicMetadata(spotifyAppController, "spotify:track:${trackUriId}", trackCoverArtCode, trackArtistName, "spotify:album:${trackAlbumUriId}", trackName, "Track", true, false)
		assertEquals(metadata2, searchResults[1])
		val metadata3 = createSpotifyMusicMetadata(spotifyAppController, "spotify:artist:${artistUriId}", artistCoverArtCode, artistName, null, artistName, "Artist", false, true)
		assertEquals(metadata3, searchResults[2])
		val metadata4 = createSpotifyMusicMetadata(spotifyAppController, "spotify:show:${showUriId}", showCoverArtCode, showPublisherName, null, showName, "Show", false, true)
		assertEquals(metadata4, searchResults[3])
		val metadata5 = createSpotifyMusicMetadata(spotifyAppController, "spotify:episode:${episodeUriId}", episodeCoverArtCode, null, null, episodeName, "Episode", true, false)
		assertEquals(metadata5, searchResults[4])
	}

	@Test
	fun testSearchForQuery_AuthenticationException() = runBlocking {
		val query = "query"

		val exception = SpotifyException.AuthenticationException("message")

		val clientSearchApi: ClientSearchApi = mock()
		whenever(clientSearchApi.searchAllTypes(query, 8)).doAnswer { throw exception }

		val webApi: SpotifyClientApi = mock()
		whenever(webApi.search).thenReturn(clientSearchApi)
		FieldSetter.setField(spotifyWebApi, spotifyWebApi::class.java.getDeclaredField("webApi"), webApi)

		doNothing().whenever(spotifyAuthStateManager).addAccessTokenAuthorizationException(exception)

		val notificationManager: NotificationManager = mock()
		whenever(context.getSystemService(NotificationManager::class.java)).thenReturn(notificationManager)

		val spotifyAppController: SpotifyAppController = mock()
		val searchResults = spotifyWebApi.searchForQuery(spotifyAppController, query)

		assertTrue(searchResults.isEmpty())

		val internalWebApi: SpotifyClientApi? = Whitebox.getInternalState(spotifyWebApi, "webApi")
		assertNull(internalWebApi)

		verify(spotifyAuthStateManager).addAccessTokenAuthorizationException(exception)
		verify(notificationManager, never()).notify(any(), any())
	}

	@Test
	fun testSearchForQuery_SpotifyException() = runBlocking {
		val query = "query"

		val exception = SpotifyException.BadRequestException("message")

		val clientSearchApi: ClientSearchApi = mock()
		whenever(clientSearchApi.searchAllTypes(query, 8)).doAnswer { throw exception }

		val webApi: SpotifyClientApi = mock()
		whenever(webApi.search).thenReturn(clientSearchApi)
		FieldSetter.setField(spotifyWebApi, spotifyWebApi::class.java.getDeclaredField("webApi"), webApi)

		val spotifyAppController: SpotifyAppController = mock()
		val searchResults = spotifyWebApi.searchForQuery(spotifyAppController, query)

		assertTrue(searchResults.isEmpty())
	}

	@Test
	fun testCreatePlaylist() = runBlocking {
		val playlistName = "playlistName"
		val playlistUri: PlaylistUri = mock()
		val playlist: Playlist = mock()
		whenever(playlist.uri) doAnswer { playlistUri }

		val clientPlaylistApi: ClientPlaylistApi = mock()
		whenever(clientPlaylistApi.createClientPlaylist(name = playlistName, description = null, public = false)).doAnswer { playlist }

		val webApi: SpotifyClientApi = mock()
		whenever(webApi.playlists).thenReturn(clientPlaylistApi)
		FieldSetter.setField(spotifyWebApi, spotifyWebApi::class.java.getDeclaredField("webApi"), webApi)

		val playlistCreateResult = spotifyWebApi.createPlaylist(playlistName)

		assertEquals(playlistCreateResult, playlistUri)
	}

	@Test
	fun testCreatePlaylist_AuthenticationException() = runBlocking {
		val playlistName = "playlistName"

		val exception = SpotifyException.AuthenticationException("message")
		doNothing().whenever(spotifyAuthStateManager).addAccessTokenAuthorizationException(exception)

		val notificationManager: NotificationManager = mock()
		whenever(context.getSystemService(NotificationManager::class.java)).thenReturn(notificationManager)

		val clientPlaylistApi: ClientPlaylistApi = mock()
		whenever(clientPlaylistApi.createClientPlaylist(name = playlistName, description = null, public = false)).doAnswer { throw exception }

		val webApi: SpotifyClientApi = mock()
		whenever(webApi.playlists).thenReturn(clientPlaylistApi)
		FieldSetter.setField(spotifyWebApi, spotifyWebApi::class.java.getDeclaredField("webApi"), webApi)

		val playlistCreateResult = spotifyWebApi.createPlaylist(playlistName)

		val internalWebApi: SpotifyClientApi? = Whitebox.getInternalState(spotifyWebApi, "webApi")
		assertNull(internalWebApi)
		assertNull(playlistCreateResult)

		verify(notificationManager, never()).notify(any(), any())
	}

	@Test
	fun testCreatePlaylist_SpotifyException() = runBlocking {
		val playlistName = "playlistName"

		val exception = SpotifyException.BadRequestException("message")

		val clientPlaylistApi: ClientPlaylistApi = mock()
		whenever(clientPlaylistApi.createClientPlaylist(name = playlistName, description = null, public = false)).doAnswer { throw exception }

		val webApi: SpotifyClientApi = mock()
		whenever(webApi.playlists).thenReturn(clientPlaylistApi)
		FieldSetter.setField(spotifyWebApi, spotifyWebApi::class.java.getDeclaredField("webApi"), webApi)

		val playlistCreateResult = spotifyWebApi.createPlaylist(playlistName)

		assertNull(playlistCreateResult)
	}

	@Test
	fun testAddSongsToPlaylist() = runBlocking {
		val playlistId = "playlistId"
		val songs = listOf(
				MusicMetadata("mediaId1", 1, true, false, 5, null, "coverArtUri1", null, "Artist 1", "Album 1", "Title 1"),
				MusicMetadata("mediaId2", 2, true, false, 3, null,"coverArtUri2", null, "Artist 2", "Album 2", "Title 2")
		)

		val clientPlaylistApi: ClientPlaylistApi = mock()
		whenever(clientPlaylistApi.addTracksToClientPlaylist(playlistId, songs[0].mediaId!!, songs[1].mediaId!!)).doAnswer {  }

		val webApi: SpotifyClientApi = mock()
		whenever(webApi.playlists).thenReturn(clientPlaylistApi)
		FieldSetter.setField(spotifyWebApi, spotifyWebApi::class.java.getDeclaredField("webApi"), webApi)

		spotifyWebApi.addSongsToPlaylist(playlistId, songs)

		verify(clientPlaylistApi).addTracksToClientPlaylist(playlistId, songs[0].mediaId!!, songs[1].mediaId!!)
	}

	@Test
	fun testAddSongsToPlaylist_AuthenticationException() = runBlocking {
		val playlistId = "playlistId"
		val songs = listOf(
				MusicMetadata("mediaId1", 1, true, false, 5, null, "coverArtUri1", null, "Artist 1", "Album 1", "Title 1"),
				MusicMetadata("mediaId2", 2, true, false, 3, null,"coverArtUri2", null, "Artist 2", "Album 2", "Title 2")
		)

		val exception = SpotifyException.AuthenticationException("message")
		doNothing().whenever(spotifyAuthStateManager).addAccessTokenAuthorizationException(exception)

		val notificationManager: NotificationManager = mock()
		whenever(context.getSystemService(NotificationManager::class.java)).thenReturn(notificationManager)

		val clientPlaylistApi: ClientPlaylistApi = mock()
		whenever(clientPlaylistApi.addTracksToClientPlaylist(playlistId, songs[0].mediaId!!, songs[1].mediaId!!)).doAnswer { throw exception }

		val webApi: SpotifyClientApi = mock()
		whenever(webApi.playlists).thenReturn(clientPlaylistApi)
		FieldSetter.setField(spotifyWebApi, spotifyWebApi::class.java.getDeclaredField("webApi"), webApi)

		spotifyWebApi.addSongsToPlaylist(playlistId, songs)

		val internalWebApi: SpotifyClientApi? = Whitebox.getInternalState(spotifyWebApi, "webApi")
		assertNull(internalWebApi)

		verify(clientPlaylistApi).addTracksToClientPlaylist(playlistId, songs[0].mediaId!!, songs[1].mediaId!!)
		verify(notificationManager, never()).notify(any(), any())
	}

	@Test
	fun testAddSongsToPlaylist_SpotifyException() = runBlocking {
		val playlistId = "playlistId"
		val songs = listOf(
				MusicMetadata("mediaId1", 1, true, false, 5, null, "coverArtUri1", null, "Artist 1", "Album 1", "Title 1"),
				MusicMetadata("mediaId2", 2, true, false, 3, null,"coverArtUri2", null, "Artist 2", "Album 2", "Title 2")
		)

		val exception = SpotifyException.BadRequestException("message")

		val clientPlaylistApi: ClientPlaylistApi = mock()
		whenever(clientPlaylistApi.addTracksToClientPlaylist(playlistId, songs[0].mediaId!!, songs[1].mediaId!!)).doAnswer { throw exception }

		val webApi: SpotifyClientApi = mock()
		whenever(webApi.playlists).thenReturn(clientPlaylistApi)
		FieldSetter.setField(spotifyWebApi, spotifyWebApi::class.java.getDeclaredField("webApi"), webApi)

		spotifyWebApi.addSongsToPlaylist(playlistId, songs)

		verify(clientPlaylistApi).addTracksToClientPlaylist(playlistId, songs[0].mediaId!!, songs[1].mediaId!!)
	}

	@Test
	fun testReplacePlaylistSongs() = runBlocking {
		val playlistId = "playlistId"
		val songs = listOf(
				MusicMetadata("mediaId1", 1, true, false, 5, null, "coverArtUri1", null, "Artist 1", "Album 1", "Title 1"),
				MusicMetadata("mediaId2", 2, true, false, 3, null,"coverArtUri2", null, "Artist 2", "Album 2", "Title 2")
		)

		val clientPlaylistApi: ClientPlaylistApi = mock()
		whenever(clientPlaylistApi.replaceClientPlaylistTracks(playlistId, songs[0].mediaId!!, songs[1].mediaId!!)).doAnswer {  }

		val webApi: SpotifyClientApi = mock()
		whenever(webApi.playlists).thenReturn(clientPlaylistApi)
		FieldSetter.setField(spotifyWebApi, spotifyWebApi::class.java.getDeclaredField("webApi"), webApi)

		spotifyWebApi.replacePlaylistSongs(playlistId, songs)

		verify(clientPlaylistApi).replaceClientPlaylistTracks(playlistId, songs[0].mediaId!!, songs[1].mediaId!!)
	}

	@Test
	fun testReplacePlaylistSongs_AuthenticationException() = runBlocking {
		val playlistId = "playlistId"
		val songs = listOf(
				MusicMetadata("mediaId1", 1, true, false, 5, null, "coverArtUri1", null, "Artist 1", "Album 1", "Title 1"),
				MusicMetadata("mediaId2", 2, true, false, 3, null,"coverArtUri2", null, "Artist 2", "Album 2", "Title 2")
		)

		val exception = SpotifyException.AuthenticationException("message")
		doNothing().whenever(spotifyAuthStateManager).addAccessTokenAuthorizationException(exception)

		val notificationManager: NotificationManager = mock()
		whenever(context.getSystemService(NotificationManager::class.java)).thenReturn(notificationManager)

		val clientPlaylistApi: ClientPlaylistApi = mock()
		whenever(clientPlaylistApi.replaceClientPlaylistTracks(playlistId, songs[0].mediaId!!, songs[1].mediaId!!)).doAnswer { throw exception }

		val webApi: SpotifyClientApi = mock()
		whenever(webApi.playlists).thenReturn(clientPlaylistApi)
		FieldSetter.setField(spotifyWebApi, spotifyWebApi::class.java.getDeclaredField("webApi"), webApi)

		spotifyWebApi.replacePlaylistSongs(playlistId, songs)

		val internalWebApi: SpotifyClientApi? = Whitebox.getInternalState(spotifyWebApi, "webApi")
		assertNull(internalWebApi)

		verify(clientPlaylistApi).replaceClientPlaylistTracks(playlistId, songs[0].mediaId!!, songs[1].mediaId!!)
		verify(notificationManager, never()).notify(any(), any())
	}

	@Test
	fun testReplacePlaylistSongs_SpotifyException() = runBlocking {
		val playlistId = "playlistId"
		val songs = listOf(
				MusicMetadata("mediaId1", 1, true, false, 5, null, "coverArtUri1", null, "Artist 1", "Album 1", "Title 1"),
				MusicMetadata("mediaId2", 2, true, false, 3, null,"coverArtUri2", null, "Artist 2", "Album 2", "Title 2")
		)

		val exception = SpotifyException.BadRequestException("message")

		val clientPlaylistApi: ClientPlaylistApi = mock()
		whenever(clientPlaylistApi.replaceClientPlaylistTracks(playlistId, songs[0].mediaId!!, songs[1].mediaId!!)).doAnswer { throw exception }

		val webApi: SpotifyClientApi = mock()
		whenever(webApi.playlists).thenReturn(clientPlaylistApi)
		FieldSetter.setField(spotifyWebApi, spotifyWebApi::class.java.getDeclaredField("webApi"), webApi)

		spotifyWebApi.replacePlaylistSongs(playlistId, songs)

		verify(clientPlaylistApi).replaceClientPlaylistTracks(playlistId, songs[0].mediaId!!, songs[1].mediaId!!)
	}

	@Test
	fun testGetPlaylistUri_MatchingPlaylist() = runBlocking {
		val playlistName = "playlistName"
		val uriId = "uriId"
		val playlists = listOf(
				createSimplePlaylist(uriId, playlistName)
		)

		val pagingObject: PagingObject<SimplePlaylist> = mock()
		whenever(pagingObject.items).doAnswer { playlists }

		val clientPlaylistApi: ClientPlaylistApi = mock()
		whenever(clientPlaylistApi.getClientPlaylists(any(), any())).doAnswer { pagingObject }

		val webApi: SpotifyClientApi = mock()
		whenever(webApi.playlists).thenReturn(clientPlaylistApi)
		FieldSetter.setField(spotifyWebApi, spotifyWebApi::class.java.getDeclaredField("webApi"), webApi)

		val playlistUri = spotifyWebApi.getPlaylistUri(playlistName)

		assertNotNull(playlistUri)
		assertEquals(uriId, playlistUri!!.id)
		assertEquals("spotify:playlist:${uriId}", playlistUri.uri)
	}

	@Test
	fun testGetPlaylistUri_NoMatchingPlaylist() = runBlocking {
		val playlistName = "playlistName"
		val uriId = "uriId"
		val playlists = listOf(
				createSimplePlaylist(uriId, playlistName)
		)

		val pagingObject: PagingObject<SimplePlaylist> = mock()
		whenever(pagingObject.items).doAnswer { playlists }

		val clientPlaylistApi: ClientPlaylistApi = mock()
		whenever(clientPlaylistApi.getClientPlaylists(any(), any())).doAnswer { pagingObject }

		val webApi: SpotifyClientApi = mock()
		whenever(webApi.playlists).thenReturn(clientPlaylistApi)
		FieldSetter.setField(spotifyWebApi, spotifyWebApi::class.java.getDeclaredField("webApi"), webApi)

		val playlistUri = spotifyWebApi.getPlaylistUri("other playlist")

		assertNull(playlistUri)
	}

	@Test
	fun testGetPlaylistUri_AuthenticationException() = runBlocking {
		val playlistName = "playlistName"

		val exception = SpotifyException.AuthenticationException("message")
		doNothing().whenever(spotifyAuthStateManager).addAccessTokenAuthorizationException(exception)

		val notificationManager: NotificationManager = mock()
		whenever(context.getSystemService(NotificationManager::class.java)).thenReturn(notificationManager)

		val clientPlaylistApi: ClientPlaylistApi = mock()
		whenever(clientPlaylistApi.getClientPlaylists(any(), any())).doAnswer { throw exception }

		val webApi: SpotifyClientApi = mock()
		whenever(webApi.playlists).thenReturn(clientPlaylistApi)
		FieldSetter.setField(spotifyWebApi, spotifyWebApi::class.java.getDeclaredField("webApi"), webApi)

		val playlistUri = spotifyWebApi.getPlaylistUri(playlistName)

		assertNull(playlistUri)

		val internalWebApi: SpotifyClientApi? = Whitebox.getInternalState(spotifyWebApi, "webApi")
		assertNull(internalWebApi)

		verify(notificationManager, never()).notify(any(), any())
	}

	@Test
	fun testGetPlaylistUri_SpotifyException() = runBlocking {
		val playlistName = "playlistName"

		val exception = SpotifyException.BadRequestException("message")

		val clientPlaylistApi: ClientPlaylistApi = mock()
		whenever(clientPlaylistApi.getClientPlaylists(any(), any())).doAnswer { throw exception }

		val webApi: SpotifyClientApi = mock()
		whenever(webApi.playlists).thenReturn(clientPlaylistApi)
		FieldSetter.setField(spotifyWebApi, spotifyWebApi::class.java.getDeclaredField("webApi"), webApi)

		val playlistUri = spotifyWebApi.getPlaylistUri(playlistName)

		assertNull(playlistUri)
	}

	private fun createSpotifyMusicMetadata(spotifyAppController: SpotifyAppController, uriId: String, coverArtCode: String?, artistName: String?, albumName: String?, trackName: String, subtitle: String?, playable: Boolean, browseable: Boolean): SpotifyMusicMetadata {
		val coverArtUri = if (coverArtCode != null) {
			"spotify:image:${coverArtCode.drop(1)}"
		} else {
			null
		}
		return SpotifyMusicMetadata(spotifyAppController, uriId, uriId.hashCode().toLong(), coverArtUri, artistName, albumName, trackName, subtitle, playable, browseable)
	}

	private fun createSavedTrack(uriId: String, trackName: String, artistName: String, albumName: String, coverArtCode: String, heightToMatch: Int = 300): SavedTrack {
		val images = listOf(SpotifyImage(heightToMatch, coverArtCode, 300))
		val artists = listOf(SimpleArtist(emptyMap(), "href", "id", ArtistUri("artistUri"), artistName, "type"))
		val album = SimpleAlbum("album", emptyList(), emptyMap(), "href", "id", AlbumUri("albumUri"), artists, images, albumName, "type", null, "1950", "year")
		return 	SavedTrack("value", Track(emptyMap(), emptyMap(), emptyList(), "", "", PlayableUri(uriId), album, artists, true, 1, 5, false, null, trackName, 1, null, 1, ""))
	}

	private fun createSimpleAlbum(uriId: String, artistName: String, albumName: String, coverArtCode: String): SimpleAlbum {
		val images = listOf(SpotifyImage(300, coverArtCode, 300))
		val artist = listOf(SimpleArtist(emptyMap(), "href", "id", ArtistUri("artistUri"), artistName, "artist"))
		return SimpleAlbum("album", emptyList(), emptyMap(), "href", "id", AlbumUri(uriId), artist, images, albumName, "album", null, "1990", "year")
	}

	private fun createTrack(uriId: String, albumUriId: String, coverArtCode: String, artistName: String, trackName: String): Track {
		val album = createSimpleAlbum(albumUriId, "artistName", "albumName", coverArtCode)
		val artist = listOf(SimpleArtist(emptyMap(), "href", "id", ArtistUri("artistUri"), artistName, "artist"))
		return Track(emptyMap(), emptyMap(), emptyList(), "href", "id", PlayableUri(uriId), album, artist, true, 0, 1000, false, null, trackName, 1, null, 1, "track", null, null, null, null)
	}

	private fun createArtist(uriId: String, coverArtCode: String, artistName: String): Artist {
		val images = listOf(SpotifyImage(320, coverArtCode, 320))
		return Artist(emptyMap(), "href", "id", ArtistUri(uriId), mock(), emptyList(), images, artistName, 1, "artist")
	}

	private fun createSimpleShow(uriId: String, showName: String, publisherName: String, coverArtCode: String): SimpleShow {
		val images = listOf(SpotifyImage(300, coverArtCode, 300))
		val spotifyUri: SpotifyUri = mock()
		whenever(spotifyUri.uri).thenReturn("spotify:show:${uriId}")
		return SimpleShow(emptyList(), emptyMap(), emptyList(), "description", false, "href", "id", images, null, emptyList(), "mediaType", showName, publisherName, "show", spotifyUri)
	}

	private fun createSimpleEpisode(uriId: String, episodeName: String, coverArtCode: String): SimpleEpisode {
		val images = listOf(SpotifyImage(300, coverArtCode, 300))
		val spotifyUri: SpotifyUri = mock()
		whenever(spotifyUri.uri).thenReturn("spotify:episode:${uriId}")
		return SimpleEpisode(null, null, 1, false, emptyMap(), "href", "id", images, false, true, null, emptyList(), episodeName, "releaseDateStr", "year", null, "episode", spotifyUri)
	}

	private fun createSimplePlaylist(uriId: String, playlistName: String): SimplePlaylist {
		val images = listOf(SpotifyImage(300, "uri", 300))
		val spotifyUri: SpotifyUri = mock()
		whenever(spotifyUri.uri).thenReturn("spotify:playlist:${uriId}")
		whenever(spotifyUri.id).thenReturn(uriId)
		return SimplePlaylist(emptyMap(), "href", "id", spotifyUri, false, images, playlistName, "description", mock(), "color", false, "snapshotId", mock(), "playlist")
	}
}