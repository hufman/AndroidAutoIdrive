package me.hufman.androidautoidrive

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bmwgroup.connected.car.app.BrandType
import me.hufman.androidautoidrive.carapp.*
import me.hufman.androidautoidrive.carapp.assistant.AssistantControllerAndroid
import me.hufman.androidautoidrive.carapp.assistant.AssistantApp
import me.hufman.androidautoidrive.carapp.maps.MapAppMode
import me.hufman.androidautoidrive.carapp.music.MusicAppMode
import me.hufman.androidautoidrive.connections.BtStatus
import me.hufman.androidautoidrive.phoneui.*
import me.hufman.androidautoidrive.utils.GraphicsHelpersAndroid
import me.hufman.idriveconnectionkit.CDS
import me.hufman.idriveconnectionkit.android.CarAPIAppInfo
import me.hufman.idriveconnectionkit.android.CarAPIDiscovery
import me.hufman.idriveconnectionkit.android.IDriveConnectionReceiver
import me.hufman.idriveconnectionkit.android.security.SecurityAccess
import java.lang.IllegalArgumentException
import java.util.*

class MainService: Service() {
	companion object {
		const val TAG = "MainService"

		const val ACTION_START = "me.hufman.androidautoidrive.MainService.start"
		const val ACTION_STOP = "me.hufman.androidautoidrive.MainService.stop"
		const val EXTRA_FOREGROUND = "EXTRA_FOREGROUND"

		const val CONNECTED_PROBE_TIMEOUT: Long = 2 * 60 * 1000     // if Bluetooth is connected
		const val DISCONNECTED_PROBE_TIMEOUT: Long = 20 * 1000      // if Bluetooth is not connected
		const val STARTUP_DEBOUNCE = 1500
	}

	val ONGOING_NOTIFICATION_ID = 20503
	val NOTIFICATION_CHANNEL_ID = "ConnectionNotification"

	var foregroundNotification: Notification? = null

	val appSettings by lazy { MutableAppSettingsReceiver(applicationContext) }
	val securityAccess by lazy { SecurityAccess.getInstance(applicationContext) }
	val iDriveConnectionReceiver = IDriveConnectionReceiver()   // start listening to car connection, if the AndroidManifest listener didn't start
	var carProberThread: CarProber? = null

	val securityServiceThread by lazy { SecurityServiceThread(securityAccess) }

	val carInformationObserver = CarInformationObserver()
	val carInformationUpdater by lazy { CarInformationUpdater(appSettings) }
	val cdsObserver = CDSEventHandler { _, _ -> combinedCallback() }
	var threadCapabilities: CarThread? = null
	var carappCapabilities: CarInformationDiscovery? = null

	var notificationService: NotificationService? = null
	var mapService: MapService? = null
	var musicService: MusicService? = null

	var threadAssistant: CarThread? = null
	var carappAssistant: AssistantApp? = null

	// reschedule a combinedCallback to make sure enough time has passed
	var connectionTime: Long? = null
	val combinedCallbackRunnable = Runnable {
		combinedCallback()
	}

	// shut down probing after a timeout
	val handler = Handler()
	val shutdownTimeout = Runnable {
		if (!iDriveConnectionReceiver.isConnected || !securityAccess.isConnected()) {
			stopSelf()
		}
	}

	// triggers repeated BLUETOOTH_UUID responses to the Connected app
	// which should trigger it to connect to the car
	val btStatus by lazy { BtStatus(applicationContext, {}).apply { register() } }
	val btfetchUuidsWithSdp: Runnable by lazy { Runnable {
		handler.removeCallbacks(btfetchUuidsWithSdp)
		if (!iDriveConnectionReceiver.isConnected) {
			btStatus.fetchUuidsWithSdp()

			// schedule as long as the car is connected
			if (btStatus.isA2dpConnected) {
				handler.postDelayed(btfetchUuidsWithSdp, 5000)
			}
		}
	} }

	override fun onCreate() {
		super.onCreate()

		// only register listeners a single time

		// subscribe to configuration changes
		appSettings.callback = {
			combinedCallback()
		}
		// set up connection listeners
		securityAccess.callback = {
			combinedCallback()
		}
		iDriveConnectionReceiver.callback = {
			combinedCallback()
		}
		// start some more services as the capabilities are known
		carInformationObserver.callback = {
			combinedCallback()
		}
		// start some more services as the car language is discovered
		carInformationObserver.cdsData.addEventHandler(CDS.VEHICLE.LANGUAGE, 1000, cdsObserver)
	}

