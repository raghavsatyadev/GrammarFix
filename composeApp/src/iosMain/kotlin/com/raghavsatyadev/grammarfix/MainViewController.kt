package com.raghavsatyadev.grammarfix

import androidx.compose.ui.window.ComposeUIViewController
import com.raghavsatyadev.grammarfix.library.support.di.initKoin
import com.raghavsatyadev.grammarfix.ui.GrammarFixApp

fun MainViewController() = ComposeUIViewController { GrammarFixApp() }

fun InitKoin() = initKoin {}
