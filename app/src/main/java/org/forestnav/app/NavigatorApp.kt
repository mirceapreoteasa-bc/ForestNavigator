package org.forestnav.app

import android.app.Application
import android.net.Uri
import android.util.Log

/**
 * App-level state for Forest Navigator.
 *
 * Holds the user-chosen data folder (SAF tree URI) across runs. Mirrors
 * Forest Point Collector's CollectorApp; the shapefile data itself lives in the
 * user-owned Documents folder, never in app storage.
 */
class NavigatorApp : Application() {

    private val PREF_SAF_TREE = "fnav_saf_tree_uri"
    private val PREF_LAST_CENTER_E = "fnav_center_e"
    private val PREF_LAST_CENTER_N = "fnav_center_n"
    private val PREF_LAST_SCALE = "fnav_scale"
    private val PREF_AUTO_ZOOM = "fnav_auto_zoom"

    var safTreeUri: Uri? = null
        private set

    override fun onCreate() {
        super.onCreate()
        safTreeUri = getSharedPreferences(PREF_SAF_TREE, MODE_PRIVATE)
            .getString(PREF_SAF_TREE, null)?.let { Uri.parse(it) }
        Log.i("NavigatorApp", "saf=$safTreeUri")
    }

    fun setSafTree(uri: Uri) {
        safTreeUri = uri
        getSharedPreferences(PREF_SAF_TREE, MODE_PRIVATE).edit()
            .putString(PREF_SAF_TREE, uri.toString()).apply()
    }

    // ---- last view position, so a restart lands where the user left off ------

    fun saveView(e: Double, n: Double, scale: Int) {
        if (!e.isFinite() || !n.isFinite() || scale <= 0) return
        if (e == 0.0 && n == 0.0) return
        getSharedPreferences(PREF_SAF_TREE, MODE_PRIVATE).edit()
            .putString(PREF_LAST_CENTER_E, e.toString())
            .putString(PREF_LAST_CENTER_N, n.toString())
            .putInt(PREF_LAST_SCALE, scale)
            .apply()
    }

    /**
     * Whether the map changes scale by itself with speed.
     *
     * Defaults to on, and a stored choice is honoured either way: the crew decides,
     * and it survives a restart.
     */
    fun autoZoomEnabled(): Boolean =
        getSharedPreferences(PREF_SAF_TREE, MODE_PRIVATE).getBoolean(PREF_AUTO_ZOOM, true)

    fun setAutoZoomEnabled(on: Boolean) {
        getSharedPreferences(PREF_SAF_TREE, MODE_PRIVATE).edit()
            .putBoolean(PREF_AUTO_ZOOM, on).apply()
    }

    fun lastView(): Triple<Double, Double, Int>? {
        val p = getSharedPreferences(PREF_SAF_TREE, MODE_PRIVATE)
        val e = p.getString(PREF_LAST_CENTER_E, null)?.toDoubleOrNull() ?: return null
        val n = p.getString(PREF_LAST_CENTER_N, null)?.toDoubleOrNull() ?: return null
        val s = p.getInt(PREF_LAST_SCALE, 0)
        if (!e.isFinite() || !n.isFinite() || s <= 0) return null
        // (0,0) is not a place a survey map is centred on. It gets saved when a view
        // is fitted before the canvas has a size, and restoring it leaves the map on
        // null island with every layer filtered out of the viewport.
        if (e == 0.0 && n == 0.0) return null
        return Triple(e, n, s)
    }

}
