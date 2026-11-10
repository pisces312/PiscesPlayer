package com.pisces312.piscesplayer.utils.japanese

import com.atilika.kuromoji.dict.CharacterDefinitions
import com.atilika.kuromoji.dict.ConnectionCosts
import com.atilika.kuromoji.dict.InsertedDictionary
import com.atilika.kuromoji.dict.TokenInfoDictionary
import com.atilika.kuromoji.dict.UnknownDictionary
import com.atilika.kuromoji.ipadic.Tokenizer
import com.atilika.kuromoji.trie.DoubleArrayTrie
import com.atilika.kuromoji.util.ResourceResolver
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Builds an ipadic [Tokenizer] from the dictionary files stored outside the APK.
 *
 * kuromoji-ipadic's stock builder always resolves its resources through the class loader, and
 * the app excludes those .bin files from packaging (they are downloaded on demand instead).
 * This builder reproduces kuromoji-ipadic 0.9.0's protected loading routine, resolving every
 * file from [dictionaryDirectory]. The penalty values mirror the defaults assigned in
 * [Tokenizer.Builder]'s constructor; keep them in sync when upgrading kuromoji.
 */
internal object ExternalDictionaryTokenizer {

    const val DICTIONARY_VERSION = "kuromoji-ipadic-0.9.0"

    val REQUIRED_FILES = listOf(
        "characterDefinitions.bin",
        "connectionCosts.bin",
        "doubleArrayTrie.bin",
        "tokenInfoDictionary.bin",
        "tokenInfoFeaturesMap.bin",
        "tokenInfoPartOfSpeechMap.bin",
        "tokenInfoTargetMap.bin",
        "unknownDictionary.bin",
    )

    fun isComplete(directory: File): Boolean =
        directory.isDirectory && REQUIRED_FILES.all { name ->
            File(directory, name).let { it.isFile && it.length() > 0L }
        }

    fun build(dictionaryDirectory: File): Tokenizer {
        require(isComplete(dictionaryDirectory)) {
            "Japanese dictionary is incomplete: $dictionaryDirectory"
        }
        return FileDictionaryTokenizerBuilder(FileResourceResolver(dictionaryDirectory)).build()
    }

    private class FileResourceResolver(
        private val directory: File
    ) : ResourceResolver {
        override fun resolve(name: String): InputStream {
            val file = File(directory, name)
            if (!file.isFile) throw IOException("Missing dictionary resource: $name")
            return FileInputStream(file)
        }
    }

    private class FileDictionaryTokenizerBuilder(
        private val fileResolver: ResourceResolver
    ) : Tokenizer.Builder() {

        override fun loadDictionaries() {
            try {
                penalties = mutableListOf(2, 3000, 7, 1700)
                resolver = fileResolver
                doubleArrayTrie = DoubleArrayTrie.newInstance(fileResolver)
                connectionCosts = ConnectionCosts.newInstance(fileResolver)
                tokenInfoDictionary = TokenInfoDictionary.newInstance(fileResolver)
                characterDefinitions = CharacterDefinitions.newInstance(fileResolver)
                unknownDictionary =
                    UnknownDictionary.newInstance(fileResolver, characterDefinitions, totalFeatures)
                insertedDictionary = InsertedDictionary(totalFeatures)
            } catch (e: Exception) {
                throw RuntimeException("Could not load dictionaries.", e)
            }
        }
    }
}
