package app.hivpn.core.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.hivpn.core.common.Dispatcher
import app.hivpn.core.common.HiDispatchers
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Storage for secrets: WireGuard private keys, preshared keys, the account number and
 * API tokens. Nothing stored here is ever logged, exported or backed up.
 */
interface SecretStore {
    suspend fun put(alias: String, value: String)
    suspend fun get(alias: String): String?
    suspend fun delete(alias: String)
    suspend fun deleteWithPrefix(prefix: String)
}

/** Well-known aliases. */
object SecretAliases {
    const val DEVICE_PRIVATE_KEY = "device.wg.private"
    const val ACCOUNT_NUMBER = "account.number"
    const val ACCESS_TOKEN = "account.access"
    const val REFRESH_TOKEN = "account.refresh"
    fun importedPrivateKey(id: Long) = "imported.$id.private"
    fun importedPresharedKey(id: Long) = "imported.$id.psk"
    fun importedPrefix(id: Long) = "imported.$id."
}

/**
 * Each value is encrypted with AES-256-GCM under a non-exportable Android Keystore key
 * (hardware-backed where the device supports it), then stored in app-private prefs as
 * base64(iv || ciphertext). The alias is bound as AAD, so a value copied to another
 * alias fails to decrypt.
 */
@Singleton
internal class KeystoreSecretStore @Inject constructor(
    @ApplicationContext context: Context,
    @Dispatcher(HiDispatchers.IO) private val io: CoroutineDispatcher,
) : SecretStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("hivpn_secrets", Context.MODE_PRIVATE)

    override suspend fun put(alias: String, value: String) = withContext(io) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(alias.toByteArray())
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(alias, Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()) {
            "Failed to persist secret"
        }
    }

    override suspend fun get(alias: String): String? = withContext(io) {
        val stored = prefs.getString(alias, null) ?: return@withContext null
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        cipher.updateAAD(alias.toByteArray())
        String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    override suspend fun delete(alias: String) = withContext(io) {
        prefs.edit().remove(alias).commit()
        Unit
    }

    override suspend fun deleteWithPrefix(prefix: String) = withContext(io) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        editor.commit()
        Unit
    }

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "hivpn.secrets.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SecurityModule {
    @Binds abstract fun secretStore(impl: KeystoreSecretStore): SecretStore
}
