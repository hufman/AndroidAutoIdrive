package me.hufman.androidautoidrive.phoneui.fragments

import android.os.Bundle
import android.os.Handler
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.android.synthetic.main.fragment_music_applist.*
import me.hufman.androidautoidrive.AppSettings
import me.hufman.androidautoidrive.StoredSet
import me.hufman.androidautoidrive.MutableAppSettingsReceiver
import me.hufman.androidautoidrive.R
import me.hufman.androidautoidrive.music.MusicAppInfo
import me.hufman.androidautoidrive.phoneui.MusicAppDiscoveryThread
import me.hufman.androidautoidrive.phoneui.adapters.DataBoundListAdapter
import me.hufman.androidautoidrive.phoneui.adapters.DataBoundViewHolder
import me.hufman.androidautoidrive.phoneui.adapters.MusicAppListAdapter
import me.hufman.androidautoidrive.phoneui.controllers.MusicAppListController

class MusicAppsListFragment: Fragment() {
	val handler = Handler()

	val controller by lazy { MusicAppListController(requireActivity()) }
	val displayedApps = ArrayList<MusicAppInfo>()
	val appDiscoveryThread by lazy {
		MusicAppDiscoveryThread(requireActivity().applicationContext) { appDiscovery ->
			handler.post {
				displayedApps.clear()
				displayedApps.addAll(appDiscovery.allApps)

				val listView = view?.findViewById<RecyclerView>(R.id.listMusicApps)
				if (listView != null && listView.adapter == null) {
					listView.adapter = DataBoundListAdapter(displayedApps, R.layout.musicapp_listitem, controller)
//					listView.adapter = MusicAppListAdapter(requireActivity(), handler, requireActivity().supportFragmentManager, displayedApps, appDiscovery.musicSessions)
				}
				listView?.adapter?.notifyDataSetChanged() // redraw the app list
			}
		}.apply { start() }
	}
	val appSettings by lazy { MutableAppSettingsReceiver(requireContext()) }
	val hiddenApps by lazy { StoredSet(appSettings, AppSettings.KEYS.HIDDEN_MUSIC_APPS) }

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
		return inflater.inflate(R.layout.fragment_music_applist, container, false)
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		listMusicApps.setHasFixedSize(true)
		listMusicApps.layoutManager = LinearLayoutManager(requireActivity())

		listMusicAppsRefresh.setOnRefreshListener {
			appDiscoveryThread.forceDiscovery()
			handler.postDelayed({
				this.view?.findViewById<SwipeRefreshLayout>(R.id.listMusicAppsRefresh)?.isRefreshing = false
			}, 2000)
		}

		val swipeCallback = object: ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
			override fun onMove(p0: RecyclerView, p1: RecyclerView.ViewHolder, p2: RecyclerView.ViewHolder): Boolean {
				return false
			}

			override fun onSwiped(view: RecyclerView.ViewHolder, direction: Int) {
				val musicAppInfo = (view as? DataBoundViewHolder<MusicAppInfo, MusicAppListController>)?.data
				if (musicAppInfo != null) {
					val previous = hiddenApps.contains(musicAppInfo.packageName)
					if (previous) {
						hiddenApps.remove(musicAppInfo.packageName)
					} else {
						hiddenApps.add(musicAppInfo.packageName)
					}
				}
			}
		}
		ItemTouchHelper(swipeCallback).attachToRecyclerView(listMusicApps)
	}

	override fun onResume() {
		super.onResume()

		// build list of discovered music apps
		appDiscoveryThread.discovery()
	}

	override fun onDestroy() {
		super.onDestroy()
		appDiscoveryThread.stopDiscovery()
	}
}