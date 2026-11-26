package com.sentencelab.app.data

import com.sentencelab.app.model.Sentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KoreanTranslatorTest {
    @Test fun onlyMissingKoreanDefinitionsAreCandidates() {
        val cards = listOf(
            Sentence(id = "empty", english = "The ocean is important.", korean = ""),
            Sentence(id = "filled", english = "A subscription is flexible.", korean = "구독은 유연하다."),
            Sentence(id = "korean", english = "한국어 제목", korean = ""),
            Sentence(id = "spaces", english = "This still needs a translation.", korean = "  ")
        )
        assertEquals(listOf("empty", "spaces"), KoreanTranslator.missing(cards).map { it.id })
    }

    @Test fun noMissingCardsMeansNoTranslationWork() {
        assertTrue(KoreanTranslator.missing(listOf(
            Sentence(id = "done", english = "Hello!", korean = "안녕!")
        )).isEmpty())
    }
}
