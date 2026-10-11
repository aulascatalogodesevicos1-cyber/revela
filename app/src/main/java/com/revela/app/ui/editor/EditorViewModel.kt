package com.revela.app.ui.editor

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.revela.app.data.PhotoRepository
import com.revela.app.imaging.ExportSize
import com.revela.app.imaging.Exporter
import com.revela.app.imaging.ImageProcessor
import com.revela.app.model.EditParams
import com.revela.app.model.Preset
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Área de transferência de ajustes entre fotos (copiar / colar configurações). */
object SettingsClipboard {
    var params: EditParams? = null
}

@OptIn(FlowPreview::class)
class EditorViewModel(app: Application, state: SavedStateHandle) : AndroidViewModel(app) {

    val photoId: String = checkNotNull(state["id"])
    private val repo = PhotoRepository.instance

    private val _params = MutableStateFlow(EditParams())
    val params: StateFlow<EditParams> = _params.asStateFlow()

    private val _rendered = MutableStateFlow<Bitmap?>(null)
    val rendered: StateFlow<Bitmap?> = _rendered.asStateFlow()

    private val _original = MutableStateFlow<Bitmap?>(null)
    val original: StateFlow<Bitmap?> = _original.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private val undoStack = ArrayDeque<EditParams>()
    private val redoStack = ArrayDeque<EditParams>()
    private var committed = EditParams()
    private var loaded = false

    init {
        viewModelScope.launch {
            val p = repo.loadParams(photoId)
            committed = p
            _params.value = p
            val base = repo.baseImage(photoId, PREVIEW_SIZE)
            _original.value = base
            loaded = true
            if (base == null) {
                _messages.emit("Não foi possível abrir a imagem")
                return@launch
            }
            _params.collectLatest { pp ->
                _busy.value = true
                _rendered.value = ImageProcessor.render(base, pp)
                _busy.value = false
            }
        }
        // Salvamento automático (não destrutivo)
        viewModelScope.launch {
            _params.drop(1).debounce(400).collect { if (loaded) repo.saveParams(photoId, it) }
        }
    }

    /** Altera os ajustes durante o arraste de um controle (sem criar passo de desfazer). */
    fun update(f: (EditParams) -> EditParams) {
        _params.value = f(_params.value)
    }

    /** Fecha um passo de edição (ao soltar o controle). */
    fun commit() {
        val cur = _params.value
        if (cur == committed) return
        undoStack.addLast(committed)
        if (undoStack.size > 100) undoStack.removeFirst()
        redoStack.clear()
        committed = cur
        refreshHistory()
    }

    fun set(p: EditParams) {
        _params.value = p
        commit()
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_params.value)
        committed = prev
        _params.value = prev
        refreshHistory()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_params.value)
        committed = next
        _params.value = next
        refreshHistory()
    }

    private fun refreshHistory() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    fun applyPreset(preset: Preset) = set(_params.value.withLookOf(preset.params))

    fun reset() = set(EditParams())

    fun copySettings() {
        SettingsClipboard.params = _params.value.lookOnly()
        _messages.tryEmit("Ajustes copiados")
    }

    fun pasteSettings() {
        val p = SettingsClipboard.params
        if (p == null) {
            _messages.tryEmit("Nada copiado ainda")
            return
        }
        set(_params.value.withLookOf(p))
        _messages.tryEmit("Ajustes colados")
    }

    fun savePreset(name: String) = viewModelScope.launch {
        val p = repo.savePreset(name, _params.value)
        _messages.emit("Preset \"${p.name}\" salvo")
    }

    fun deletePreset(preset: Preset) = viewModelScope.launch {
        repo.deletePreset(preset.id)
        _messages.emit("Preset \"${preset.name}\" apagado")
    }

    fun export(size: ExportSize) = viewModelScope.launch {
        _busy.value = true
        _messages.emit("Exportando…")
        val uri = try {
            Exporter.export(getApplication(), photoId, _params.value, size)
        } catch (e: OutOfMemoryError) {
            null
        }
        _busy.value = false
        _messages.emit(if (uri != null) "Salva em Galeria › Pictures/Revela" else "Falha ao exportar (tente um tamanho menor)")
    }

    /** Miniatura de prévia de um preset aplicado nesta foto. */
    suspend fun presetPreview(preset: Preset): Bitmap? {
        val small = repo.baseImage(photoId, 220) ?: return null
        return ImageProcessor.render(small, _params.value.withLookOf(preset.params))
    }

    companion object {
        const val PREVIEW_SIZE = 1600
    }
}
