package app.hivpn.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Raw preference values. Mapping to domain types happens in :data. */
data class StoredPreferences(
    val onboardingCompleted: Boolean,
    val themeMode: String?,
    val selectedTarget: String?,
    val activeProfileId: Long?,
    val autoConnectOnLaunch: Boolean,
    val biometricLock: Boolean,
)

/**
 * Non-secret UI and connection preferences. Secrets (keys, tokens) never go here; they
 * belong in core:security's Keystore-backed storage.
 */
@Singleton
class AppPreferencesDataSource @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val preferences: Flow<StoredPreferences> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            StoredPreferences(
                onboardingCompleted = p[Keys.ONBOARDING_COMPLETED] ?: false,
                themeMode = p[Keys.THEME_MODE],
                selectedTarget = p[Keys.SELECTED_TARGET],
                activeProfileId = p[Keys.ACTIVE_PROFILE],
                autoConnectOnLaunch = p[Keys.AUTO_CONNECT_LAUNCH] ?: false,
                biometricLock = p[Keys.BIOMETRIC_LOCK] ?: false,
            )
        }

    suspend fun setOnboardingCompleted(value: Boolean) = set(Keys.ONBOARDING_COMPLETED, value)
    suspend fun setThemeMode(value: String) = set(Keys.THEME_MODE, value)
    suspend fun setSelectedTarget(value: String?) = set(Keys.SELECTED_TARGET, value)
    suspend fun setActiveProfile(value: Long?) = set(Keys.ACTIVE_PROFILE, value)
    suspend fun setAutoConnectOnLaunch(value: Boolean) = set(Keys.AUTO_CONNECT_LAUNCH, value)
    suspend fun setBiometricLock(value: Boolean) = set(Keys.BIOMETRIC_LOCK, value)

    private suspend fun <T> set(key: Preferences.Key<T>, value: T?) {
        dataStore.edit { prefs -> if (value == null) prefs.remove(key) else prefs[key] = value }
    }

    private object Keys {
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val SELECTED_TARGET = stringPreferencesKey("selected_target")
        val ACTIVE_PROFILE = longPreferencesKey("active_profile_id")
        val AUTO_CONNECT_LAUNCH = booleanPreferencesKey("auto_connect_launch")
        val BIOMETRIC_LOCK = booleanPreferencesKey("biometric_lock")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {
    @Provides
    @Singleton
    fun preferencesDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create {
            context.preferencesDataStoreFile("hivpn_settings")
        }
}
