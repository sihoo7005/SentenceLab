package com.sentencelab.app.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sentencelab.app.data.DocumentImporter
import com.sentencelab.app.data.ImportedDocument
import com.sentencelab.app.data.KoreanTranslator
import com.sentencelab.app.cloud.CloudSession
import com.sentencelab.app.cloud.CloudApi
import com.sentencelab.app.cloud.CloudConflictException
import com.sentencelab.app.cloud.ServerStudyState
import com.sentencelab.app.model.BulkEntry
import com.sentencelab.app.model.Sentence
import com.sentencelab.app.model.StudyMode
import com.sentencelab.app.model.StudySet
import com.sentencelab.app.model.normalized
import com.sentencelab.app.model.BlankDifficulty
import com.sentencelab.app.model.blankWords
import com.sentencelab.app.model.blankPrompt
import com.sentencelab.app.model.matchesBlank
import com.sentencelab.app.model.completedIn
import com.sentencelab.app.model.allModesCompleted
import com.sentencelab.app.model.parseBulk
import com.sentencelab.app.model.parseChunks
import com.sentencelab.app.model.eligibleForStudy
import com.sentencelab.app.model.resetStudyProgress
import com.sentencelab.app.model.validChunks
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel

@Composable
fun SentenceLabApp() {
    val context = LocalContext.current
    val cloudSession = remember { CloudSession(context.applicationContext) }
    var sessionToken by remember { mutableStateOf(cloudSession.load()) }
    val accountScope = rememberCoroutineScope()
    // A new account gets an entirely new screen, coroutine scope and in-memory data.
    key(sessionToken) {
        ServerStudyScreen(sessionToken = sessionToken, onSessionChanged = { next ->
            val previous = sessionToken
            if (next == null) cloudSession.clear()
            sessionToken = next
            // Revoke the old token without letting a slow logout race with edits
            // or clear a subsequently logged-in account.
            if (next == null && previous != null) {
                accountScope.launch {
                    try {
                        CloudApi().logout(previous)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Local sign-out already succeeded.
                    }
                }
            }
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerStudyScreen(
    sessionToken: String?,
    onSessionChanged: (String?) -> Unit
) {
    val context = LocalContext.current
    val api = remember { CloudApi() }
    var serverState by remember { mutableStateOf(ServerStudyState()) }
    val sets by remember { derivedStateOf { serverState.sets } }
    var syncStatus by remember { mutableStateOf(if (sessionToken == null) "로그인이 필요합니다." else "서버 데이터 불러오는 중…") }
    var syncBusy by remember { mutableStateOf(false) }
    var discardDialog by remember { mutableStateOf(false) }
    val syncSignals = remember { Channel<Unit>(Channel.CONFLATED) }
    DisposableEffect(syncSignals) {
        onDispose { syncSignals.close() }
    }
    var currentSetId by remember { mutableStateOf<String?>(null) }
    var studyMode by remember { mutableStateOf<StudyMode?>(null) }
    var sessionIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var newSetOpen by remember { mutableStateOf(false) }
    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Sentence?>(null) }
    var importing by remember { mutableStateOf(false) }
    var deletingCard by remember { mutableStateOf<Sentence?>(null) }
    var deletingSet by remember { mutableStateOf(false) }
    var resetProgressSetId by remember { mutableStateOf<String?>(null) }
    var directStudyCardId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var fileImportTargetId by remember { mutableStateOf<String?>(null) }
    var fileImportBusy by remember { mutableStateOf(false) }
    var fileImportPreview by remember { mutableStateOf<ImportedDocument?>(null) }
    var fileImportMessage by remember { mutableStateOf<String?>(null) }
    var translationBusy by remember { mutableStateOf(false) }
    var translationProgress by remember { mutableStateOf("") }
    var translationNotice by remember { mutableStateOf<String?>(null) }
    var translationJob by remember { mutableStateOf<Job?>(null) }
    var autoTranslationAttempted by remember { mutableStateOf<Set<String>>(emptySet()) }
    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        if (uri != null) {
            scope.launch {
                fileImportBusy = true
                try {
                    fileImportPreview = DocumentImporter.read(context.applicationContext, uri)
                } catch (e: Exception) {
                    fileImportMessage = e.localizedMessage ?: "문서를 읽지 못했습니다."
                } finally {
                    fileImportBusy = false
                }
            }
        }
    }

    fun save(updated: List<StudySet>) {
        if (sessionToken == null || !serverState.ready) return
        serverState = serverState.edit(updated)
        syncStatus = if (serverState.conflict) "다른 기기의 변경과 충돌 · 미저장 변경이 있습니다."
            else if (serverState.dirty || serverState.attempted != null) "서버 저장 대기 중…" else "서버 저장 완료"
        syncSignals.trySend(Unit)
    }

    fun translateMissing(setId: String, cards: List<Sentence>) {
        val pending = KoreanTranslator.missing(cards)
        if (pending.isEmpty()) {
            translationNotice = "자동 번역할 영어 문장이 없습니다."
            return
        }
        if (translationBusy) {
            translationNotice = "다른 세트의 번역이 진행 중입니다. 완료 후 다시 시도하세요."
            return
        }
        translationBusy = true
        translationProgress = "번역 모델을 준비하고 있습니다. 최초 실행 시 다운로드가 필요합니다."
        translationJob = scope.launch {
            try {
                val (translated, failed) = KoreanTranslator.fillMissing(
                    pending,
                    onProgress = { finished, total ->
                        translationProgress = "문장 번역 중: " + finished + " / " + total
                    },
                    onBatch = { results ->
                        if (results.isNotEmpty()) {
                            save(sets.map { group ->
                                if (group.id != setId) group
                                else group.copy(cards = group.cards.map { card ->
                                    val korean = results[card.id]
                                    if (korean != null && card.korean.isBlank()) {
                                        card.copy(korean = korean)
                                    } else card
                                })
                            })
                        }
                    }
                )
                translationNotice = "한국어 해석 " + translated + "개 자동 입력 완료" +
                    if (failed > 0) " · 실패 " + failed + "개" else ""
            } catch (cancelled: CancellationException) {
                translationNotice = "자동 번역을 중단했습니다. 완료된 해석은 그대로 저장됩니다."
            } catch (e: Exception) {
                translationNotice = "번역에 실패했습니다: " +
                    (e.localizedMessage ?: "번역 모델 다운로드나 네트워크를 확인하세요.") +
                    " · 영어 원문은 유지됩니다."
            } finally {
                translationBusy = false
                translationJob = null
            }
        }
    }

    LaunchedEffect(currentSetId) {
        val id = currentSetId ?: return@LaunchedEffect
        if (id !in autoTranslationAttempted && !translationBusy) {
            autoTranslationAttempted = autoTranslationAttempted + id
            val untranslated = sets.firstOrNull { it.id == id }?.cards.orEmpty()
            if (KoreanTranslator.missing(untranslated).isNotEmpty()) {
                translateMissing(id, untranslated)
            }
        }
    }

    LaunchedEffect(sessionToken) {
        val token = sessionToken ?: return@LaunchedEffect
        syncSignals.trySend(Unit)
        for (signal in syncSignals) {
            if (serverState.conflict) continue
            syncBusy = true
            syncStatus = if (serverState.dirty || serverState.attempted != null)
                "서버 저장 중…" else "서버 데이터 확인 중…"
            try {
                // Recover a lost upload response by reading the authoritative server.
                // Never blindly retry a write against a newer revision.
                if (serverState.attempted != null || !serverState.dirty) {
                    val remote = api.getSnapshot(token)
                    serverState = serverState.observe(remote)
                }
                val upload = serverState.nextUpload()
                if (upload != null) {
                    serverState = serverState.beginUpload(upload)
                    val revision = api.upload(token, upload.revision, upload.sets)
                    serverState = serverState.uploaded(upload, revision)
                }
                syncStatus = when {
                    serverState.conflict -> "다른 기기의 변경과 충돌 · 미저장 변경이 있습니다."
                    serverState.dirty -> "서버 저장 대기 중…"
                    else -> "서버 저장 완료 · 버전 " + serverState.acknowledged?.revision
                }
                if (serverState.dirty && !serverState.conflict) syncSignals.trySend(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: CloudConflictException) {
                syncStatus = "서버 변경 확인 중…"
                syncSignals.trySend(Unit)
            } catch (e: Exception) {
                syncStatus = (if (serverState.dirty || serverState.attempted != null)
                    "저장 실패 · 앱을 닫으면 미저장 변경을 잃을 수 있습니다. "
                    else "서버 불러오기 실패 · ") +
                    (e.localizedMessage ?: "네트워크를 확인하세요.")
            } finally {
                syncBusy = false
            }
        }
    }

    LaunchedEffect(sessionToken) {
        if (sessionToken == null) return@LaunchedEffect
        while (true) {
            delay(30_000L)
            syncSignals.trySend(Unit)
        }
    }

    val activity = context as? ComponentActivity
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) syncSignals.trySend(Unit)
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    val selected = sets.firstOrNull { it.id == currentSetId }
    val title = if (studyMode != null) "문장 학습"
        else selected?.title ?: "SentenceLab"

    fun goBack() {
        if (studyMode != null) studyMode = null
        else currentSetId = null
    }
    BackHandler(enabled = selected != null) { goBack() }
    BackHandler(enabled = selected == null && (serverState.dirty || serverState.attempted != null)) {
        syncStatus = "미저장 변경이 있습니다. 서버 저장 후 앱을 종료하세요."
        syncSignals.trySend(Unit)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1)
                        if (sessionToken != null && selected != null) {
                            Text(syncStatus, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                        }
                    }
                },
                navigationIcon = {
                    if (selected != null) {
                        TextButton(onClick = { goBack() }) { Text("‹ 뒤로") }
                    }
                }
            )
        }
    ) { insets ->
        Surface(modifier = Modifier.fillMaxSize().padding(insets)) {
            when {
                selected == null -> HomeScreen(
                    sets = sets,
                    onCreate = { newSetOpen = true },
                    onOpen = { currentSetId = it },
                    sessionToken = sessionToken,
                    syncStatus = syncStatus,
                    onSyncNow = { syncSignals.trySend(Unit) },
                    dataReady = serverState.ready,
                    canLogout = !serverState.dirty && serverState.attempted == null && !translationBusy && !fileImportBusy,
                    canDiscard = (serverState.dirty || serverState.attempted != null) && !syncBusy && !translationBusy && !fileImportBusy,
                    onDiscard = { discardDialog = true },
                    onSessionChanged = { next ->
                        if (next != null || (!serverState.dirty && serverState.attempted == null &&
                                !translationBusy && !fileImportBusy)) {
                            onSessionChanged(next)
                        }
                    }
                )
                studyMode != null -> StudyScreen(
                    set = selected,
                    sessionIds = sessionIds,
                    mode = studyMode!!,
                    onGrade = { cardId, isCorrect ->
                        save(sets.map { set ->
                            if (set.id != selected.id) set
                            else set.copy(cards = set.cards.map { card ->
                                if (card.id != cardId) card
                                else card.copy(
                                    attempts = card.attempts + 1,
                                    correct = card.correct + if (isCorrect) 1 else 0,
                                    needsReview = !isCorrect,
                                    completed = true,
                                    modeMask = card.modeMask or studyMode!!.bit
                                )
                            })
                        })
                    },
                    onClose = { studyMode = null }
                )
                else -> SetScreen(
                    set = selected,
                    onAdd = { editing = null; editorOpen = true },
                    onEdit = { editing = it; editorOpen = true },
                    onImport = { importing = true },
                    onImportFile = {
                        fileImportTargetId = selected.id
                        fileImportMessage = null
                        // Virtual Google Docs are excluded by CATEGORY_OPENABLE.
                        documentPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            type = "*/*"
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    },
                    onDeleteCard = { deletingCard = it },
                    onDeleteSet = { deletingSet = true },
                    onResetProgress = { resetProgressSetId = selected.id },
                    onDirectStudy = { card -> directStudyCardId = card.id },
                    onTranslateMissing = {
                        translateMissing(selected.id, selected.cards)
                    },
                    onStudy = { mode, reviewOnly ->
                        sessionIds = eligibleForStudy(selected.cards, mode, reviewOnly)
                            .map { it.id }
                        studyMode = mode
                    }
                )
            }
        }
    }

    if (discardDialog) {
        AlertDialog(
            onDismissRequest = { discardDialog = false },
            title = { Text("미저장 변경을 버릴까요?") },
            text = { Text("서버에 이미 저장된 데이터는 삭제하지 않습니다. 이 기기의 아직 저장되지 않은 변경만 버리고 서버에서 다시 불러옵니다.") },
            confirmButton = {
                TextButton(enabled = !syncBusy && !translationBusy && !fileImportBusy, onClick = {
                    discardDialog = false
                    serverState = ServerStudyState()
                    currentSetId = null
                    studyMode = null
                    syncStatus = "서버 데이터 다시 불러오는 중…"
                    syncSignals.trySend(Unit)
                }) { Text("변경 버리기") }
            },
            dismissButton = { TextButton(onClick = { discardDialog = false }) { Text("취소") } }
        )
    }

    val directCard = selected?.cards?.firstOrNull { it.id == directStudyCardId }
    if (directCard != null) {
        AlertDialog(
            onDismissRequest = { directStudyCardId = null },
            title = { Text("학습 유형 선택") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(directCard.english, maxLines = 3)
                    if (directCard.modeMask != 0 || directCard.completed) {
                        Text("✓ 이미 완료한 문장도 다시 학습할 수 있습니다.")
                    }
                    StudyMode.entries.forEach { mode ->
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = mode != StudyMode.SPELL || directCard.korean.isNotBlank(),
                            onClick = {
                                sessionIds = listOf(directCard.id)
                                studyMode = mode
                                directStudyCardId = null
                            }
                        ) { Text(mode.label) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { directStudyCardId = null }) { Text("취소") }
            }
        )
    }

    if (translationBusy) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("한국어 자동 해석") },
            text = { Text(translationProgress) },
            confirmButton = {
                TextButton(onClick = { translationJob?.cancel() }) { Text("중단") }
            }
        )
    }
    val translationResult = translationNotice
    if (translationResult != null && !translationBusy) {
        AlertDialog(
            onDismissRequest = { translationNotice = null },
            title = { Text("자동 번역 결과") },
            text = { Text(translationResult) },
            confirmButton = {
                TextButton(onClick = { translationNotice = null }) { Text("확인") }
            }
        )
    }

    if (fileImportBusy) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("문서 가져오는 중") },
            text = { Text("Google Drive 또는 PDF에서 텍스트를 추출하고 있습니다.") },
            confirmButton = {}
        )
    }

    val importedFile = fileImportPreview
    if (importedFile != null) {
        AlertDialog(
            onDismissRequest = { fileImportPreview = null },
            title = { Text("문장 가져오기 확인") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("파일: " + importedFile.name)
                    Text("읽은 문장: " + importedFile.sentences.size + "개")
                    Text("미리보기")
                    Text(importedFile.sentences.take(2).joinToString("\n\n"), maxLines = 7)
                    Text("영어 원문을 저장한 뒤 한국어 해석을 자동으로 생성합니다. 기존 해석은 덮어쓰지 않습니다.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val targetId = fileImportTargetId
                    val target = sets.firstOrNull { it.id == targetId }
                    if (target == null) {
                        fileImportMessage = "가져올 문장 세트를 찾을 수 없습니다."
                    } else {
                        val seen = target.cards.map { normalized(it.english) }.toMutableSet()
                        val added = importedFile.sentences.filter { seen.add(normalized(it)) }
                            .map { english ->
                                val words = english.split(Regex("\\s+"))
                                val segmentSize = (words.size + 99) / 100
                                Sentence(
                                    english = english, korean = "",
                                    chunks = words.chunked(segmentSize).map { it.joinToString(" ") }
                                )
                            }
                        if (added.isNotEmpty()) {
                            save(sets.map { group ->
                                if (group.id == targetId) group.copy(cards = group.cards + added)
                                else group
                            })
                            if (targetId != null) translateMissing(targetId, added)
                        } else {
                            fileImportMessage = "중복 문장을 제외하면 새로 추가할 문장이 없습니다."
                        }
                    }
                    fileImportPreview = null
                }) { Text("가져오기") }
            },
            dismissButton = {
                TextButton(onClick = { fileImportPreview = null }) { Text("취소") }
            }
        )
    }

    if (fileImportMessage != null) {
        AlertDialog(
            onDismissRequest = { fileImportMessage = null },
            title = { Text("파일 가져오기") },
            text = { Text(fileImportMessage.orEmpty()) },
            confirmButton = {
                TextButton(onClick = { fileImportMessage = null }) { Text("확인") }
            }
        )
    }

    if (newSetOpen) {
        var titleInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newSetOpen = false },
            title = { Text("새 문장 세트") },
            text = {
                OutlinedTextField(
                    value = titleInput,
                    onValueChange = { titleInput = it },
                    label = { Text("세트 이름") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = titleInput.isNotBlank(),
                    onClick = {
                        val created = StudySet(title = titleInput.trim())
                        save(sets + created)
                        currentSetId = created.id
                        newSetOpen = false
                    }
                ) { Text("만들기") }
            },
            dismissButton = {
                TextButton(onClick = { newSetOpen = false }) { Text("취소") }
            }
        )
    }

    if (selected != null && editorOpen) {
        SentenceEditor(
            initial = editing,
            onDismiss = { editorOpen = false },
            onSave = { english, korean, segments ->
                val result = if (editing == null) {
                    Sentence(english = english, korean = korean, chunks = segments)
                } else {
                    editing!!.copy(english = english, korean = korean, chunks = segments)
                }
                save(sets.map { set ->
                    if (set.id != selected.id) set
                    else set.copy(cards = if (editing == null) set.cards + result
                        else set.cards.map { if (it.id == result.id) result else it })
                })
                editorOpen = false
                if (result.korean.isBlank()) translateMissing(selected.id, listOf(result))
            }
        )
    }

    if (selected != null && importing) {
        ImportDialog(
            onDismiss = { importing = false },
            onImport = { lines ->
                val added = lines.map { Sentence(english = it.english, korean = it.korean) }
                save(sets.map { set ->
                    if (set.id == selected.id) set.copy(cards = set.cards + added) else set
                })
                importing = false
            }
        )
    }

    if (selected != null && deletingCard != null) {
        val card = deletingCard!!
        ConfirmDelete(
            title = "문장을 삭제할까요?",
            content = card.english,
            onCancel = { deletingCard = null },
            onDelete = {
                save(sets.map { set ->
                    if (set.id == selected.id) set.copy(
                        cards = set.cards.filterNot { it.id == card.id }
                    ) else set
                })
                deletingCard = null
            }
        )
    }

    val resetSet = sets.firstOrNull { it.id == resetProgressSetId }
    if (resetSet != null) {
        ConfirmDelete(
            title = "이 세트의 학습 상태를 초기화할까요?",
            content = "완료 표시, 시도 횟수, 정답 횟수, 오답 복습 기록이 초기화됩니다.\n영어 문장과 한국어 해석은 그대로 유지됩니다.",
            onCancel = { resetProgressSetId = null },
            onDelete = {
                save(sets.map { studySet ->
                    if (studySet.id == resetSet.id) studySet.copy(
                        cards = resetStudyProgress(studySet.cards)
                    ) else studySet
                })
                resetProgressSetId = null
            },
            confirmLabel = "초기화"
        )
    }

    if (selected != null && deletingSet) {
        ConfirmDelete(
            title = "세트를 삭제할까요?",
            content = "포함된 문장과 학습 기록이 모두 삭제됩니다.",
            onCancel = { deletingSet = false },
            onDelete = {
                save(sets.filterNot { it.id == selected.id })
                currentSetId = null
                deletingSet = false
            }
        )
    }
}

