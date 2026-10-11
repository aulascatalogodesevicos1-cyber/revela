package com.revela.app.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import com.revela.app.imaging.BitmapIO
import com.revela.app.imaging.ImageProcessor
import com.revela.app.model.EditParams
import com.revela.app.model.Preset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Photo(
    val id: String,
    val createdAt: Long,
    /** "import" ou "tether" */
    val origin: String,
    /** Nome original do arquivo (ex.: DSC01234.ARW) */
    val name: String,
    /** Nome do arquivo RAW guardado junto, se houver */
    val rawName: String? = null,
    /** Incrementa sempre que a imagem ou os ajustes mudam (invalida miniaturas). */
    val rev: Int = 0,
    /** Incrementa só quando o arquivo de imagem em si é substituído. */
    val imageRev: Int = 0,
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("createdAt", createdAt); put("origin", origin); put("name", name)
        if (rawName != null) put("rawName", rawName)
    }

    companion object {
        fun fromJson(o: JSONObject) = Photo(
            id = o.getString("id"),
            createdAt = o.getLong("createdAt"),
            origin = o.optString("origin", "import"),
            name = o.optString("name", ""),
            rawName = if (o.has("rawName")) o.getString("rawName") else null,
        )
    }
}

/**
 * Biblioteca local: cada foto tem a imagem de trabalho (<id>.img), o RAW opcional
 * e um <id>.json com os ajustes não destrutivos.
 */
class PhotoRepository private constructor(private val app: Context) {

    companion object {
        lateinit var instance: PhotoRepository
            private set

        fun init(ctx: Context) {
            if (!::instance.isInitialized) instance = PhotoRepository(ctx.applicationContext)
        }

        /** Janela para juntar RAW + JPEG da mesma foto que chegam separados. */
        private const val PAIR_WINDOW_MS = 20_000L
    }

    private val dir = File(app.filesDir, "library").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val presetsFile = File(app.filesDir, "presets.json")
    private val mutex = Mutex()

    private val _photos = MutableStateFlow(readIndex())
    val photos: StateFlow<List<Photo>> = _photos.asStateFlow()

    private val _userPresets = MutableStateFlow(readPresets())
    val userPresets: StateFlow<List<Preset>> = _userPresets.asStateFlow()

    private val cacheBytes = (Runtime.getRuntime().maxMemory() / 6).toInt()
    private val baseCache = object : LruCache<String, Bitmap>(cacheBytes / 2) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val renderCache = object : LruCache<String, Bitmap>(cacheBytes / 2) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun imageFile(id: String) = File(dir, "$id.img")
    private fun paramsFile(id: String) = File(dir, "$id.json")
    fun photo(id: String): Photo? = _photos.value.firstOrNull { it.id == id }

    // ------------------------------------------------------------------ importação

    suspend fun importUris(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        var count = 0
        for (uri in uris) {
            try {
                val name = displayName(uri) ?: "importada.jpg"
                val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: continue
                if (storeNew(name, bytes, "import", null) != null) count++
            } catch (_: Exception) {
            }
        }
        count
    }

    /**
     * Recebe um arquivo vindo da captura vinculada. Se for o par RAW/JPEG de uma foto
     * que acabou de chegar, junta as duas. Retorna a foto e se ela é nova.
     */
    suspend fun addCaptured(name: String, bytes: ByteArray, preset: Preset?): Pair<Photo, Boolean>? =
        withContext(Dispatchers.IO) {
            val stem = name.substringBeforeLast('.').lowercase()
            val now = System.currentTimeMillis()
            val sibling = _photos.value.firstOrNull {
                it.origin == "tether" && now - it.createdAt < PAIR_WINDOW_MS &&
                    it.name.substringBeforeLast('.').lowercase() == stem &&
                    !it.name.equals(name, ignoreCase = true)
            }
            if (sibling != null) {
                if (BitmapIO.isRaw(name)) {
                    val rawName = "${sibling.id}.${BitmapIO.extension(name)}"
                    File(dir, rawName).writeBytes(bytes)
                    updatePhoto(sibling.id) { it.copy(rawName = rawName) }
                } else {
                    // JPEG real é melhor que a prévia embutida no RAW
                    imageFile(sibling.id).writeBytes(bytes)
                    updatePhoto(sibling.id) { it.copy(rev = it.rev + 1, imageRev = it.imageRev + 1) }
                }
                return@withContext photo(sibling.id)?.let { it to false }
            }
            storeNew(name, bytes, "tether", preset?.params?.lookOnly())?.let { it to true }
        }

