package org.forestnav.app.map

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/**
 * Byte-level access to a shapefile folder (the app's `<dataFolder>/GPS_MAP/`
 * via SAF, or any plain directory). Reading is by .shp/.shx/.dbf name; the
 * editable (point) layer is rewritten in full on "Save Edits".
 */
interface MapFileDir {
    fun listFiles(): List<String>            /** all file names in the folder */
    fun readFile(name: String): ByteArray?
    /** First [maxBytes] of a file (shapefile header, .prj) — a full readFile of
     *  a multi-MB layer just to read its 100-byte header is the main lag source. */
    fun readHeader(name: String, maxBytes: Int): ByteArray?
    fun overwriteFile(name: String, bytes: ByteArray): Boolean
    /** Create a new file (used for the first save of the POINTS layer). */
    fun createFile(name: String, bytes: ByteArray): Boolean
    /** True when [name] is in the folder. */
    fun childExists(name: String): Boolean

    /**
     * Remove a file, if the provider allows it.
     *
     * Needed because a cut makes a saved network wrong: the network still holds the
     * unbroken road, so a route would cross ground the user has said cannot be crossed.
     * Leaving that file in place would make the cut look effective while it is not.
     */
    fun deleteFile(name: String): Boolean
    /** Drop cached listing + bytes (Close_MAP frees tens of MB on small phones). */
    /** Last-modified time in ms, 0 when the platform does not report one. */
    fun lastModified(name: String): Long

    fun clearCaches()
}

class SafMapDir(private val context: Context, val treeUri: Uri) : MapFileDir {
    override fun lastModified(name: String): Long =
        mapFolder()?.findFile(name)?.lastModified() ?: 0L


    private val resolver = context.applicationContext.contentResolver

    private val root: DocumentFile?
        get() = try {
            DocumentFile.fromTreeUri(context.applicationContext, treeUri)
        } catch (e: Exception) { null }

    /**
     * Folder holding the shapefiles, resolved once per instance:
     * `<tree>/GPS_MAP` when it exists; a tree that already contains `*.shp`
     * files directly (the flat layout users create by copying SHP_CAP into
     * Documents) is used as-is; otherwise there is no layer folder yet and
     * [createFile] makes `GPS_MAP` on the first save.
     */
    private var layerFolder: DocumentFile? = null
    private var layerResolved = false
    /** Cached listing of [layerFolder] — DocumentFile.findFile() is a full
     *  directory query per call, so every readFile/overwrite would re-list. */
    private var folderChildren: Map<String, DocumentFile>? = null
    /**
     * Raw file bytes by name. Viewport reloads re-read every layer; on slow
     * storage that is minutes per pan/zoom. Cleared for a file on write, and
     * wholesale on folder switch (new instance). External mid-session copies
     * need a map restart to appear.
     */
    private val bytesCache = mutableMapOf<String, ByteArray>()

    fun mapFolder(): DocumentFile? {
        if (layerResolved) return layerFolder
        layerResolved = true
        layerFolder = try {
            val r = root ?: return null
            r.findFile("GPS_MAP")?.takeIf { it.isDirectory }
                ?: r.listFiles()?.any { it.name?.endsWith(".shp", true) == true }
                    ?.takeIf { it }?.let { r }
        } catch (e: Exception) { null }
        return layerFolder
    }

    /** A file in the layer folder. Refreshes the cached listing on a miss so
     *  externally added files (USB copy) and empty-first-listing hiccups heal
     *  instead of failing every read while listFiles() still shows files. */
    /**
     * The document for [name], matched case-insensitively.
     *
     * SAF hands back a plain map keyed by the exact file name, and a lookup in it is
     * case-sensitive. The sheet calls this layer `XGV_MP` while the file on disk is
     * `xgv_mp.shp`, so an exact lookup missed and the write failed - and creating the
     * differently-cased name was refused too, because the provider will not make a second
     * file that differs only in case. Reads happened to work elsewhere in the app, so this
     * only ever showed up as "could not be fully written" when saving.
     */
    private fun child(name: String): DocumentFile? = try {
        // One listing at most. A name that is not there used to trigger a second full
        // folder query, and a missing settings file made Settings take seconds on a slow
        // provider.
        val children = folderChildren ?: refreshChildren()
        children[name] ?: children.entries.firstOrNull { it.key.equals(name, true) }?.value
    } catch (e: Exception) { null }

    private fun refreshChildren(): Map<String, DocumentFile> {
        val map = try {
            mapFolder()?.listFiles()?.mapNotNull { it.name?.let { n -> n to it } }?.toMap()
        } catch (e: Exception) { null }.orEmpty()
        folderChildren = map
        return map
    }

    override fun clearCaches() {
        folderChildren = null
        bytesCache.clear()
    }

    /**
     * Served from [folderChildren] (one provider query per session, not per
     * call). A save writes .shp/.shx/.dbf + up to 4 aux files, and every
     * writeMapFile() used to re-list the folder — 7 full SAF directory
     * queries per save, ~25 s on a slow phone. [createFile] invalidates the
     * cache; [clearCaches] drops it.
     */
    override fun listFiles(): List<String> = try {
        (folderChildren ?: refreshChildren()).keys.sorted()
    } catch (e: Exception) { emptyList() }

