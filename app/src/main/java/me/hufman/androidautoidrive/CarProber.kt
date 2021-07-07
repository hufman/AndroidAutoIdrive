package me.hufman.androidautoidrive

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import de.bmw.idrive.BMWRemotingServer
import de.bmw.idrive.BaseBMWRemotingClient
import me.hufman.idriveconnectionkit.IDriveConnection
import me.hufman.idriveconnectionkit.android.CertMangling
import me.hufman.idriveconnectionkit.android.IDriveConnectionStatus
import me.hufman.idriveconnectionkit.android.security.SecurityAccess
import java.io.IOException
import java.net.Socket

/**
 * Tries to connect to a car
 */
class CarProber(val securityAccess: SecurityAccess, val bmwCert: ByteArray, val miniCert: ByteArray, val j29Cert: ByteArray): HandlerThread("CarProber") {
	companion object {
		val PORTS = listOf(4004, 4005, 4006, 4007, 4008)
		val TAG = "CarProber"
	}

	var handler: Handler? = null
	var carConnection: BMWRemotingServer? = null

	val ProberTask = Runnable {
		for (port in PORTS) {
			if (probePort(port)) {
				// we found a car proxy! probably
				// let's try connecting to it
				Log.i(TAG, "Found open socket at $port, detecting car brand")
				probeCar(port)
				// successful connection!
				schedule(5000)
			}
		}
		schedule(2000)
	}

	val KeepaliveTask = Runnable {
		if (!pingCar()) {
			// car has disconnected
			Log.i(TAG, "Previously-connected car has disconnected")
			IDriveConnectionStatus.reset()
			schedule(5000)
		}

		schedule(5000)
	}

	override fun onLooperPrepared() {
		handler = Handler(looper)
		schedule(1000)
	}

	fun schedule(delay: Long) {
		if (!IDriveConnectionStatus.isConnected || carConnection == null) {
			handler?.removeCallbacks(ProberTask)
			handler?.postDelayed(ProberTask, delay)
		} else {
			handler?.removeCallbacks(KeepaliveTask)
			handler?.postDelayed(KeepaliveTask, delay)
		}
	}

	/**
	 * Detects whether a port is open
	 */
	private fun probePort(port: Int): Boolean {
		try {
			val socket = Socket("127.0.0.1", port)
			if (socket.isConnected) {
				socket.close()
				return true
			}
		}
		 catch (e: IOException) {
			// this port isn't open
		}
		return false
	}

	/**
	 * Attempts to detect the car brand
	 */
	private fun probeCar(port: Int) {
		if (!securityAccess.isConnected()) {
			// try again later after the security service is ready
			schedule(2000)
			return
		}
		// try logging in as if it were a bmw or a mini
		var success = false
		var errorMessage: String? = null
		var errorException: Throwable? = null
		for (brand in listOf("bmw", "mini", "j29")) {
			try {
				val cert = when(brand) {
					"bmw" -> bmwCert
					"mini" -> miniCert
					else -> j29Cert
				}
				val signedCert = CertMangling.mergeBMWCert(cert, securityAccess.fetchBMWCerts(brandHint = brand))
				val conn = IDriveConnection.getEtchConnection("127.0.0.1", port, BaseBMWRemotingClient())
				val sas_challenge = conn.sas_certificate(signedCert)
				val sas_login = securityAccess.signChallenge(challenge = sas_challenge)
				conn.sas_login(sas_login)
				val capabilities = conn.rhmi_getCapabilities("", 255)
				carConnection = conn

				val vehicleType = capabilities["vehicle.type"] as? String?
				val hmiType = capabilities["hmi.type"] as? String?
				Analytics.reportCarProbeDiscovered(port, vehicleType, hmiType)
				Log.i(TAG, "Probing detected a HMI type $hmiType")
				if (hmiType?.startsWith("BMW") == true) {
					// BMW brand
					setConnectedState(port, "bmw")
					success = true
					break
				}
				if (hmiType?.startsWith("MINI") == true) {
					// MINI connected
					setConnectedState(port, "mini")
					success = true
					break
				}
				if (brand == "j29") {
					setConnectedState(port, "j29")
					success = true
					break
				}
			} catch (e: Exception) {
				// Car rejected this cert
				errorMessage = e.message
				errorException = e
				Log.w(TAG, "Exception while probing car", e)
			}
		}
		if (!success) {
			Analytics.reportCarProbeFailure(port, errorMessage, errorException)
		}
	}

	private fun pingCar(): Boolean {
		try {
			return carConnection?.ver_getVersion() != null
		} catch (e: java.lang.Exception) {
			carConnection = null
			Log.w(TAG, "Exception while pinging car", e)
			return false
		}
	}

	private fun setConnectedState(port: Int, brand: String) {
		Log.i(TAG, "Successfully detected $brand connection at port $port")
		IDriveConnectionStatus.setConnection(brand, "127.0.0.1", port)
	}
}