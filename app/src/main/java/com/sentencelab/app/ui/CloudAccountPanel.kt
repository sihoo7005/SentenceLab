package com.sentencelab.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.sentencelab.app.BuildConfig
import com.sentencelab.app.cloud.CloudApi
import com.sentencelab.app.cloud.CloudSession
import kotlinx.coroutines.launch

@Composable
fun CloudAccountPanel(
    sessionToken: String?,
    syncStatus: String,
    onSyncNow: () -> Unit,
    onSessionChanged: (String?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { CloudApi() }
    val credentials = remember(context) { CredentialManager.create(context) }
    val local = remember(context) { CloudSession(context) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    fun execute(action: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            message = ""
            try {
                action()
            } catch (e: Exception) {
                message = e.localizedMessage ?: "요청에 실패했습니다."
            } finally {
                busy = false
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("클라우드 계정", style = MaterialTheme.typography.titleMedium)
            if (!api.configured) {
                Text("Google 로그인 또는 API 주소가 설정되지 않았습니다.")
            } else if (sessionToken == null) {
                Text("로그인하면 기기의 문장과 서버 데이터를 자동 동기화합니다.")
                Button(enabled = !busy, onClick = {
                    execute {
                        val nonce = api.challenge()
                        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                            .setNonce(nonce)
                            .build()
                        val request = GetCredentialRequest.Builder()
                            .addCredentialOption(option)
                            .build()
                        val result = credentials.getCredential(context = context, request = request)
                        val credential = result.credential as? CustomCredential
                            ?: error("Google 로그인 자격 증명이 아닙니다.")
                        val google = GoogleIdTokenCredential.createFrom(credential.data)
                        val newToken = api.exchangeGoogleToken(google.idToken, nonce)
                        local.save(newToken)
                        onSessionChanged(newToken)
                        message = "로그인 성공. 자동 동기화가 시작됩니다."
                    }
                }) { Text("Google 계정으로 로그인") }
            } else {
                Text("로그인됨 · 변경 시, 앱 재개 시, 앱 사용 중 주기적으로 동기화합니다.")
                Text(syncStatus, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled = !busy, onClick = onSyncNow) {
                    Text("지금 동기화")
                }
                TextButton(enabled = !busy, onClick = {
                    execute {
                        val token = sessionToken ?: return@execute
                        try {
                            api.logout(token)
                        } finally {
                            local.clear()
                            onSessionChanged(null)
                        }
                        message = "로그아웃했습니다. 기기 문장은 그대로 남습니다."
                    }
                }) { Text("로그아웃") }
            }
            if (busy) Text("처리 중…")
            if (message.isNotBlank()) {
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
