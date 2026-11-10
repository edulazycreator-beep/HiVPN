package app.hivpn.feature.profiles

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.hivpn.domain.model.ConnectionProfile
import app.hivpn.domain.repository.ProfileRepository
import app.hivpn.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ProfilesUiState(
    val profiles: List<ConnectionProfile> = emptyList(),
    val activeId: Long? = null,
)

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    val uiState: StateFlow<ProfilesUiState> = combine(profiles.observeProfiles(), settings.settings) { list, s ->
        ProfilesUiState(list, s.activeProfileId ?: list.firstOrNull()?.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfilesUiState())

    init {
        viewModelScope.launch { profiles.ensureDefaultProfile() }
    }

    fun activate(id: Long) = viewModelScope.launch { settings.setActiveProfile(id) }

    fun duplicate(p: ConnectionProfile) = viewModelScope.launch {
        profiles.upsert(p.copy(id = 0, name = (p.name + " copy").take(40)))
    }

    fun delete(p: ConnectionProfile) = viewModelScope.launch {
        val state = uiState.value
        if (state.profiles.size <= 1) return@launch // always keep one profile
        if (state.activeId == p.id) settings.setActiveProfile(state.profiles.first { it.id != p.id }.id)
        profiles.delete(p.id)
    }

    fun exportText(p: ConnectionProfile): String = ProfileTransfer.export(p)

    /** Returns false if the text is not a Hi VPN profile export or fails validation. */
    suspend fun importText(text: String): Boolean {
        val form = ProfileTransfer.import(text) ?: return false
        val profile = form.validate().profile ?: return false
        profiles.upsert(profile)
        return true
    }
}

data class InstalledApp(val packageName: String, val label: String)

data class EditorUiState(
    val loaded: Boolean = false,
    val form: ProfileForm = ProfileForm(),
    val errors: Map<FormError, String?> = emptyMap(),
    val apps: List<InstalledApp> = emptyList(),
    val saved: Boolean = false,
)

@HiltViewModel
class ProfileEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val profiles: ProfileRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val id = savedStateHandle.toRoute<ProfileEditorRoute>().id
    private val _state = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val form = if (id == 0L) ProfileForm(name = "New profile")
            else profiles.observeProfile(id).first()?.let(ProfileForm::from) ?: ProfileForm(name = "New profile")
            _state.update { it.copy(loaded = true, form = form) }
        }
    }

    fun edit(transform: (ProfileForm) -> ProfileForm) = _state.update { it.copy(form = transform(it.form), errors = emptyMap()) }

    fun loadApps() {
        if (_state.value.apps.isNotEmpty()) return
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = context.packageManager
                val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
                    .map { it.activityInfo.applicationInfo }
                    .distinctBy { it.packageName }
                    // Hi VPN itself always uses the tunnel decision of the profile; hide it.
                    .filter { it.packageName != context.packageName }
                    .map { InstalledApp(it.packageName, pm.getApplicationLabel(it).toString()) }
                    .sortedBy { it.label.lowercase() }
            }
            _state.update { it.copy(apps = apps) }
        }
    }

    fun save() {
        val v = _state.value.form.validate()
        val profile = v.profile
        if (profile == null) {
            _state.update { it.copy(errors = v.errors) }
            return
        }
        viewModelScope.launch {
            profiles.upsert(profile)
            _state.update { it.copy(saved = true) }
        }
    }
}
