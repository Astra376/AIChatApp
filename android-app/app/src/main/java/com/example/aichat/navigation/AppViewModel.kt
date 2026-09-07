package com.example.aichat.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aichat.core.auth.AuthRepository
import com.example.aichat.core.model.SessionUiState
import com.example.aichat.core.model.ThemeMode
import com.example.aichat.core.model.UserProfile
import com.example.aichat.feature.profile.ProfileRepository
import com.example.aichat.feature.profile.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AppViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    profileRepository: ProfileRepository,
    settingsRepository: SettingsRepository,
    val notifications: com.example.aichat.feature.activity.NotificationRepository
) : ViewModel() {
    val sessionState: StateFlow<SessionUiState> = authRepository.sessionState

    val profile: StateFlow<UserProfile?> = profileRepository.profile.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null
    )

    val themeMode: StateFlow<ThemeMode> = settingsRepository.themeMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ThemeMode.DARK
    )

    init {
        viewModelScope.launch {
            authRepository.bootstrap()
        }
    }
}
