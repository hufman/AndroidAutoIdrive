package me.hufman.androidautoidrive.music

import android.graphics.Bitmap

data class QueueMetadata(val title: String? = null,
                         val subtitle: String? = null,
                         val songs: List<MusicMetadata>? = null,
                         var coverArt: Bitmap? = null,
                         val mediaId: String? = null
                         )