    override fun readFile(name: String): ByteArray? = try {
        bytesCache[name] ?: child(name)?.let { f ->
            resolver.openInputStream(f.uri)?.use { it.readBytes() }
        }?.also { bytesCache[name] = it }
    } catch (e: Exception) { null }

    /**
     * A seekable stream over one file, for reading a small window of a large one.
     *
     * The orthoimagery in a survey folder runs to hundreds of megabytes, and reading
     * one whole to show a screenful would exhaust the heap on a phone. A raster is
     * read in strips through this instead, so only the bytes actually on screen are
     * ever held.
     */
    /** One descriptor for random access; the caller closes it. */
    fun openRandomAccess(name: String): android.os.ParcelFileDescriptor? = try {
        child(name)?.let { f -> resolver.openFileDescriptor(f.uri, "r") }
    } catch (e: Exception) { null }

    fun openSeekable(name: String): java.io.InputStream? = try {
        child(name)?.let { f ->
            val pfd = resolver.openFileDescriptor(f.uri, "r") ?: return null
            java.io.BufferedInputStream(java.io.FileInputStream(pfd.fileDescriptor), 1 shl 16)
        }
    } catch (e: Exception) { null }

    /** Size of one file in bytes, or null when it cannot be determined. */
    fun sizeOf(name: String): Long? = try {
        child(name)?.let { f ->
            runCatching {
                resolver.openFileDescriptor(f.uri, "r")?.use { it.statSize }
            }.getOrNull()
        }
    } catch (e: Exception) { null }

    override fun readHeader(name: String, maxBytes: Int): ByteArray? = try {
        child(name)?.let { f ->
            resolver.openInputStream(f.uri)?.use { input ->
                val buf = ByteArray(maxBytes)
                var off = 0
                var zeroes = 0
                // Some providers spuriously return 0 (readBytes() retries, so a
                // break-on-zero here fails files that full reads handle fine).
                while (off < maxBytes && zeroes < 100) {
                    val n = input.read(buf, off, maxBytes - off)
                    if (n < 0) break
                    if (n == 0) { zeroes++; continue }
                    off += n
                }
                if (off > 0) return@let buf.copyOf(off)
                // Partial path failed: fall back to a full read (proven path).
                readFile(name)?.let { full ->
                    if (full.isEmpty()) null else full.copyOf(minOf(maxBytes, full.size))
                }
            }
        }
    } catch (e: Exception) { null }

    override fun overwriteFile(name: String, bytes: ByteArray): Boolean = try {
        bytesCache.remove(name)
        val f = child(name) ?: return false
        resolver.openOutputStream(f.uri, "wt")?.use { it.write(bytes); true } ?: false
    } catch (e: Exception) { false }

    override fun createFile(name: String, bytes: ByteArray): Boolean {
        return try {
            // Never creates folders: the user uploads SHP_CAP with GPS_MAP
            // (or flat .shp files) already in place. Without a layer folder
            // there is nowhere to write — caller reports it.
            val folder = mapFolder() ?: return false
            if (child(name) != null) return overwriteFile(name, bytes)
            val f = folder.createFile("application/octet-stream", name) ?: return false
            folderChildren = null
            bytesCache.remove(name)
            resolver.openOutputStream(f.uri, "wt")?.use { it.write(bytes); true } ?: false
        } catch (e: Exception) { false }
    }

    override fun childExists(name: String): Boolean =
        (folderChildren ?: refreshChildren()).containsKey(name)

    override fun deleteFile(name: String): Boolean {
        return try {
            val f = child(name) ?: return false
            val gone = f.delete()
            folderChildren = null
            bytesCache.remove(name)
            gone
        } catch (e: Exception) {
            false
        }
    }
}

class FileMapDir(private val dir: java.io.File) : MapFileDir {
    override fun lastModified(name: String): Long =
        java.io.File(dir, name).lastModified()

    private val bytesCache = mutableMapOf<String, ByteArray>()

    override fun clearCaches() {
        bytesCache.clear()
    }

    override fun listFiles(): List<String> =
        dir.listFiles()?.map { it.name }?.sorted().orEmpty()

    override fun readFile(name: String): ByteArray? =
        bytesCache[name]
            ?: java.io.File(dir, name).takeIf { it.exists() }?.readBytes()
                ?.also { bytesCache[name] = it }

    override fun readHeader(name: String, maxBytes: Int): ByteArray? =
        java.io.File(dir, name).takeIf { it.exists() }?.inputStream()?.use { input ->
            val buf = ByteArray(maxBytes)
            var off = 0
            while (off < maxBytes) {
                val n = input.read(buf, off, maxBytes - off)
                if (n <= 0) break
                off += n
            }
            if (off == 0) null else buf.copyOf(off)
        }

    override fun overwriteFile(name: String, bytes: ByteArray): Boolean =
        try {
            bytesCache.remove(name)
            if (!dir.exists()) dir.mkdirs()
            java.io.FileOutputStream(java.io.File(dir, name)).use { it.write(bytes); true }
        } catch (e: Exception) { false }

    override fun createFile(name: String, bytes: ByteArray): Boolean = overwriteFile(name, bytes)

    override fun childExists(name: String): Boolean = java.io.File(dir, name).exists()

    override fun deleteFile(name: String): Boolean = java.io.File(dir, name).delete()
}