package com.raghavsatyadev.grammarfix.library.support.di

import com.raghavsatyadev.grammarfix.data.AppSettingsRepository
import com.raghavsatyadev.grammarfix.data.GrammarFixDatabase
import com.raghavsatyadev.grammarfix.data.openGrammarFixDatabase
import com.raghavsatyadev.grammarfix.engine.AcceptedFixRecorder
import com.raghavsatyadev.grammarfix.engine.CorrectionRecorder
import com.raghavsatyadev.grammarfix.engine.GrammarAssistantViewModel
import com.raghavsatyadev.grammarfix.engine.InferenceEngine
import com.raghavsatyadev.grammarfix.engine.LlmRuntime
import com.raghavsatyadev.grammarfix.engine.ModelDownloader
import com.raghavsatyadev.grammarfix.engine.ModelDownloaderImpl
import com.raghavsatyadev.grammarfix.engine.RoomCorrectionRecorder
import com.raghavsatyadev.grammarfix.engine.RoomTonePresetStore
import com.raghavsatyadev.grammarfix.engine.RoomToneRecorder
import com.raghavsatyadev.grammarfix.engine.ToneDetector
import com.raghavsatyadev.grammarfix.engine.TonePresetRepository
import com.raghavsatyadev.grammarfix.engine.TonePresetStore
import com.raghavsatyadev.grammarfix.engine.ToneRecorder
import com.raghavsatyadev.grammarfix.ui.insights.InsightsViewModel
import com.raghavsatyadev.grammarfix.ui.tone.ToneViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

val appModule = module {
  single<InferenceEngine> { LlmRuntime.engine }
  single<ModelDownloader> { ModelDownloaderImpl() }
  // One database per process; opening it twice would take two write locks on the same file.
  single { openGrammarFixDatabase() }
  // The Insights tab reads the analytics table directly; there is nothing to repository over.
  single { get<GrammarFixDatabase>().correctionEventDao() }
  single { get<GrammarFixDatabase>().acceptedFixDao() }
  // Fixes the user accepted in the overlay: what "Fixes applied" counts.
  single { AcceptedFixRecorder(get()) }
  single<TonePresetStore> { RoomTonePresetStore(get()) }
  // Correction statistics, written to the same database and never sent anywhere.
  single<CorrectionRecorder> { RoomCorrectionRecorder(get()) }
  // One repository per process: the Playground picker and the Tones tab edit the same tones.
  single { TonePresetRepository(get()) }
  single { AppSettingsRepository(get<GrammarFixDatabase>().appSettingDao()) }
  // Tone readings (#32): labels only reach the database, never the text or the summary.
  single<ToneRecorder> { RoomToneRecorder(get<GrammarFixDatabase>().toneEventDao()) }
  // One detector per process: the overlay starts reads, the Tones tab shows the latest one.
  single {
    ToneDetector(
      engine = get(),
      scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
      recorder = get(),
    )
  }
  viewModelOf(::GrammarAssistantViewModel)
  viewModelOf(::ToneViewModel)
  // Not `viewModelOf`: the clock is a constructor default a test replaces, and the reflective
  // builder would try to resolve it from the graph.
  viewModel { InsightsViewModel(get(), get()) }
}

fun initKoin(appDeclaration: KoinAppDeclaration = {}) {
  startKoin {
    appDeclaration()
    modules(appModule)
  }
}
