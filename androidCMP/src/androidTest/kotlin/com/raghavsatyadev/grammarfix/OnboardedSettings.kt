package com.raghavsatyadev.grammarfix

import com.raghavsatyadev.grammarfix.data.AppSettingDao
import com.raghavsatyadev.grammarfix.data.AppSettingEntity
import com.raghavsatyadev.grammarfix.data.AppSettingsRepository
import com.raghavsatyadev.grammarfix.data.ONBOARDING_DONE_SETTING
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Settings for a user who has finished the first-launch onboarding, read before the first frame so
 * the app shell renders at once instead of the onboarding.
 */
fun onboardedSettings() =
  AppSettingsRepository(
    object : AppSettingDao {
      override suspend fun put(setting: AppSettingEntity) {}

      override suspend fun get(key: String): String? =
        if (key == ONBOARDING_DONE_SETTING) "1" else null
    },
    CoroutineScope(Dispatchers.Unconfined),
  )
