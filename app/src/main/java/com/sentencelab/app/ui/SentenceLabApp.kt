package com.sentencelab.app.ui

import android.speech.tts.TextToSpeech
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sentencelab.app.data.StudyStorage
import com.sentencelab.app.model.BulkEntry
import com.sentencelab.app.model.Sentence
import com.sentencelab.app.model.StudyMode
import com.sentencelab.app.model.StudySet
import com.sentencelab.app.model.normalized
import com.sentencelab.app.model.parseBulk
import com.sentencelab.app.model.parseChunks
import com.sentencelab.app.model.validChunks
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SentenceLabApp() {
    val context = LocalContext.current
    val storage = remember { StudyStorage(context.applicationContext) }
    var sets by remember { mutableStateOf(storage.load()) }
    var currentSetId by remember { mutableStateOf<String?>(null) }
    var studyMode by remember { mutableStateOf<StudyMode?>(null) }
    var sessionIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var newSetOpen by remember { mutableStateOf(false) }
    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Sentence?>(null) }
    var importing by remember { mutableStateOf(false) }
    var deletingCard by remember { mutableStateOf<Sentence?>(null) }
    var deletingSet by remember { mutableStateOf(false) }

    fun save(updated: List<StudySet>) {
        storage.save(updated)
        sets = updated
    }

    val selected = sets.firstOrNull { it.id == currentSetId }
    val title = if (studyMode != null) "문장 학습"
        else selected?.title ?: "SentenceLab"

    fun goBack() {
        if (studyMode != null) studyMode = null
        else currentSetId = null
    }
    BackHandler(enabled = selected != null) { goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
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
                    onOpen = { currentSetId = it }
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
                                    needsReview = !isCorrect
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
                    onDeleteCard = { deletingCard = it },
                    onDeleteSet = { deletingSet = true },
                    onStudy = { mode, reviewOnly ->
                        sessionIds = selected.cards.filter { !reviewOnly || it.needsReview }
                            .map { it.id }
                        studyMode = mode
                    }
                )
            }
        }
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
    onOpen: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("내 문장 세트", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("네트워크 연결 없이 모든 학습 내용을 기기에 저장합니다.")
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
                                set.cards.count { it.needsReview }.toString() + "개",
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
    onDeleteCard: (Sentence) -> Unit,
    onDeleteSet: () -> Unit,
    onStudy: (StudyMode, Boolean) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAdd) { Text("+ 문장 추가") }
            OutlinedButton(onClick = onImport) { Text("대량 등록") }
        }
        Text("학습 모드", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StudyMode.entries.forEach { mode ->
                FilledTonalButton(
                    enabled = set.cards.isNotEmpty(),
                    onClick = { onStudy(mode, false) }
                ) { Text(mode.label) }
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
            TextButton(onClick = onDeleteSet) { Text("세트 삭제") }
        }
        if (set.cards.isEmpty()) Text("문장을 추가하면 학습을 시작할 수 있습니다.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(set.cards, key = { it.id }) { card ->
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(card.english, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(4.dp))
                        Text(card.korean, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (card.needsReview) {
                            Text("복습 필요", color = MaterialTheme.colorScheme.error)
                        }
                        Row {
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
    val valid = english.isNotBlank() && korean.isNotBlank() && validChunks(english, chunks)
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
                    label = { Text("한국어 뜻") },
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
    onDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text(content) },
        confirmButton = { TextButton(onClick = onDelete) { Text("삭제") } },
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
        modifier = Modifier.fillMaxSize().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (position >= cards.size) {
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
            Text(card.korean, style = MaterialTheme.typography.headlineSmall)
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
    val parts = remember(card.id) {
        card.chunks.ifEmpty { card.english.trim().split(Regex("\\s+")) }
    }
    val choices = remember(card.id) {
        val original = parts.indices.toList()
        val shuffled = original.shuffled()
        if (shuffled == original && shuffled.size > 1) shuffled.drop(1) + shuffled.first()
        else shuffled
    }
    var selected by remember(card.id) { mutableStateOf<List<Int>>(emptyList()) }
    var result by remember(card.id) { mutableStateOf<Boolean?>(null) }
    Text("아래 조각을 순서대로 누르세요.")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (selected.isEmpty()) Text("여기에 문장을 완성하세요.")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                selected.forEach { index ->
                    OutlinedButton(
                        onClick = { if (result == null) selected = selected - index },
                        enabled = result == null
                    ) { Text(parts[index]) }
                }
            }
        }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        choices.filterNot { it in selected }.forEach { index ->
            FilledTonalButton(
                onClick = { selected = selected + index },
                enabled = result == null
            ) { Text(parts[index]) }
        }
    }
    if (result == null) {
        Button(
            enabled = selected.size == parts.size,
            onClick = {
                val answer = selected.joinToString(" ") { parts[it] }
                val correct = normalized(answer) == normalized(card.english)
                onGrade(correct)
                result = correct
            }
        ) { Text("정답 확인") }
    } else {
        CheckResult(result == true, card.english, onNext)
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
    onGrade: (Boolean) -> Unit,
    onNext: () -> Unit
) {
    val match = remember(card.id) {
        Regex("[\\p{L}]{2,}").find(card.english)
    }
    if (match == null) {
        Text("빈칸을 만들 수 없는 문장입니다.")
        Button(onClick = onNext) { Text("다음 문장") }
    } else {
        Text("빈칸에 들어갈 영어 단어를 입력하세요.")
        Text(
            card.english.replaceRange(match.range, "_____"),
            style = MaterialTheme.typography.titleLarge
        )
        WrittenQuestion(
            card = card,
            prompt = "빠진 단어는 무엇일까요?",
            correctAnswer = match.value,
            onGrade = onGrade,
            onNext = onNext
        )
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
