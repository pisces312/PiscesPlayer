package com.lostf1sh.pixelplayeross.utils.japanese

import com.atilika.kuromoji.ipadic.Tokenizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Proves that the dictionary files the app downloads produce exactly the same token stream as
 * the copy that used to be bundled inside the APK. The test reads the bundled resources from the
 * JVM classpath (Gradle does not apply the APK packaging excludes here), writes them to a temp
 * directory, and feeds them through [ExternalDictionaryTokenizer].
 */
class ExternalDictionaryTokenizerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun isComplete_rejectsMissingOrEmptyFiles() {
        val directory = temporaryFolder.newFolder("empty")
        assertFalse(ExternalDictionaryTokenizer.isComplete(directory))

        ExternalDictionaryTokenizer.REQUIRED_FILES.forEach {
            File(directory, it).writeBytes(ByteArray(0))
        }
        assertFalse(ExternalDictionaryTokenizer.isComplete(directory))

        ExternalDictionaryTokenizer.REQUIRED_FILES.forEach {
            File(directory, it).writeBytes(byteArrayOf(1))
        }
        assertTrue(ExternalDictionaryTokenizer.isComplete(directory))
    }

    @Test
    fun externalDictionary_matchesBundledTokenizer() {
        val directory = temporaryFolder.newFolder("ipadic")
        val allExtracted = ExternalDictionaryTokenizer.REQUIRED_FILES.all { name ->
            val resource = "com/atilika/kuromoji/ipadic/$name"
            val input = javaClass.classLoader?.getResourceAsStream(resource) ?: return@all false
            input.use { stream ->
                File(directory, name).outputStream().use { output -> stream.copyTo(output) }
            }
            true
        }
        assumeTrue("kuromoji-ipadic resources are not on the test classpath", allExtracted)
        assertTrue(ExternalDictionaryTokenizer.isComplete(directory))

        val bundled = Tokenizer()
        val external = ExternalDictionaryTokenizer.build(directory)

        val samples = listOf(
            "今日はいい天気ですね。",
            "君の名は。",
            "東京スカイツリーに行きたい",
            "ひらがなカタカナ漢字",
            "ｺﾝﾆﾁﾜ、世界！",
            "unknownwordｘｙｚ",
            "切なくて、さようなら",
        )

        samples.forEach { text ->
            assertEquals(
                "Token mismatch for \"$text\"",
                describe(bundled.tokenize(text)),
                describe(external.tokenize(text)),
            )
        }
    }

    private fun describe(tokens: List<com.atilika.kuromoji.ipadic.Token>): List<String> =
        tokens.map { token ->
            listOf(
                token.surface,
                token.allFeatures,
            ).joinToString("|")
        }
}
