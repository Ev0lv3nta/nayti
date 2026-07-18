package app.nayti.ui

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class OnboardingStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
    private val mutableCompleted = MutableStateFlow(preferences.getBoolean(CompletedKey, false))

    val completed: StateFlow<Boolean> = mutableCompleted.asStateFlow()

    fun complete() {
        if (mutableCompleted.value) return
        preferences.edit { putBoolean(CompletedKey, true) }
        mutableCompleted.value = true
    }

    private companion object {
        const val PreferencesName = "nayti-onboarding"
        const val CompletedKey = "completed-v1"
    }
}
