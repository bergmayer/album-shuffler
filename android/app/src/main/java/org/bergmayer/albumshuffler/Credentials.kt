package org.bergmayer.albumshuffler

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The user token is encrypted with a non-exportable device key and excluded from backup. */
class Credentials(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = context.getSharedPreferences("music-credentials", Context.MODE_PRIVATE)
    private val keyAlias = "album-shuffler-user-token"
    private var cachedDeveloper: String? = null
    private val tokenClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).followRedirects(false).build()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun userToken(): String? {
        val saved = preferences.getString("user", null) ?: return null
        return try {
            val bytes = Base64.decode(saved, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) {
            preferences.edit().remove("user").apply()
            null
        }
    }

    fun saveUserToken(token: String) {
        require(token.isNotBlank())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        preferences.edit().putString("user", Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    fun clearUser() {
        preferences.edit().remove("user").apply()
        // Apple's playback SDK also caches its token pair in app-private preferences.
        applicationContext.getSharedPreferences("com.apple.android.music.auth_preferences", Context.MODE_PRIVATE).edit().clear().apply()
    }
    fun currentDeveloperToken(): String = cachedDeveloper ?: BuildConfig.MUSIC_DEVELOPER_TOKEN

    suspend fun developerToken(): String {
        cachedDeveloper?.takeIf { tokenIsCurrent(it) }?.let { return it }
        val embedded = BuildConfig.MUSIC_DEVELOPER_TOKEN
        if (tokenIsCurrent(embedded)) return embedded.also { cachedDeveloper = it }
        val endpoint = BuildConfig.MUSIC_TOKEN_URL.toHttpUrlOrNull()
            ?: throw AuthenticationFailure("Apple Music is not configured in this build. See the Android setup instructions.")
        if (!endpoint.isHttps || endpoint.username.isNotEmpty() || endpoint.password.isNotEmpty()) {
            throw AuthenticationFailure("The Apple Music configuration needs a secure token address.")
        }
        val fetched = withContext(Dispatchers.IO) {
            try {
                tokenClient.newCall(Request.Builder().url(endpoint).build()).execute().use {
                    if (!it.isSuccessful) throw AuthenticationFailure("Could not renew Apple Music access. Try again later.")
                    JSONObject(it.body.string()).getString("developerToken")
                }
            } catch (e: AuthenticationFailure) { throw e }
            catch (_: Exception) { throw AuthenticationFailure("Could not renew Apple Music access. Check your connection.") }
        }
        if (!tokenIsCurrent(fetched)) throw AuthenticationFailure("The Apple Music access token has expired.")
        cachedDeveloper = fetched
        return fetched
    }

    private fun tokenIsCurrent(token: String): Boolean = try {
        val parts = token.split('.')
        parts.size == 3 && JSONObject(String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
            .optLong("exp") > System.currentTimeMillis() / 1000 + 60
    } catch (_: Exception) { false }
}
