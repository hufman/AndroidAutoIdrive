package me.hufman.androidautoidrive.music

import android.content.Context
import android.graphics.drawable.Drawable
import me.hufman.androidautoidrive.R
import me.hufman.androidautoidrive.carapp.AMAppInfo
import me.hufman.androidautoidrive.carapp.AMAppInfo.Companion.getAppWeight
import me.hufman.androidautoidrive.carapp.AMCategory
import java.util.*

data class MusicAppInfo(override val name: String, override val icon: Drawable,
                        override val packageName: String, val className: String?): AMAppInfo {
	var probed = false
	var hidden = false          // whether to show this app in the car, applied by MusicAppDiscovery settings
	var connectable = false     // whether MediaBrowser can connect
	var controllable = false    // whether MediaSession can control it
	var browseable = false      // whether any media items were discovered
	var searchable = false      // whether any search results came back
	var playsearchable = false  // whether the controller indicated PlayFromSearch supportm
	var playing = false         // whether this MediaSession is currently playing

	// AM App List display controls
	var forcedCategory: AMCategory? = null

	override val category: AMCategory
		get() = this.forcedCategory ?: guessCategory(packageName, name)

	var weightAdjustment = 0
	override val weight: Int
		get() {
			return 800 - (getAppWeight(this.name) - weightAdjustment)
		}

	// general helpers
	companion object {
		fun getInstance(context: Context, packageName: String, className: String?): MusicAppInfo {
			val packageManager = context.packageManager

			val appInfo = packageManager.getApplicationInfo(packageName, 0)
			val name = packageManager.getApplicationLabel(appInfo).toString()
			val icon = packageManager.getApplicationIcon(appInfo)
			return MusicAppInfo(name, icon, packageName, className)
		}

		fun guessCategory(packageName: String, label: String): AMCategory {
			val lowLabel = label.toLowerCase(Locale.getDefault())

			// any names which are Media, even though they look like radio
			val MEDIA_NAMES = setOf("librofm")
			if (MEDIA_NAMES.any { packageName.contains(it) || lowLabel.contains(it) }) {
				return AMCategory.MULTIMEDIA
			}

			// any names which are Radio
			val RADIO_NAMES = setOf(
					"cast", "fm", "news", "radio", "live", "show",  // general keywords
					"sirius", "tunein",     // the big ones
					"ard", "audials", "audioaddict", "mnn", "nhl", "ntv", "rtl",   // some manual package names
					"it.mediaset", "it.froggy",                         // Froggy Media
					"com.global.", "com.thisisglobal.", "com.thisisaim."    // Global Media
			)
			if (RADIO_NAMES.any { packageName.contains(it) || lowLabel.contains(it) }) {
				return AMCategory.RADIO
			}
			val RADIO_PATTERNS = setOf(
					Regex("(8|9|10)[0-9]")
			)
			if (RADIO_PATTERNS.any { packageName.contains(it) || lowLabel.contains(it) }) {
				return AMCategory.RADIO
			}

			// everything else is Media
			return AMCategory.MULTIMEDIA
		}
	}

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false

		other as MusicAppInfo

		if (name != other.name) return false
		if (packageName != other.packageName) return false

		return true
	}

	override fun hashCode(): Int {
		var result = name.hashCode()
		result = 31 * result + packageName.hashCode()
		result = 31 * result + className.hashCode()
		return result
	}

	fun toMap():Map<String, Any?> {
		return mapOf(
				"name" to name,
				"packageName" to packageName,
				"className" to className,
				"connectable" to connectable,
				"controllable" to controllable,
				"browseable" to browseable,
				"searchable" to searchable,
				"playsearchable" to playsearchable
		)
	}

	fun clone(probed: Boolean? = null, hidden: Boolean? = null, connectable: Boolean? = null, controllable: Boolean? = null,
	          browseable: Boolean? = null, searchable: Boolean? = null, playsearchable: Boolean? = null,
	          forcedCategory: AMCategory? = null, weightAdjustment: Int? = null): MusicAppInfo {
		return MusicAppInfo(name, icon, packageName, className).also {
			it.probed = probed ?: this.probed
			it.hidden = hidden ?: this.hidden
			it.connectable = connectable ?: this.connectable
			it.controllable = controllable ?: this.controllable
			it.browseable = browseable ?: this.browseable
			it.searchable = searchable ?: this.searchable
			it.playsearchable = playsearchable ?: this.playsearchable
			it.forcedCategory = forcedCategory ?: this.forcedCategory
			it.weightAdjustment = weightAdjustment ?: this.weightAdjustment
		}
	}

	override fun toString(): String {
		return "MusicAppInfo(name='$name', packageName='$packageName', className=$className, probed=$probed, connectable=$connectable, controllable=$controllable, browseable=$browseable, searchable=$searchable, playsearchable=$playsearchable)"
	}

	fun featuresString(): Context.() -> String {
		val appInfo = this
		return {
			listOfNotNull(
				if (appInfo.controllable && !appInfo.connectable) this.getString(R.string.musicAppControllable) else null,
				if (appInfo.connectable) this.getString(R.string.musicAppConnectable) else null,
				if (appInfo.browseable) this.getString(R.string.musicAppBrowseable) else null,
				if (appInfo.searchable || appInfo.playsearchable) this.getString(R.string.musicAppSearchable) else null,
				if (appInfo.controllable || appInfo.connectable) null else this.getString(R.string.musicAppUnavailable),
				if (appInfo.hidden) this.getString(R.string.musicAppHidden) else null
			).joinToString(", ")
		}
	}
}