	override fun onBind(intent: Intent?): IBinder? {
		return null
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		Analytics.init(applicationContext)

		val action = intent?.action ?: ""
		if (action == ACTION_START) {
			handleActionStart()
		} else if (action == ACTION_STOP) {
			handleActionStop()
		}
		return START_STICKY
	}

	override fun onDestroy() {
		handleActionStop()
		// undo one time things
		carInformationObserver.cdsData.removeEventHandler(CDS.VEHICLE.LANGUAGE, cdsObserver)
		carInformationObserver.callback = { }
		iDriveConnectionReceiver.callback = { }
		try {
			iDriveConnectionReceiver.unsubscribe(applicationContext)
		} catch (e: IllegalArgumentException) {
			// never started?
		}
		securityAccess.callback = {}
		appSettings.callback = null

		carProberThread?.quitSafely()
		btStatus.unregister()

		// close the notification
		stopServiceNotification()
		super.onDestroy()
	}

	/**
	 * Start the service
	 */
	private fun handleActionStart() {
		Log.i(TAG, "Starting up service $this")
		// show the notification, so we can be startForegroundService'd
		createNotificationChannel()
		startServiceNotification(iDriveConnectionReceiver.brand, ChassisCode.fromCode(carInformationObserver.capabilities["vehicle.type"] ?: "Unknown"))
		// try connecting to the security service
		if (!securityServiceThread.isAlive) {
			securityServiceThread.start()
		}
		securityServiceThread.connect()
		// start up car connection listener
		announceCarAPI()
		iDriveConnectionReceiver.subscribe(applicationContext)
		startCarProber()
	}

