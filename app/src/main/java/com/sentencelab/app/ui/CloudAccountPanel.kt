package com.sentencelab.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.sentencelab.app.cloud.CloudSnapshot
import com.sentencelab.app.model.StudySet
import kotlinx.coroutines.launch

@Composable
fun CloudAccountPanel(sets: List<StudySet>, onRestore: (List<StudySet>) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { CloudApi() }
    val credentials = remember(context) { CredentialManager.create(context) }
    val local = remember(context) { CloudSession(context) }
    var sessionToken by remember { mutableStateOf(local.load()) }
    var revision by remember { mutableIntStateOf(local.revision()) }
    var remoteForRestore by remember { mutableStateOf<CloudSnapshot?>(null) }
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
                Text(
                    "구글 로그인 설정 전입니다. Google 웹 클라이언트 ID와 HTTPS API 주소를 빌드에 연결해야 합니다.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else if (sessionToken == null) {
                Text("로그인하지 않아도 로컬 학습은 계속 가능합니다.")
                Button(
                    enabled = !busy,
                    onClick = {
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
                            sessionToken = newToken
                            revision = -1
                            message = "로그인 성공. 서버와 동기화할 방법을 선택하세요."
                        }
                    }
                ) { Text("Google 계정으로 로그인") }
            } else {
                Text("로그인됨 · 로컬 문장은 자동으로 업로드되지 않습니다.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy,
                        onClick = {
                            execute {
                                val token = sessionToken ?: return@execute
                                val baseRevision = if (revision >= 0) revision else {
                                    val remote = api.getSnapshot(token)
                                    if (remote.revision != 0) {
                                        error("서버에 기존 데이터가 있습니다. 먼저 서버에서 복원하세요.")
                                    }
                                    0
                                }
                                val newRevision = api.upload(token, baseRevision, sets)
                                revision = newRevision
                                local.save(token, newRevision)
                                message = "서버에 문장과 학습 기록을 저장했습니다."
                            }
                        }
                    ) { Text("서버에 백업") }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            execute {
                                val remote = api.getSnapshot(sessionToken ?: return@execute)
                                remoteForRestore = remote
                                message = if (remote.sets.isEmpty()) "서버에 저장된 문장이 없습니다."
                                else "서버 데이터 " + remote.sets.size + "개 세트를 확인했습니다."
                            }
                        }
                    ) { Text("서버에서 복원") }
                }
                TextButton(
                    enabled = !busy,
                    onClick = {
                        execute {
                            val token = sessionToken ?: return@execute
                            try {
                                api.logout(token)
                            } finally {
                                local.clear()
                                sessionToken = null
                                revision = -1
                            }
                            message = "로그아웃했습니다. 로컬 문장은 유지됩니다."
                        }
                    }
                ) { Text("로그아웃") }
            }
            if (busy) Text("처리 중…")
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }

    val remote = remoteForRestore
    if (remote != null) {
        AlertDialog(
            onDismissRequest = { remoteForRestore = null },
            title = { Text("서버 문장으로 복원") },
            text = {
                Text(
                    "기기 안의 기존 " + sets.size + "개 세트를 서버의 " +
                        remote.sets.size + "개 세트로 교체합니다. " +
                        "기존 로컬 문장은 되돌릴 수 없습니다."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRestore(remote.sets)
                    val token = sessionToken
                    if (token != null) local.save(token, remote.revision)
                    revision = remote.revision
                    remoteForRestore = null
                    message = "서버의 학습 데이터를 기기에 복원했습니다."
                }) { Text("기기 데이터 교체") }
            },
            dismissButton = {
                TextButton(onClick = { remoteForRestore = null }) { Text("취소") }
            }
        )
    }
}
