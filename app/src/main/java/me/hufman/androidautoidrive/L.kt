import android.content.Context
import android.content.res.Configuration
import java.lang.AssertionError
import java.util.*
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.memberProperties

object L {
	// all of the strings used in the car app
	// these default string values are used in tests, Android resources are used for real
	var NOTIFICATIONS_TITLE = "Notifications"
	var NOTIFICATIONS_EMPTY_LIST = "No Notifications"
	var NOTIFICATION_CLEAR_ACTION = "Clear"
	var NOTIFICATION_OPTIONS = "Options"
	var NOTIFICATION_POPUPS = "Notification Popups"
	var NOTIFICATION_POPUPS_PASSENGER = "Popups with passenger"
	var NOTIFICATION_SOUND = "Play notification sound"
	var NOTIFICATION_READOUT = "Speak when viewing notifications"
	var NOTIFICATION_READOUT_POPUP = "Speak new notifications"
	var NOTIFICATION_READOUT_POPUP_PASSENGER = "... with a passenger"
	var NOTIFICATION_PERMISSION_NEEDED = "This app needs to be granted Notification Access"
	var NOTIFICATION_CENTER_APP = "This app is used to push to the car's Notification Center"
	var READOUT_DESCRIPTION = "This app is used for readout purposes"

	var MAP_ACTION_VIEWMAP = "View Full Map"
	var MAP_ACTION_SEARCH = "Search for Place"
	var MAP_ACTION_CLEARNAV = "Clear Navigation"

	var MUSIC_APPLIST_TITLE = "Apps"
	var MUSIC_APPLIST_EMPTY = "<No Apps>"
	var MUSIC_CUSTOMACTIONS_TITLE = "Actions"
	var MUSIC_DISCONNECTED = "<Not Connected>"
	var MUSIC_BROWSE_TITLE = "Browse"
	var MUSIC_BROWSE_EMPTY = "<Empty>"
	var MUSIC_BROWSE_LOADING = "<Loading>"
	var MUSIC_BROWSE_SEARCHING = "<Searching>"
	var MUSIC_BROWSE_ACTION_JUMPBACK = "Jump Back"
	var MUSIC_BROWSE_ACTION_FILTER = "Filter"
	var MUSIC_BROWSE_ACTION_SEARCH = "Search"
	var MUSIC_BROWSE_PLAY_FROM_SEARCH = "Play From Search"
	val MUSIC_SEARCH_RESULTS_LABEL = "Search Results"
	val MUSIC_SEARCH_RESULTS_VIEW_FULL_RESULTS = "View Full Results"
	val MUSIC_SEARCH_RESULTS_ELLIPSIS = "..."
	var MUSIC_QUEUE_TITLE = "Now Playing"
	var MUSIC_QUEUE_EMPTY = "<Empty Queue>"
	var MUSIC_SKIP_PREVIOUS = "Back"
	var MUSIC_SKIP_NEXT = "Next"
	var MUSIC_TURN_SHUFFLE_UNAVAILABLE = "Shuffle Unavailable"
	var MUSIC_TURN_SHUFFLE_ON = "Turn Shuffle On"
	var MUSIC_TURN_SHUFFLE_OFF = "Turn Shuffle Off"
	var MUSIC_TURN_REPEAT_UNAVAILABLE = "Repeat Unavailable"
	var MUSIC_TURN_REPEAT_ALL_ON = "Turn Repeat All On"
	var MUSIC_TURN_REPEAT_ONE_ON = "Turn Repeat One On"
	var MUSIC_TURN_REPEAT_OFF = "Turn Repeat Off"
	var MUSIC_TEMPORARY_PLAYLIST_DESCRIPTION = "AndroidAutoIDrive dummy playlist - please do not delete"

	var MUSIC_ACTION_SEEK_BACK_5 = "Seek back 5 seconds"
	var MUSIC_ACTION_SEEK_BACK_10 = "Seek back 10 seconds"
	var MUSIC_ACTION_SEEK_BACK_15 = "Seek back 15 seconds"
	var MUSIC_ACTION_SEEK_BACK_20 = "Seek back 20 seconds"
	var MUSIC_ACTION_SEEK_BACK_60 = "Seek back 60 seconds"
	var MUSIC_ACTION_SEEK_FORWARD_5 = "Seek forward 5 seconds"
	var MUSIC_ACTION_SEEK_FORWARD_10 = "Seek forward 10 seconds"
	var MUSIC_ACTION_SEEK_FORWARD_15 = "Seek forward 15 seconds"
	var MUSIC_ACTION_SEEK_FORWARD_20 = "Seek forward 20 seconds"
	var MUSIC_ACTION_SEEK_FORWARD_60 = "Seek forward 60 seconds"

	var MUSIC_SPOTIFY_REMOVE_FROM_COLLECTION = "Dislike"
	var MUSIC_SPOTIFY_START_RADIO = "Make Radio Station"
	var MUSIC_SPOTIFY_ADD_TO_COLLECTION = "Like"
	var MUSIC_SPOTIFY_THUMB_UP = "Thumb Up"
	var MUSIC_SPOTIFY_THUMBS_UP_SELECTED = "Thumbed Up"
	var MUSIC_SPOTIFY_THUMB_DOWN = "Thumb Down"
	var MUSIC_SPOTIFY_THUMBS_DOWN_SELECTED = "Thumbed Down"    // not sure if this exists, but just to be complete

	fun loadResources(context: Context, locale: Locale? = null) {
		val thisContext = if (locale == null) { context } else {
			val origConf = context.resources.configuration
			val localeConf = Configuration(origConf)
			localeConf.setLocale(locale)
			context.createConfigurationContext(localeConf)
		}
		val stringProperties = L::class.memberProperties.filterIsInstance<KMutableProperty1<L, String>>()
		for (member in stringProperties) {
			if (member.name.matches(Regex("[A-Z_]+$"))) {
				// this should crash the app if a new L string is created without a matching resource
				val id = thisContext.resources.getIdentifier(member.name, "string", context.packageName)
				if (id == 0) {
					throw AssertionError("Could not find Resource value for string ${member.name}")
				}
				val value =	thisContext.resources.getString(id)
				member.set(L, value)
			} else if (member.name.matches(Regex("[A-Z_]+_[0-9]+$"))) {
				val nameMatch = Regex("([A-Z_]+)_([0-9]+)$").matchEntire(member.name)
						?: throw AssertionError("Could not parse L name ${member.name}")
				val id = thisContext.resources.getIdentifier(nameMatch.groupValues[1], "plurals", context.packageName)
				val quantity = nameMatch.groupValues[2].toInt()
				val value = thisContext.resources.getQuantityString(id, quantity, quantity)
				member.set(L, value)
			}
		}

	}
}