	private fun createNotificationChannel() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID,
					getString(R.string.notification_channel_connection),
					NotificationManager.IMPORTANCE_MIN)

			val notificationManager = getSystemService(NotificationManager::class.java)
			notificationManager.createNotificationChannel(channel)
		}
	}

	private fun announceCarAPI() {
		val myApp = CarAPIAppInfo(
				id = packageName,
				title = "Android Auto IDrive",
				category = "OnlineServices",
				brandType = BrandType.ALL,
				version = "v2",
				rhmiVersion = "v2",
				connectIntentName = "me.hufman.androidautoidrive.CarConnectionListener_START",
				disconnectIntentName = "me.hufman.androidautoidrive.CarConnectionListener_STOP",
				appIcon = null
		)
		CarAPIDiscovery.announceApp(applicationContext, myApp)
	}

	private fun startCarProber() {
		if (carProberThread?.isAlive != true) {
			carProberThread = CarProber(securityAccess,
				CarAppAssetManager(applicationContext, "smartthings").getAppCertificateRaw("bmw")!!.readBytes(),
				CarAppAssetManager(applicationContext, "smartthings").getAppCertificateRaw("mini")!!.readBytes(),
				CarAppAssetManager(applicationContext, "cdsbaseapp").getAppCertificateRaw("")!!.readBytes()
			).apply { start() }
		} else {
			carProberThread?.schedule(1000)
		}
	}

	private fun startServiceNotification(brand: String?, chassisCode: ChassisCode?) {
		Log.i(TAG, "Creating foreground notification")
		val notifyIntent = Intent(applicationContext, NavHostActivity::class.java).apply {
			flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
		}
		val foregroundNotificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
				.setOngoing(true)
				.setContentTitle(getText(R.string.notification_title))
				.setContentText(getText(R.string.notification_description))
				.setSmallIcon(R.drawable.ic_notify)
				.setPriority(NotificationCompat.PRIORITY_LOW)
				.setContentIntent(PendingIntent.getActivity(applicationContext, 0, notifyIntent, PendingIntent.FLAG_UPDATE_CURRENT))

		if (!iDriveConnectionReceiver.isConnected) {
			// show a notification even if we aren't connected, in case we were called with startForegroundService
			// the combinedCallback will hide it right away, but it's probably enough
			foregroundNotificationBuilder.setContentText(getString(R.string.connectionStatusWaiting))
			foregroundNotificationBuilder.setOngoing(false) // able to swipe away if we aren't currently connected
		} else {
			foregroundNotificationBuilder.setContentText(getText(R.string.notification_description))
			if (brand?.toLowerCase(Locale.ROOT) == "bmw") foregroundNotificationBuilder.setContentText(getText(R.string.notification_description_bmw))
			if (brand?.toLowerCase(Locale.ROOT) == "mini") foregroundNotificationBuilder.setContentText(getText(R.string.notification_description_mini))

			if (chassisCode != null) {
				foregroundNotificationBuilder.setContentText(resources.getString(R.string.notification_description_chassiscode, chassisCode.toString()))
			}
		}

		val foregroundNotification = foregroundNotificationBuilder.build()
		if (this.foregroundNotification?.extras?.getCharSequence(Notification.EXTRA_TEXT) !=
				foregroundNotification?.extras?.getCharSequence(Notification.EXTRA_TEXT)) {
			startForeground(ONGOING_NOTIFICATION_ID, foregroundNotification)
		}
		this.foregroundNotification = foregroundNotification
	}

	fun combinedCallback() {
		synchronized(MainService::class.java) {
			handler.removeCallbacks(shutdownTimeout)
			if (iDriveConnectionReceiver.isConnected && securityAccess.isConnected()) {
				// make sure we are subscribed for an instance id
				if ((iDriveConnectionReceiver.instanceId ?: -1) <= 0) {
					iDriveConnectionReceiver.subscribe(applicationContext)
				}

				// record when we first see the connection
				if (connectionTime == null) {
					connectionTime = System.currentTimeMillis()
				}
				// wait until it's connected long enough
				if (System.currentTimeMillis() - (connectionTime ?: 0) < STARTUP_DEBOUNCE) {
					handler.removeCallbacks(combinedCallbackRunnable)
					handler.postDelayed(combinedCallbackRunnable, 1000)
					return
				}

				var startAny = false

				AppSettings.loadSettings(applicationContext)

				// set the car app languages
				val locale = if (appSettings[AppSettings.KEYS.FORCE_CAR_LANGUAGE].isNotBlank()) {
					Locale.forLanguageTag(appSettings[AppSettings.KEYS.FORCE_CAR_LANGUAGE])
				} else if (appSettings[AppSettings.KEYS.PREFER_CAR_LANGUAGE].toBoolean() &&
							carInformationObserver.cdsData[CDS.VEHICLE.LANGUAGE] != null) {
					CDSVehicleLanguage.fromCdsProperty(carInformationObserver.cdsData[CDS.VEHICLE.LANGUAGE]).locale
				} else {
					null
				}
				L.loadResources(applicationContext, locale)

				// report car capabilities
				// also loads the car language
				startAny = startAny or startCarCapabilities()

				if (appSettings[AppSettings.KEYS.PREFER_CAR_LANGUAGE].toBoolean() &&
						carInformationObserver.cdsData[CDS.VEHICLE.LANGUAGE] == null) {
					// still waiting for language
					Log.d(TAG, "Waiting for the car's language to be confirmed")
				} else if (iDriveConnectionReceiver.brand == "bmw" || iDriveConnectionReceiver.brand == "mini") {
					// RHMI apps are not supported in J29

					// start notifications
					startAny = startAny or startNotifications()

					// start maps
					startAny = startAny or startMaps()

					// start music
					startAny = startAny or startMusic()

					// start assistant
					startAny = startAny or startAssistant()

					// start navigation handler
					startNavigationListener()
				}

				// check if we are idle and should shut down
				if (!startAny) {
					Log.i(TAG, "No apps are enabled, skipping the service start")
					stopServiceNotification()
					stopSelf()
				}

				// show a donation popup, if it's time
				DonationRequest(this).countUsage()
			} else {
				Log.d(TAG, "Not fully connected: IDrive:${iDriveConnectionReceiver.isConnected} SecurityService:${securityAccess.isConnected()}")
				connectionTime = null
				stopCarApps()
				val timeout = if (btStatus.isBTConnected) CONNECTED_PROBE_TIMEOUT else DISCONNECTED_PROBE_TIMEOUT
				handler.postDelayed(shutdownTimeout, timeout)
				handler.post(btfetchUuidsWithSdp)
			}
		}
		carInformationUpdater.isConnected = iDriveConnectionReceiver.isConnected && securityAccess.isConnected()
	}

	fun startCarCapabilities(): Boolean {
		synchronized(this) {
			if (threadCapabilities?.isAlive != true) {
				Log.d(TAG, "Starting CarCapabilities thread")
				// clear the capabilities to not start dependent services until it's ready
				threadCapabilities = CarThread("Capabilities") {
					Log.i(TAG, "Starting to discover car capabilities")
					val handler = threadCapabilities?.handler!!

					// receiver to receive capabilities and cds properties
					// wraps the CDSConnection with a Handler async wrapper
					val carInformationUpdater = object: CarInformationUpdater(appSettings) {
						override fun onCdsConnection(connection: CDSConnection?) {
							super.onCdsConnection(connection?.let { CDSConnectionAsync(handler, connection) })
						}
					}

					carappCapabilities = CarInformationDiscovery(iDriveConnectionReceiver, securityAccess,
							CarAppAssetManager(applicationContext, "cdsbaseapp"), carInformationUpdater)
					carappCapabilities?.onCreate()
				}
				threadCapabilities?.start()
			}
		}
		return true
	}

	fun stopCarCapabilities() {
		carappCapabilities?.onDestroy()
		carappCapabilities = null
		threadCapabilities?.quitSafely()
		threadCapabilities = null
	}

	fun startNotifications(): Boolean {
		if (carInformationObserver.capabilities.isNotEmpty() && notificationService == null) {
			notificationService = NotificationService(applicationContext, iDriveConnectionReceiver, securityAccess, carInformationObserver)
		}
		return notificationService?.start() ?: false
	}

	fun stopNotifications() {
		notificationService?.stop()
	}

	fun startMaps(): Boolean {
		if (carInformationObserver.capabilities.isNotEmpty() && mapService == null) {
			mapService = MapService(applicationContext, iDriveConnectionReceiver, securityAccess,
					MapAppMode(RHMIDimensions.create(carInformationObserver.capabilities), AppSettingsViewer()))
		}
		return mapService?.start() ?: false
	}

	fun stopMaps() {
		mapService?.stop()
	}

	fun startMusic(): Boolean {
		if (carInformationObserver.capabilities.isNotEmpty() && musicService == null) {
			musicService = MusicService(applicationContext, iDriveConnectionReceiver, securityAccess,
					MusicAppMode.build(carInformationObserver.capabilities, applicationContext))
		}
		musicService?.start()
		return true
	}
	fun stopMusic() {
		musicService?.stop()
	}

	fun startAssistant(): Boolean {
		synchronized(this) {
			if (threadAssistant == null) {
				threadAssistant = CarThread("Assistant") {
					Log.i(TAG, "Starting to discover car capabilities")

					carappAssistant = AssistantApp(iDriveConnectionReceiver, securityAccess,
							CarAppAssetManager(applicationContext, "basecoreOnlineServices"),
							AssistantControllerAndroid(applicationContext, PhoneAppResourcesAndroid(applicationContext)),
							GraphicsHelpersAndroid())
					carappAssistant?.onCreate()
				}
				threadAssistant?.start()
			}
		}
		return true
	}

	fun stopAssistant() {
		carappAssistant?.onDestroy()
		carappAssistant = null
		threadAssistant?.quitSafely()
		threadAssistant = null
	}

	fun startNavigationListener() {
		if (carInformationObserver.capabilities["navi"] == "true") {
			if (iDriveConnectionReceiver.brand?.toLowerCase(Locale.ROOT) == "bmw") {
				packageManager.setComponentEnabledSetting(
						ComponentName(BuildConfig.APPLICATION_ID, "${BuildConfig.APPLICATION_ID}.phoneui.NavActivityBMW"),
						PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
				)
			} else if (iDriveConnectionReceiver.brand?.toLowerCase(Locale.ROOT) == "mini") {
				packageManager.setComponentEnabledSetting(
						ComponentName(BuildConfig.APPLICATION_ID, "${BuildConfig.APPLICATION_ID}.phoneui.NavActivityMINI"),
						PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
				)
			}
		}
	}

	fun stopNavigationListener() {
		packageManager.setComponentEnabledSetting(
				ComponentName(BuildConfig.APPLICATION_ID, "${BuildConfig.APPLICATION_ID}.phoneui.NavActivityBMW"),
				PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP
		)
		packageManager.setComponentEnabledSetting(
				ComponentName(BuildConfig.APPLICATION_ID, "${BuildConfig.APPLICATION_ID}.phoneui.NavActivityMINI"),
				PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP
		)
	}

	private fun stopCarApps() {
		stopCarCapabilities()
		stopNotifications()
		stopMaps()
		stopMusic()
		stopAssistant()
		stopNavigationListener()

		// revert notification to Waiting for Connection
		startServiceNotification(iDriveConnectionReceiver.brand, ChassisCode.fromCode(carInformationObserver.capabilities["vehicle.type"] ?: "Unknown"))
	}

	/**
	 * Stop the service
	 */
	private fun handleActionStop() {
		Log.i(TAG, "Shutting down apps")
		synchronized(MainService::class.java) {
			stopCarApps()
		}
	}

	private fun stopServiceNotification() {
		Log.i(TAG, "Hiding foreground notification")
		stopForeground(true)
		foregroundNotification = null
	}
}