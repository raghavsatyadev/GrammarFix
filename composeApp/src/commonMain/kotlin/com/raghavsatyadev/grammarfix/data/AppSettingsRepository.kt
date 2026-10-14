package com.raghavsatyadev.grammarfix.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** `app_setting` key for [AppSettingsRepository.detectToneWhileTyping]; "1" means on. */
const val DETECT_TONE_WHILE_TYPING_SETTING = "detect_tone_while_typing"

/** `app_setting` key for [AppSettingsRepository.onboardingDone]; "1" once the user finished it. */
const val ONBOARDING_DONE_SETTING = "onboarding_done"

/**
 * The user's app-wide switches, kept in `app_setting` like the selected tone.
 *
 * Readable synchronously through [StateFlow]s, because the accessibility service asks on every
 * settled edit and must not wait on the database to do it. Until the first read lands every switch
 * is at its default, which for each of them is the cautious one.
 */
class AppSettingsRepository(
  private val dao: AppSettingDao,
  scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

  private val _detectToneWhileTyping = MutableStateFlow(false)

  /**
   * Read the tone of the text once typing settles, after each grammar check (#32). Off by default:
   * it runs the model on every pause, which costs battery and can warm the phone.
   */
  val detectToneWhileTyping: StateFlow<Boolean> = _detectToneWhileTyping.asStateFlow()

  private val _onboardingDone = MutableStateFlow<Boolean?>(null)

  /**
   * Whether the first-launch onboarding was finished or skipped. Null until the stored value is
   * read, so a returning user never sees the onboarding flash before it is known.
   */
  val onboardingDone: StateFlow<Boolean?> = _onboardingDone.asStateFlow()

  private val initialLoad = scope.launch {
    try {
      dao.get(DETECT_TONE_WHILE_TYPING_SETTING)?.let { _detectToneWhileTyping.value = it == "1" }
    } catch (e: Exception) {
      // An unreadable setting leaves the switch at its default rather than breaking the app.
      e.printStackTrace()
    }
    _onboardingDone.value =
      try {
        dao.get(ONBOARDING_DONE_SETTING) == "1"
      } catch (e: Exception) {
        // Showing the onboarding again is better than never showing it.
        e.printStackTrace()
        false
      }
  }

  /** Suspends until the stored values have replaced the defaults. */
  suspend fun awaitLoaded() = initialLoad.join()

  suspend fun setOnboardingDone() {
    initialLoad.join()
    _onboardingDone.value = true
    dao.put(AppSettingEntity(ONBOARDING_DONE_SETTING, "1"))
  }

  suspend fun setDetectToneWhileTyping(enabled: Boolean) {
    initialLoad.join()
    _detectToneWhileTyping.value = enabled
    dao.put(AppSettingEntity(DETECT_TONE_WHILE_TYPING_SETTING, if (enabled) "1" else "0"))
  }
}