@Composable
private fun HomeScreen(
    sets: List<StudySet>,
    onCreate: () -> Unit,
    onOpen: (String) -> Unit,
    sessionToken: String?,
    syncStatus: String,
    onSyncNow: () -> Unit,
    dataReady: Boolean,
    canLogout: Boolean,
    canDiscard: Boolean,
    onDiscard: () -> Unit,
    onSessionChanged: (String?) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        CloudAccountPanel(
            sessionToken = sessionToken,
            syncStatus = syncStatus,
            onSyncNow = onSyncNow,
            canLogout = canLogout,
            onSessionChanged = onSessionChanged
        )
        if (canDiscard) {
            OutlinedButton(onClick = onDiscard) { Text("미저장 변경 버리고 서버에서 다시 불러오기") }
        }
        Text("내 문장 세트", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (sessionToken == null) {
            Text("로그인하면 해당 계정의 서버 문장을 불러옵니다.")
            return@Column
        }
        if (!dataReady) {
            Text("서버 데이터가 아직 준비되지 않았습니다.")
            return@Column
        }
        Text("문장과 학습 기록은 서버에 저장됩니다.")
        Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text("+ 새 세트") }
        if (sets.isEmpty()) Text("아직 세트가 없습니다. 새 세트를 만들어 시작하세요.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(sets, key = { it.id }) { set ->
                ElevatedCard(
                    onClick = { onOpen(set.id) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(set.title, style = MaterialTheme.typography.titleLarge)
                        Text(
                            set.cards.size.toString() + "문장 · 오답 " +
                                set.cards.count { it.needsReview }.toString() + "개 · 완료 " +
                                set.cards.count { it.completed }.toString() + "개",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetScreen(
    set: StudySet,
    onAdd: () -> Unit,
    onEdit: (Sentence) -> Unit,
    onImport: () -> Unit,
    onImportFile: () -> Unit,
    onDeleteCard: (Sentence) -> Unit,
    onDeleteSet: () -> Unit,
    onResetProgress: () -> Unit,
    onDirectStudy: (Sentence) -> Unit,
    onTranslateMissing: () -> Unit,
    onStudy: (StudyMode, Boolean) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAdd) { Text("+ 문장 추가") }
            OutlinedButton(onClick = onImport) { Text("대량 등록") }
            OutlinedButton(onClick = onImportFile) { Text("Drive / PDF 가져오기") }
            OutlinedButton(
                onClick = onTranslateMissing,
                enabled = KoreanTranslator.missing(set.cards).isNotEmpty()
            ) { Text("빈 한국어 해석 자동 채우기") }
        }
        Text("학습 모드 · 전체 미완료 " + set.cards.count { !it.allModesCompleted() } + "개",
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StudyMode.entries.forEach { mode ->
                FilledTonalButton(
                    enabled = set.cards.any {
                        !it.completedIn(mode) && (mode != StudyMode.SPELL || it.korean.isNotBlank())
                    },
                    onClick = { onStudy(mode, false) }
                ) { Text(mode.label + " · " + eligibleForStudy(set.cards, mode, false).size) }
            }
            OutlinedButton(
                enabled = set.cards.any { it.needsReview },
                onClick = { onStudy(StudyMode.ORDER, true) }
            ) { Text("오답 복습") }
        }
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "문장 목록 (" + set.cards.size + ")",
                style = MaterialTheme.typography.titleMedium
            )
            Row {
                TextButton(
                    onClick = onResetProgress,
                    enabled = set.cards.any {
                        it.attempts > 0 || it.correct > 0 || it.completed || it.needsReview
                    }
                ) { Text("학습 초기화") }
                TextButton(onClick = onDeleteSet) { Text("세트 삭제") }
            }
        }
        if (set.cards.isEmpty()) Text("문장을 추가하면 학습을 시작할 수 있습니다.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(set.cards, key = { it.id }) { card ->
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(card.english, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(4.dp))
                        Text(card.korean, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (card.modeMask != 0) {
                            Text("✓ " + StudyMode.entries.filter { card.completedIn(it) }.joinToString(" · ") { it.label }, color = MaterialTheme.colorScheme.primary)
                        } else if (card.completed) {
                            Text("이전 학습 기록 있음", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (card.needsReview) {
                            Text("복습 필요", color = MaterialTheme.colorScheme.error)
                        }
                        Row {
                            TextButton(onClick = { onDirectStudy(card) }) { Text("학습") }
                            TextButton(onClick = { onEdit(card) }) { Text("수정") }
                            TextButton(onClick = { onDeleteCard(card) }) { Text("삭제") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SentenceEditor(
    initial: Sentence?,
    onDismiss: () -> Unit,
    onSave: (String, String, List<String>) -> Unit
) {
    var english by remember(initial?.id) { mutableStateOf(initial?.english.orEmpty()) }
    var korean by remember(initial?.id) { mutableStateOf(initial?.korean.orEmpty()) }
    var chunksInput by remember(initial?.id) {
        mutableStateOf(initial?.chunks?.joinToString(" | ").orEmpty())
    }
    val chunks = parseChunks(english, chunksInput)
    val valid = english.isNotBlank() && validChunks(english, chunks)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "문장 추가" else "문장 수정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = english,
                    onValueChange = { english = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("영어 문장") },
                    minLines = 2
                )
                OutlinedTextField(
                    value = korean,
                    onValueChange = { korean = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("한국어 뜻 (선택)") },
                    minLines = 2
                )
                OutlinedTextField(
                    value = chunksInput,
                    onValueChange = { chunksInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("어순 배열 조각 (선택)") },
                    supportingText = { Text("구분자는 | 입니다. 비우면 단어별로 분리합니다.") }
                )
                if (english.isNotBlank() && !validChunks(english, chunks)) {
                    Text(
                        "조각을 합쳤을 때 원문과 같아야 합니다.",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onSave(english.trim(), korean.trim(), chunks) }
            ) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

@Composable
private fun ImportDialog(
    onDismiss: () -> Unit,
    onImport: (List<BulkEntry>) -> Unit
) {
    var content by remember { mutableStateOf("") }
    val parsed = remember(content) { parseBulk(content) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("여러 문장 가져오기") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("한 줄에 영어 문장 + Tab + 한국어 뜻을 붙여넣으세요. ||| 도 구분자로 지원합니다.")
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                    maxLines = 12,
                    placeholder = { Text("I like music.\t나는 음악을 좋아해.\nHello!|||안녕!") }
                )
                Text("등록 가능: " + parsed.size + "문장")
            }
        },
        confirmButton = {
            TextButton(enabled = parsed.isNotEmpty(), onClick = { onImport(parsed) }) {
                Text("가져오기")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

@Composable
private fun ConfirmDelete(
    title: String,
    content: String,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    confirmLabel: String = "삭제"
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text(content) },
        confirmButton = { TextButton(onClick = onDelete) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("취소") } }
    )
}

@Composable
private fun StudyScreen(
    set: StudySet,
    sessionIds: List<String>,
    mode: StudyMode,
    onGrade: (String, Boolean) -> Unit,
    onClose: () -> Unit
) {
    val cards = sessionIds.mapNotNull { id -> set.cards.firstOrNull { it.id == id } }
    var position by remember(sessionIds, mode) { mutableIntStateOf(0) }
    var blankDifficulty by remember(sessionIds, mode) { mutableStateOf(BlankDifficulty.NORMAL) }
    var blankDifficultyConfirmed by remember(sessionIds, mode) { mutableStateOf(false) }

    val context = LocalContext.current
    var ttsReady by remember { mutableStateOf(false) }
    val tts = remember {
        TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }
    }
    LaunchedEffect(ttsReady) {
        if (ttsReady) tts.language = Locale.US
    }
    DisposableEffect(tts) {
        onDispose {
            tts.stop()
            tts.shutdown()
        }
    }
    fun speak(english: String) {
        if (ttsReady) tts.speak(english, TextToSpeech.QUEUE_FLUSH, null, "sentencelab")
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (mode == StudyMode.BLANK && !blankDifficultyConfirmed) {
            Text("빈칸 난이도", style = MaterialTheme.typography.headlineSmall)
            BlankDifficulty.entries.forEach { level ->
                OutlinedButton(onClick = { blankDifficulty = level }, modifier = Modifier.fillMaxWidth()) {
                    Text((if (blankDifficulty == level) "✓ " else "") + level.label + " · 약 " + (level.fraction * 100).toInt() + "%")
                }
            }
            Button(onClick = { blankDifficultyConfirmed = true }) { Text("학습 시작") }
        } else if (position >= cards.size) {
            Text("학습 완료", style = MaterialTheme.typography.headlineMedium)
            Text(cards.size.toString() + "개 문장을 학습했습니다.")
            Button(onClick = onClose) { Text("세트로 돌아가기") }
        } else {
            val card = cards[position]
            Text(
                mode.label + " · " + (position + 1) + " / " + cards.size,
                style = MaterialTheme.typography.titleMedium
            )
            LinearProgressIndicator(
                progress = { (position + 1).toFloat() / cards.size },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                card.korean.ifBlank { "한국어 해석이 없는 원문입니다." },
                style = MaterialTheme.typography.headlineSmall
            )
            key(card.id) {
                when (mode) {
                    StudyMode.FLASHCARD -> FlashcardQuestion(
                        card = card,
                        onSpeak = { speak(card.english) },
                        speechReady = ttsReady,
                        onGrade = { isCorrect ->
                            onGrade(card.id, isCorrect)
                            position += 1
                        }
                    )
                    StudyMode.ORDER -> OrderQuestion(
                        card = card,
                        onGrade = { onGrade(card.id, it) },
                        onNext = { position += 1 }
                    )
                    StudyMode.SPELL -> WrittenQuestion(
                        card = card,
                        prompt = "영어 문장 전체를 입력하세요.",
                        correctAnswer = card.english,
                        onGrade = { onGrade(card.id, it) },
                        onNext = { position += 1 }
                    )
                    StudyMode.BLANK -> BlankQuestion(
                        card = card,
                        difficulty = blankDifficulty,
                        onGrade = { onGrade(card.id, it) },
                        onNext = { position += 1 }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { speak(card.english) }, enabled = ttsReady) {
                Text("영어 듣기")
            }
        }
    }
}

@Composable
private fun FlashcardQuestion(
    card: Sentence,
    onSpeak: () -> Unit,
    speechReady: Boolean,
    onGrade: (Boolean) -> Unit
) {
    var revealed by remember(card.id) { mutableStateOf(false) }
    if (!revealed) {
        Button(onClick = { revealed = true }) { Text("영어 문장 보기") }
    } else {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(card.english, style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = onSpeak, enabled = speechReady) { Text("발음 듣기") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { onGrade(false) }) { Text("다시 볼래요") }
            Button(onClick = { onGrade(true) }) { Text("알아요") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OrderQuestion(
    card: Sentence,
    onGrade: (Boolean) -> Unit,
    onNext: () -> Unit
) {
    val groups = remember(card.id) { com.sentencelab.app.model.orderWordGroups(card.english) }
    var groupIndex by remember(card.id) { mutableIntStateOf(0) }
    var selected by remember(card.id, groupIndex) { mutableStateOf<List<Int>>(emptyList()) }
    var hadMistake by remember(card.id) { mutableStateOf(false) }
    var lastTapWrong by remember(card.id, groupIndex) { mutableStateOf(false) }
    var complete by remember(card.id) { mutableStateOf(false) }

    if (complete) {
        CheckResult(!hadMistake, card.english, onNext)
        return
    }
    if (groups.isEmpty()) {
        Text("이 문장은 배열할 단어가 없습니다.")
        Button(onClick = onNext) { Text("다음 문장") }
        return
    }

    val words = groups[groupIndex]
    val choices = remember(card.id, groupIndex) {
        val indices = words.indices.toList()
        val shuffled = indices.shuffled()
        if (shuffled == indices && shuffled.size > 1) shuffled.drop(1) + shuffled.first()
        else shuffled
    }

    Text("단어를 누르면 바로 정답을 확인합니다.")
    if (groups.size > 1) {
        Text("문장 구간 " + (groupIndex + 1) + " / " + groups.size)
        if (groupIndex > 0) {
            Text("완료: " + groups.take(groupIndex).flatten().joinToString(" "))
        }
    }
    // Answer area stays fixed as the completed words accumulate.
    Card(modifier = Modifier.fillMaxWidth().height(220.dp)) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Text(
                "배열한 단어 " + selected.size + " / " + words.size,
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                if (selected.isEmpty()) {
                    Text(
                        "올바른 순서로 단어를 선택하세요.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    selected.forEach { index ->
                        OutlinedButton(onClick = {}, enabled = false) {
                            Text(words[index])
                        }
                    }
                }
            }
        }
    }
    // Buttons never leave the word bank: completed words are disabled in place.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        choices.forEach { index ->
            key(index) {
                FilledTonalButton(
                    enabled = index !in selected,
                    onClick = {
                        val expected = words[selected.size]
                        if (!com.sentencelab.app.model.matchesNextWord(expected, words[index])) {
                            hadMistake = true
                            lastTapWrong = true
                        } else {
                            lastTapWrong = false
                            val next = selected + index
                            if (next.size == words.size) {
                                if (groupIndex == groups.lastIndex) {
                                    complete = true
                                    onGrade(!hadMistake)
                                } else {
                                    groupIndex += 1
                                }
                            } else {
                                selected = next
                            }
                        }
                    }
                ) { Text(words[index]) }
            }
        }
    }
    if (lastTapWrong) {
        Text(
            "순서가 다른 단어입니다. 다시 선택하세요.",
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun WrittenQuestion(
    card: Sentence,
    prompt: String,
    correctAnswer: String,
    onGrade: (Boolean) -> Unit,
    onNext: () -> Unit
) {
    var answer by remember(card.id) { mutableStateOf("") }
    var result by remember(card.id) { mutableStateOf<Boolean?>(null) }
    Text(prompt)
    OutlinedTextField(
        value = answer,
        onValueChange = { if (result == null) answer = it },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
        label = { Text("내 답안") },
        readOnly = result != null
    )
    if (result == null) {
        Button(
            enabled = answer.isNotBlank(),
            onClick = {
                val correct = normalized(answer) == normalized(correctAnswer)
                onGrade(correct)
                result = correct
            }
        ) { Text("정답 확인") }
    } else {
        CheckResult(result == true, card.english, onNext)
    }
}

@Composable
private fun BlankQuestion(
    card: Sentence,
    difficulty: BlankDifficulty,
    onGrade: (Boolean) -> Unit,
    onNext: () -> Unit
) {
    val blanks = remember(card.id, card.english, difficulty) { blankWords(card.english, difficulty) }
    var answers by remember(card.id, blanks) { mutableStateOf(List(blanks.size) { "" }) }
    var result by remember(card.id, blanks) { mutableStateOf<List<Boolean>?>(null) }
    if (blanks.isEmpty()) {
        Text("빈칸을 만들 수 없는 문장입니다.")
        Button(onClick = onNext) { Text("다음 문장") }
        return
    }
    Text("빈칸 ${blanks.size}개를 모두 채우세요.")
    Text(blankPrompt(card.english, blanks), style = MaterialTheme.typography.titleLarge)
    blanks.forEachIndexed { index, word ->
        val graded = result?.get(index)
        OutlinedTextField(
            value = answers[index],
            onValueChange = { value ->
                if (result == null) answers = answers.toMutableList().also { it[index] = value }
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("빈칸 ${index + 1}") },
            singleLine = true,
            readOnly = result != null,
            isError = graded == false
        )
        if (graded != null) {
            Text(
                if (graded) "✓ 정답" else "✗ 정답: ${word.answer}",
                color = if (graded) {
                    if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF176B35)
                } else MaterialTheme.colorScheme.error
            )
        }
    }
    if (result == null) {
        Button(
            enabled = answers.all { it.isNotBlank() },
            onClick = {
                val grades = blanks.mapIndexed { index, word -> matchesBlank(word.answer, answers[index]) }
                result = grades
                onGrade(grades.all { it })
            }
        ) { Text("정답 확인") }
    } else {
        Text("${result!!.count { it }} / ${blanks.size} 정답")
        CheckResult(result!!.all { it }, card.english, onNext)
    }
}

@Composable
private fun CheckResult(correct: Boolean, english: String, onNext: () -> Unit) {
    Text(
        if (correct) "정답입니다." else "오답입니다. 복습 목록에 추가했어요.",
        color = if (correct) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        fontWeight = FontWeight.Bold
    )
    Text("정답: " + english)
    Button(onClick = onNext) { Text("다음") }
}
