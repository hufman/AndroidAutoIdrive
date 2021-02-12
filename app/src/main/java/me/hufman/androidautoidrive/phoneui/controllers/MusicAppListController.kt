package me.hufman.androidautoidrive.phoneui.controllers

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.View
import me.hufman.androidautoidrive.music.MusicAppInfo
import me.hufman.androidautoidrive.phoneui.MusicPlayerActivity
import me.hufman.androidautoidrive.phoneui.UIState
import me.hufman.androidautoidrive.phoneui.visible

class MusicAppListController(val activity: Activity) {
	fun openApplicationPermissions(appInfo: MusicAppInfo) {
		val packageName = appInfo.packageName
		run {
			val intent = Intent("miui.intent.action.APP_PERM_EDITOR")
			intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
			intent.setClassName("com.miui.securitycenter",
					"com.miui.permcenter.permissions.PermissionsEditorActivity")
			intent.putExtra("extra_pkgname", packageName)
			try {
				activity.startActivity(intent)
				return
			} catch (e: ActivityNotFoundException) {}
		}
		run {
			val intent = Intent("miui.intent.action.APP_PERM_EDITOR")
			intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
			// try an implicit intent without a classname
			intent.putExtra("extra_pkgname", packageName)
			try {
				activity.startActivity(intent)
				return
			} catch (e: ActivityNotFoundException) {}
		}
		run {
			val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
			intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
			intent.data = Uri.fromParts("package", packageName, null)
			try {
				activity.startActivity(intent)
				return
			} catch (e: ActivityNotFoundException) {}
		}
		run {
			val intent = Intent(Settings.ACTION_SETTINGS)
			intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
			try {
				activity.startActivity(intent)
				return
			} catch (e: ActivityNotFoundException) {}
		}
	}

	fun openMusicApp(appInfo: MusicAppInfo) {
		UIState.selectedMusicApp = appInfo
		val intent = Intent(activity, MusicPlayerActivity::class.java)
		activity.startActivity(intent)
	}

	fun toggleFeatures(view: View) {
		view.visible = !view.visible
	}
}