package com.sentencelab.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    canLogout: Boolean,
    onSessionChanged: (String?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { CloudApi() }
    val credentials = remember(context) { CredentialManager.create(context) }
    val local = remember(context) { CloudSession(context) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var isCopySource by remember(sessionToken) { mutableStateOf(false) }
    var copyDialog by remember { mutableStateOf(false) }
    var recipientEmail by remember { mutableStateOf("") }

    LaunchedEffect(sessionToken) {
        isCopySource = sessionToken?.let { token ->
            runCatching { api.canCopySets(token) }.getOrDefault(false)
        } ?: false
    }

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
                Text("로그인하면 해당 계정의 서버 문장과 학습 기록을 불러옵니다.")
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
                        message = "로그인 성공. 서버 데이터를 불러옵니다."
                    }
                }) { Text("Google 계정으로 로그인") }
            } else {
                Text("로그인됨 · 학습 변경을 서버에 저장합니다.")
                Text(syncStatus, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled = !busy, onClick = onSyncNow) {
                    Text("지금 동기화")
                }
                if (isCopySource) {
                    OutlinedButton(enabled = !busy, onClick = {
                        recipientEmail = ""
                        copyDialog = true
                    }) { Text("다른 계정으로 전체 세트 복사") }
                }
                if (!canLogout) Text("미저장 변경을 저장하거나 버린 후 계정을 변경하세요.")
                TextButton(enabled = !busy && canLogout, onClick = {
                    execute {
                        val token = sessionToken ?: return@execute
                        try {
                            api.logout(token)
                        } finally {
                            local.clear()
                            onSessionChanged(null)
                        }
                        message = "로그아웃했습니다. 계정 데이터는 서버에 보존됩니다."
                    }
                }) { Text("로그아웃") }
            }
            if (busy) Text("처리 중…")
            if (message.isNotBlank()) {
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (copyDialog && isCopySource && sessionToken != null) {
        AlertDialog(
            onDismissRequest = { if (!busy) copyDialog = false },
            title = { Text("다른 계정으로 전체 세트 복사") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("받는 계정의 Google 이메일 주소를 입력하세요. 기존 세트는 유지되며, 동일한 세트는 중복 복사하지 않습니다.")
                    OutlinedTextField(
                        value = recipientEmail,
                        onValueChange = { recipientEmail = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("받는 계정 이메일") },
                        singleLine = true,
                        enabled = !busy
                    )
                    Text("받는 계정은 SentenceLab에 한 번 이상 로그인해야 합니다.")
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && recipientEmail.contains("@"),
                    onClick = {
                        val token = sessionToken
                        val email = recipientEmail.trim()
                        execute {
                            val copied = api.copySets(token, email)
                            message = "복사 완료: 세트 " + copied.copiedSets +
                                "개, 문장 " + copied.copiedSentences +
                                "개 · 중복 제외 " + copied.skippedSets + "개"
                            copyDialog = false
                        }
                    }
                ) { Text("복사") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { copyDialog = false }) {
                    Text("취소")
                }
            }
        )
    }
}
