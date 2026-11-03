package com.raghavsatyadev.grammarfix.ui

import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

/** One Navigation 3 key per bottom-bar tab. */
@Serializable data class TabRoute(val tab: AppTab) : NavKey

/** Lets the back stack be saved on every platform: Nav3 needs the [NavKey] subclasses listed. */
val TabNavConfig = SavedStateConfiguration {
  serializersModule = SerializersModule {
    polymorphic(NavKey::class) { subclass(TabRoute::class, TabRoute.serializer()) }
  }
}
