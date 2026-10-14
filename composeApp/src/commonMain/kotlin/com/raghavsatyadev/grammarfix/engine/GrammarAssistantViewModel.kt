package com.raghavsatyadev.grammarfix.engine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Drives the Model and Playground tabs: model download and live correction. */
@OptIn(FlowPreview::class)
class GrammarAssistantViewModel(
  private val inferenceEngine: InferenceEngine,
  private val modelDownloader: ModelDownloader,
  private val tonePresetRepository: TonePresetRepository,
) : ViewModel() {

  /**
   * Only its pure helpers are used here; inference goes through [inferenceEngine]. It is still
   * built for the Playground, so that a future call to `analyze` on it cannot start writing
   * synthetic runs into the user's statistics.
   */
  private val grammarEngine = GrammarEngine(inferenceEngine, source = CorrectionSource.PLAYGROUND)

  private val _inputText = MutableStateFlow("")
  val inputText: StateFlow<String> = _inputText.asStateFlow()

  private val _modelUrlInput = MutableStateFlow(modelDownloader.defaultModelUrl)
  val modelUrlInput: StateFlow<String> = _modelUrlInput.asStateFlow()

  /** Every tone the user can rewrite with, built-in and their own. */
  val tonePresets: StateFlow<List<TonePreset>> = tonePresetRepository.presets

  /** The tone the editor rewrites with. [DefaultTonePresets.POLISH] is the proofreading pass. */
  val selectedTone: StateFlow<TonePreset> = tonePresetRepository.selected

  private val _isNonThinkingMode = MutableStateFlow(true)
  val isNonThinkingMode: StateFlow<Boolean> = _isNonThinkingMode.asStateFlow()

  /** The latest model output, kept for its timing figures and word-level diff. */
  private val _correctionResult = MutableStateFlow<CorrectionResult?>(null)
  val correctionResult: StateFlow<CorrectionResult?> = _correctionResult.asStateFlow()

  /** [correctionResult] with every change located in the text, so one fix can be applied. */
  val analysis: StateFlow<TextAnalysis?> =
    _correctionResult
      .map { result -> result?.let { grammarEngine.analysisOf(it.originalText, it) } }
      .stateIn(viewModelScope, SharingStarted.Eagerly, null)

  private val _isAnalyzing = MutableStateFlow(false)
  val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

  /** 1-based index of the benchmark sentence being checked, or null when no run is active. */
  private val _benchmarkProgress = MutableStateFlow<Int?>(null)
  val benchmarkProgress: StateFlow<Int?> = _benchmarkProgress.asStateFlow()

  private val _ramUsageHistory = MutableStateFlow<List<Float>>(emptyList())
  val ramUsageHistory: StateFlow<List<Float>> = _ramUsageHistory.asStateFlow()

  private val _currentRamUsageMb = MutableStateFlow(0f)
  val currentRamUsageMb: StateFlow<Float> = _currentRamUsageMb.asStateFlow()

  val downloadProgress: StateFlow<DownloadProgress> = modelDownloader.progressState

  private var analysisJob: Job? = null

  /** Text of the inference in flight or last finished; stops the same text being run twice. */
  private var requestedText: String? = null

  init {
    viewModelScope.launch {
      _inputText.debounce(DEBOUNCE_MS).collect { text -> requestCorrection(text) }
    }

    viewModelScope.launch {
      while (true) {
        val ram = getCurrentRamUsageMb()
        _currentRamUsageMb.value = ram
        _ramUsageHistory.value = (_ramUsageHistory.value + ram).takeLast(RAM_HISTORY_SIZE)
        delay(1000L)
      }
    }
  }

  fun onInputTextChanged(newText: String) {
    _inputText.value = newText
    if (newText.isBlank()) clearCorrection()
  }

  fun onModelUrlChanged(newUrl: String) {
    _modelUrlInput.value = newUrl
  }

  /** Switches the tone the editor rewrites with and re-runs the model on the current text. */
  fun selectTone(id: String) {
    if (tonePresetRepository.selected.value.id == id) return
    // The rewrite has to wait for the new tone to be the selected one, not race it.
    viewModelScope.launch {
      tonePresetRepository.select(id)
      requestCorrection(_inputText.value, force = true)
    }
  }

  /** Replaces the editor's text with the model's rewrite, whole. */
  fun applyRewrite() {
    val current = _correctionResult.value ?: return
    if (!current.isSuccess || current.correctedText == _inputText.value) return
    showRemainingFixes(current.correctedText)
  }

  fun toggleNonThinkingMode() {
    _isNonThinkingMode.value = !_isNonThinkingMode.value
    requestCorrection(_inputText.value, force = true)
  }

  /** "Check now": re-runs the model even if this text was already checked. */
  fun checkNow() {
    requestCorrection(_inputText.value, force = true)
  }

  /** Applies one suggestion, by index into [analysis], at its exact position in the text. */
  fun acceptSuggestion(index: Int) {
    val current = analysis.value ?: return
    val suggestion = current.suggestions.getOrNull(index) ?: return
    val updated = grammarEngine.applySuggestionAt(_inputText.value, suggestion) ?: return checkNow()
    showRemainingFixes(updated)
  }

  /** Writes the model's whole correction into the field. */
  fun acceptAllSuggestions() {
    val current = analysis.value ?: return
    if (current.suggestions.isEmpty()) return
    showRemainingFixes(current.correctedText)
  }

  /** Loads [sentence] into the editor and checks it straight away. */
  fun loadSample(sentence: String) {
    _inputText.value = sentence
    requestCorrection(sentence)
  }

  /** Checks every benchmark sentence in turn, waiting for each result before the next. */
  fun runBenchmark() {
    if (_benchmarkProgress.value != null) return
    viewModelScope.launch {
      for ((index, sentence) in BenchmarkSentences.all.withIndex()) {
        _benchmarkProgress.value = index + 1
        _inputText.value = sentence
        requestCorrection(sentence)?.join()
      }
      _benchmarkProgress.value = null
    }
  }

  fun startModelDownload() {
    viewModelScope.launch { modelDownloader.startDownload(_modelUrlInput.value) }
  }

  fun pauseModelDownload() {
    modelDownloader.pauseDownload()
  }

  fun deleteModel() {
    modelDownloader.deleteModel()
  }

  /**
   * After a fix is applied, the rest of the model's output still holds: diff the new text against
   * it so the remaining fixes show at once, without another inference.
   */
  private fun showRemainingFixes(updatedText: String) {
    val result = _correctionResult.value ?: return
    analysisJob?.cancel()
    _isAnalyzing.value = false
    requestedText = updatedText
    _inputText.value = updatedText
    _correctionResult.value =
      result.copy(
        originalText = updatedText,
        diffs = GrammarDiffAnalyzer.computeDiff(updatedText, result.correctedText),
      )
  }

  private fun clearCorrection() {
    analysisJob?.cancel()
    requestedText = null
    _correctionResult.value = null
    _isAnalyzing.value = false
  }

  /** Starts inference for [text] unless it is already running or done; returns the job. */
  private fun requestCorrection(text: String, force: Boolean = false): Job? {
    if (text.isBlank()) {
      clearCorrection()
      return null
    }
    if (!force && text == requestedText) return analysisJob

    analysisJob?.cancel()
    requestedText = text
    analysisJob = viewModelScope.launch {
      _isAnalyzing.value = true
      _correctionResult.value =
        inferenceEngine.generateCorrection(
          text = text,
          enforceNoThink = _isNonThinkingMode.value,
          engineMode = LlmRuntime.engineMode.value,
          tone = tonePresetRepository.selected.value,
          // This view model drives the Playground, whose "Run all 10" fires canned benchmark
          // sentences. Recording them would swamp real usage in every chart, so they are never
          // written. Revisit this if a non-Playground surface starts using this view model.
          source = CorrectionSource.PLAYGROUND,
        )
      _isAnalyzing.value = false
    }
    return analysisJob
  }

  private companion object {
    const val DEBOUNCE_MS = 300L
    const val RAM_HISTORY_SIZE = 20
  }
}
