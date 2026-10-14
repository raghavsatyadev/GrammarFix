package com.raghavsatyadev.grammarfix.ui.tone

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raghavsatyadev.grammarfix.data.AppSettingsRepository
import com.raghavsatyadev.grammarfix.engine.DefaultTonePresets
import com.raghavsatyadev.grammarfix.engine.ToneDetector
import com.raghavsatyadev.grammarfix.engine.TonePreset
import com.raghavsatyadev.grammarfix.engine.TonePresetRepository
import com.raghavsatyadev.grammarfix.engine.ToneReading
import com.raghavsatyadev.grammarfix.engine.ToneRecorder
import com.raghavsatyadev.grammarfix.engine.hasEditedPrompt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The tone being written in the editor sheet: a new one when [presetId] is null. */
@Immutable
data class ToneEditorState(
  val presetId: String?,
  val name: String,
  val systemPrompt: String,
  val usesThinking: Boolean,
  val isBuiltIn: Boolean,
  /** A built-in tone whose prompt the user has changed can be put back. */
  val canReset: Boolean,
) {
  val isValid: Boolean
    get() = name.isNotBlank() && systemPrompt.isNotBlank()
}

@Immutable
data class ToneUiState(
  val presets: List<TonePreset> = emptyList(),
  val selectedId: String = DefaultTonePresets.POLISH_ID,
  /** Non-null while the editor is open. */
  val editor: ToneEditorState? = null,
  /** The "Detect tone while typing" setting (#32). */
  val detectWhileTyping: Boolean = false,
  /**
   * The latest tone reading: this session's, with its summary, or after a restart the labels the
   * database kept, whose summary was never stored.
   */
  val lastReading: ToneReading? = null,
) {
  val builtIn: List<TonePreset>
    get() = presets.filter { it.isBuiltIn }

  val custom: List<TonePreset>
    get() = presets.filterNot { it.isBuiltIn }
}

sealed interface ToneEvent {
  data class Select(val id: String) : ToneEvent

  data class Edit(val id: String) : ToneEvent

  data class Delete(val id: String) : ToneEvent

  data object Create : ToneEvent

  data class NameChanged(val value: String) : ToneEvent

  data class PromptChanged(val value: String) : ToneEvent

  data class ThinkingChanged(val value: Boolean) : ToneEvent

  data object ResetPrompt : ToneEvent

  data object Save : ToneEvent

  data object Dismiss : ToneEvent

  data class DetectWhileTypingChanged(val enabled: Boolean) : ToneEvent
}

/** Drives the Tones tab: choosing a tone, rewording its prompt, and managing the user's own. */
class ToneViewModel(
  private val repository: TonePresetRepository,
  private val settings: AppSettingsRepository,
  private val detector: ToneDetector,
  private val recorder: ToneRecorder,
) : ViewModel() {

  private val _uiState = MutableStateFlow(ToneUiState())
  val uiState: StateFlow<ToneUiState> = _uiState.asStateFlow()

  init {
    viewModelScope.launch {
      combine(repository.presets, repository.selected) { presets, selected -> presets to selected }
        .collect { (presets, selected) ->
          _uiState.update { it.copy(presets = presets, selectedId = selected.id) }
        }
    }
    viewModelScope.launch {
      settings.detectToneWhileTyping.collect { on ->
        _uiState.update { it.copy(detectWhileTyping = on) }
      }
    }
    viewModelScope.launch {
      // Labels only: the database never held the summary, so a stored reading has none.
      val stored =
        try {
          recorder.latestLabels()?.let { ToneReading(it, summary = "") }
        } catch (e: Exception) {
          e.printStackTrace()
          null
        }
      detector.lastReading.collect { live ->
        _uiState.update { it.copy(lastReading = live ?: stored) }
      }
    }
  }

  fun onEvent(event: ToneEvent) {
    when (event) {
      is ToneEvent.Select -> viewModelScope.launch { repository.select(event.id) }

      is ToneEvent.Edit -> {
        val preset = _uiState.value.presets.firstOrNull { it.id == event.id } ?: return
        _uiState.update {
          it.copy(
            editor =
              ToneEditorState(
                presetId = preset.id,
                name = preset.name,
                systemPrompt = preset.systemPrompt,
                usesThinking = preset.usesThinking,
                isBuiltIn = preset.isBuiltIn,
                canReset = preset.hasEditedPrompt,
              )
          )
        }
      }

      is ToneEvent.Delete -> viewModelScope.launch { repository.deleteCustom(event.id) }

      ToneEvent.Create ->
        _uiState.update {
          it.copy(
            editor =
              ToneEditorState(
                presetId = null,
                name = "",
                systemPrompt = "",
                usesThinking = true,
                isBuiltIn = false,
                canReset = false,
              )
          )
        }

      is ToneEvent.NameChanged -> editEditor { it.copy(name = event.value) }

      is ToneEvent.PromptChanged -> editEditor { it.copy(systemPrompt = event.value) }

      is ToneEvent.ThinkingChanged -> editEditor { it.copy(usesThinking = event.value) }

      ToneEvent.ResetPrompt -> {
        val editor = _uiState.value.editor ?: return
        val id = editor.presetId ?: return
        viewModelScope.launch { repository.resetPrompt(id) }
        val restored = DefaultTonePresets.byId(id) ?: return
        editEditor { it.copy(systemPrompt = restored.systemPrompt, canReset = false) }
      }

      ToneEvent.Save -> {
        val editor = _uiState.value.editor ?: return
        if (!editor.isValid) return
        viewModelScope.launch {
          when {
            // A built-in tone only ever gives up its prompt; its name and examples stay ours.
            editor.isBuiltIn -> repository.editPrompt(editor.presetId!!, editor.systemPrompt)
            editor.presetId == null ->
              repository.addCustom(editor.name, editor.systemPrompt, editor.usesThinking)
            else ->
              repository.editCustom(
                editor.presetId,
                editor.name,
                editor.systemPrompt,
                editor.usesThinking,
              )
          }
        }
        _uiState.update { it.copy(editor = null) }
      }

      ToneEvent.Dismiss -> _uiState.update { it.copy(editor = null) }

      is ToneEvent.DetectWhileTypingChanged ->
        viewModelScope.launch { settings.setDetectToneWhileTyping(event.enabled) }
    }
  }

  private fun editEditor(transform: (ToneEditorState) -> ToneEditorState) {
    _uiState.update { state -> state.editor?.let { state.copy(editor = transform(it)) } ?: state }
  }
}
