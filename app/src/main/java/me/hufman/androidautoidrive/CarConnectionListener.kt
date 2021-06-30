package me.hufman.androidautoidrive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import me.hufman.idriveconnectionkit.android.IDriveConnectionReceiver

/**
 * Starts the MainService when the car announces a connection
 *
 * On Android Oreo, this won't do anything, because we instead run MainService directly
 * to subscribe IDriveConnectionListener manually
 */
class CarConnectionListener: BroadcastReceiver() {
	private val TAG = "CarConnectionListener"

	override fun onReceive(context: Context?, intent: Intent?) {
		if (context == null || intent == null) return
		// car changed connection status
		Log.i(TAG, "Received car status announcement: ${intent.action}")

		if (intent.action == IDriveConnectionReceiver.INTENT_ATTACHED ||
				intent.action == "me.hufman.androidautoidrive.CarConnectionListener_START") {
			if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
				// this is a clear signal of car connection, we can confidently startForeground
				context.startForegroundService(Intent(context, MainService::class.java).setAction(MainService.ACTION_START))
			} else {
				context.startService(Intent(context, MainService::class.java).setAction(MainService.ACTION_START))
			}
		}
		if (intent.action == IDriveConnectionReceiver.INTENT_DETACHED ||
				intent.action == "me.hufman.androidautoidrive.CarConnectionListener_STOP") {
			context.startService(Intent(context, MainService::class.java).setAction(MainService.ACTION_STOP))
		}
	}

	fun register(context: Context) {
		context.registerReceiver(this, IntentFilter(IDriveConnectionReceiver.INTENT_ATTACHED))
		context.registerReceiver(this, IntentFilter(IDriveConnectionReceiver.INTENT_DETACHED))
	}
	fun unregister(context: Context) {
		context.unregisterReceiver(this)
	}
}