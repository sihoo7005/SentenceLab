package com.sentencelab.app.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.sentencelab.app.BuildConfig
import com.sentencelab.app.model.Sentence
import com.sentencelab.app.model.StudySet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class CloudSnapshot(val revision: Int, val sets: List<StudySet>)

class CloudConflictException : IllegalStateException("서버 데이터가 변경되어 다시 동기화합니다.")

/** Google ID tokens are exchanged for our own random bearer session token. */
class CloudApi {
    private val base: String = BuildConfig.API_BASE_URL.trimEnd('/')

    val configured: Boolean
        get() = base.startsWith("https://") &&
            BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        token: String? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        check(base.startsWith("https://")) { "HTTPS API 주소가 필요합니다." }
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.connectTimeout = 12000
            connection.readTimeout = 12000
            connection.setRequestProperty("Accept", "application/json")
            if (!token.isNullOrEmpty()) connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status == 409) throw CloudConflictException()
            if (status !in 200..299) {
                val description = when (status) {
                    401 -> "인증이 만료되었거나 로그인에 실패했습니다."
                    503 -> "서버 로그인 설정이 아직 완료되지 않았습니다."
                    else -> "서버 요청 오류 (HTTP $status)"
                }
                throw IllegalStateException(description)
            }
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun challenge(): String = request("POST", "/v1/auth/challenge", JSONObject())
        .getString("nonce")

    suspend fun exchangeGoogleToken(idToken: String, nonce: String): String =
        request("POST", "/v1/auth/google", JSONObject()
            .put("id_token", idToken).put("nonce", nonce)).getString("session_token")

    suspend fun logout(token: String) {
        request("POST", "/v1/auth/logout", JSONObject(), token)
    }

    suspend fun accountId(token: String): String =
        request("GET", "/v1/me", token = token).getString("account_id")

    suspend fun getSnapshot(token: String): CloudSnapshot {
        val data = request("GET", "/v1/snapshot", token = token)
        val groups = data.getJSONArray("sets")
        val sets = (0 until groups.length()).map { i ->
            val group = groups.getJSONObject(i)
            val cards = group.getJSONArray("cards")
            StudySet(
                id = group.getString("id"),
                title = group.getString("title"),
                cards = (0 until cards.length()).map { j ->
                    val card = cards.getJSONObject(j)
                    val pieces = card.getJSONArray("chunks")
                    Sentence(
                        id = card.getString("id"),
                        english = card.getString("english"),
                        korean = card.getString("korean"),
                        chunks = (0 until pieces.length()).map(pieces::getString),
                        attempts = card.getInt("attempts"),
                        correct = card.getInt("correct"),
                        needsReview = card.getBoolean("needsReview"),
                        completed = card.optBoolean("completed", card.optInt("attempts") > 0)
                    )
                }
            )
        }
        return CloudSnapshot(revision = data.getInt("revision"), sets = sets)
    }

    suspend fun upload(token: String, baseRevision: Int, sets: List<StudySet>): Int {
        val groups = JSONArray()
        sets.forEach { set ->
            val cards = JSONArray()
            set.cards.forEach { card ->
                cards.put(JSONObject()
                    .put("id", card.id)
                    .put("english", card.english)
                    .put("korean", card.korean)
                    .put("chunks", JSONArray(card.chunks))
                    .put("attempts", card.attempts)
                    .put("correct", card.correct)
                    .put("needsReview", card.needsReview)
                    .put("completed", card.completed))
            }
            groups.put(JSONObject().put("id", set.id).put("title", set.title).put("cards", cards))
        }
        return request("PUT", "/v1/snapshot", JSONObject()
            .put("base_revision", baseRevision)
            .put("sets", groups), token).getInt("revision")
    }
}

/** Store a session encrypted by an Android Keystore AES-GCM key, not as plain text. */
class CloudSession(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("cloud_session", Context.MODE_PRIVATE)
    private val alias = "sentencelab_session_key"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        val existing = store.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun save(token: String, revision: Int = -1) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val blob = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString("encrypted_session", Base64.encodeToString(blob, Base64.NO_WRAP))
            .putInt("revision", revision)
            .apply()
    }

    fun load(): String? {
        val data = preferences.getString("encrypted_session", null) ?: return null
        return try {
            val blob = Base64.decode(data, Base64.NO_WRAP)
            if (blob.size < 29) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
            String(cipher.doFinal(blob.copyOfRange(12, blob.size)), Charsets.UTF_8)
        } catch (_: Exception) {
            clear()
            null
        }
    }

    fun revision(): Int = preferences.getInt("revision", -1)
    fun clear() {
        preferences.edit().remove("encrypted_session").remove("revision").apply()
    }
}