    private suspend fun storeNew(name: String, bytes: ByteArray, origin: String, params: EditParams?): Photo? {
        val id = newId()
        val raw = BitmapIO.isRaw(name)
        val working = if (raw) BitmapIO.extractLargestJpeg(bytes) ?: return null else bytes
        imageFile(id).writeBytes(working)
        val rawName = if (raw) "$id.${BitmapIO.extension(name)}".also { File(dir, it).writeBytes(bytes) } else null
        if (params != null) paramsFile(id).writeText(params.toJson().toString())
        val photo = Photo(id, System.currentTimeMillis(), origin, name, rawName)
        mutex.withLock {
            _photos.update { listOf(photo) + it }
            writeIndex(_photos.value)
        }
        return photo
    }

    private suspend fun updatePhoto(id: String, f: (Photo) -> Photo) = mutex.withLock {
        _photos.update { list -> list.map { if (it.id == id) f(it) else it } }
        writeIndex(_photos.value)
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val p = photo(id) ?: return@withContext
        imageFile(id).delete()
        paramsFile(id).delete()
        p.rawName?.let { File(dir, it).delete() }
        mutex.withLock {
            _photos.update { list -> list.filterNot { it.id == id } }
            writeIndex(_photos.value)
        }
    }

    // ------------------------------------------------------------------ ajustes

    suspend fun loadParams(id: String): EditParams = withContext(Dispatchers.IO) {
        val f = paramsFile(id)
        if (!f.exists()) EditParams() else try {
            EditParams.fromJson(JSONObject(f.readText()))
        } catch (_: Exception) {
            EditParams()
        }
    }

    suspend fun saveParams(id: String, params: EditParams) = withContext(Dispatchers.IO) {
        paramsFile(id).writeText(params.toJson().toString())
        updatePhoto(id) { it.copy(rev = it.rev + 1) }
    }

    // ------------------------------------------------------------------ imagens

    /** Imagem original reduzida (sem ajustes), com cache em memória. */
    suspend fun baseImage(id: String, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) {
        val imageRev = photo(id)?.imageRev ?: 0
        val key = "$id@$maxSide#$imageRev"
        baseCache.get(key) ?: BitmapIO.decodeSampled(imageFile(id), maxSide)?.also { baseCache.put(key, it) }
    }

    /** Imagem com todos os ajustes aplicados, para miniaturas e prévias. */
    suspend fun render(id: String, maxSide: Int): Bitmap? {
        val rev = photo(id)?.rev ?: return null
        val key = "$id@$maxSide#$rev"
        renderCache.get(key)?.let { return it }
        val base = baseImage(id, maxSide) ?: return null
        val out = ImageProcessor.render(base, loadParams(id))
        renderCache.put(key, out)
        return out
    }

    // ------------------------------------------------------------------ presets

    suspend fun savePreset(name: String, params: EditParams): Preset = withContext(Dispatchers.IO) {
        val p = Preset("user_" + newId(), name.trim().ifEmpty { "Meu preset" }, params.lookOnly())
        mutex.withLock {
            _userPresets.update { it + p }
            writePresets(_userPresets.value)
        }
        p
    }

    suspend fun deletePreset(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            _userPresets.update { list -> list.filterNot { it.id == id } }
            writePresets(_userPresets.value)
        }
    }

    // ------------------------------------------------------------------ persistência

    private fun newId() = UUID.randomUUID().toString().replace("-", "").take(16)

    private fun displayName(uri: Uri): String? =
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun readIndex(): List<Photo> = try {
        if (!indexFile.exists()) emptyList() else {
            val a = JSONArray(indexFile.readText())
            (0 until a.length()).map { Photo.fromJson(a.getJSONObject(it)) }.filter { imageFile(it.id).exists() }
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun writeIndex(list: List<Photo>) {
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
        tmp.renameTo(indexFile)
    }

    private fun readPresets(): List<Preset> = try {
        if (!presetsFile.exists()) emptyList() else {
            val a = JSONArray(presetsFile.readText())
            (0 until a.length()).map { Preset.fromJson(a.getJSONObject(it)) }
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun writePresets(list: List<Preset>) {
        presetsFile.writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
    }
}
