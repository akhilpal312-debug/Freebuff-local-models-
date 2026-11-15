package dev.akhil.freebuff.voice.tts

/**
 * Rule-based Devanagari → phoneme conversion for the Piper Hindi voice.
 *
 * Devanagari is close to phonemic, so a rule-based mapping is genuinely usable
 * for Hindi TTS: consonants carry an inherent schwa, matras replace it, and the
 * virama suppresses it. Two well-known irregularities are handled explicitly —
 * word-final schwa deletion and the anusvara, which is pronounced as a nasal
 * homorganic to the following consonant.
 *
 * This is intentionally not presented as a general G2P system. Romanised Hindi
 * ("Hinglish") is transcribed by Whisper as Latin text and is spoken by the
 * English voice instead.
 */
object HindiPhonemizer {

    private val independentVowels = mapOf(
        'अ' to "ə", 'आ' to "aː", 'इ' to "ɪ", 'ई' to "iː", 'उ' to "ʊ", 'ऊ' to "uː",
        'ऋ' to "r̩", 'ए' to "eː", 'ऐ' to "ɛː", 'ओ' to "oː", 'औ' to "ɔː",
        'ऍ' to "ɛ", 'ऑ' to "ɔ",
    )

    private val matras = mapOf(
        'ा' to "aː", 'ि' to "ɪ", 'ी' to "iː", 'ु' to "ʊ", 'ू' to "uː",
        'ृ' to "r̩", 'े' to "eː", 'ै' to "ɛː", 'ो' to "oː", 'ौ' to "ɔː",
        'ॅ' to "ɛ", 'ॉ' to "ɔ", 'ॆ' to "e", 'ॊ' to "o",
    )

    private val consonants = mapOf(
        'क' to "k", 'ख' to "kʰ", 'ग' to "g", 'घ' to "gʱ", 'ङ' to "ŋ",
        'च' to "tʃ", 'छ' to "tʃʰ", 'ज' to "dʒ", 'झ' to "dʒʱ", 'ञ' to "ɲ",
        'ट' to "ʈ", 'ठ' to "ʈʰ", 'ड' to "ɖ", 'ढ' to "ɖʱ", 'ण' to "ɳ",
        'त' to "t", 'थ' to "tʰ", 'द' to "d", 'ध' to "dʱ", 'न' to "n",
        'प' to "p", 'फ' to "pʰ", 'ब' to "b", 'भ' to "bʱ", 'म' to "m",
        'य' to "j", 'र' to "ɾ", 'ल' to "l", 'ळ' to "ɭ", 'व' to "ʋ",
        'श' to "ʃ", 'ष' to "ʂ", 'स' to "s", 'ह' to "h",
    )

    /** Nukta combinations (क़ ख़ ग़ ज़ ड़ ढ़ फ़). */
    private val nukta = mapOf(
        'क' to "q", 'ख' to "x", 'ग' to "ɣ", 'ज' to "z", 'ड' to "ɽ", 'ढ' to "ɽʱ", 'फ' to "f",
    )

    private const val VIRAMA = '्'
    private const val NUKTA_MARK = '़'
    private const val ANUSVARA = 'ं'
    private const val CANDRABINDU = 'ँ'
    private const val VISARGA = 'ः'
    private const val INHERENT = "ə"

    /** Returns a phoneme string using symbols that Piper's Hindi voice understands. */
    fun phonemize(text: String): String {
        val output = StringBuilder()
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        words.forEachIndexed { wordIndex, word ->
            val phonemes = phonemizeWord(word)
            if (phonemes.isNotBlank()) {
                output.append(phonemes)
                if (wordIndex != words.lastIndex) output.append(' ')
            }
            if (word.any { it in ",.!?;:" } && wordIndex != words.lastIndex) output.append("  ")
        }
        return output.toString().trim()
    }

    private fun phonemizeWord(word: String): String {
        val units = mutableListOf<String>()
        val chars = word.toList()
        var index = 0
        while (index < chars.size) {
            val char = chars[index]
            when {
                char in "।॥" -> {
                    outputPunctuation(units)
                    index++
                }

                char in ",.!?;:" -> {
                    index++
                }

                char.isDigit() -> {
                    units += devanagariDigit(char)
                    index++
                }

                char in consonants -> {
                    var symbol = consonants.getValue(char)
                    var consumed = 1
                    if (index + 1 < chars.size && chars[index + 1] == NUKTA_MARK) {
                        symbol = nukta[char] ?: symbol
                        consumed = 2
                    }
                    units += symbol
                    val nextIndex = index + consumed
                    when {
                        nextIndex < chars.size && chars[nextIndex] == VIRAMA -> {
                            // Explicit halant: cluster continues with no vowel.
                            index = nextIndex + 1
                        }
                        nextIndex < chars.size && chars[nextIndex] in matras -> {
                            units += matras.getValue(chars[nextIndex])
                            index = nextIndex + 1
                        }
                        nextIndex < chars.size && (chars[nextIndex] == ANUSVARA || chars[nextIndex] == CANDRABINDU) -> {
                            units += nasalFor(chars.getOrNull(nextIndex + 1))
                            index = nextIndex + 1
                        }
                        else -> {
                            units += INHERENT
                            index = nextIndex
                        }
                    }
                }

                char in independentVowels -> {
                    units += independentVowels.getValue(char)
                    var consumed = 1
                    if (index + 1 < chars.size && (chars[index + 1] == ANUSVARA || chars[index + 1] == CANDRABINDU)) {
                        units += nasalFor(chars.getOrNull(index + 2))
                        consumed = 2
                    }
                    index += consumed
                }

                char == ANUSVARA || char == CANDRABINDU -> {
                    units += nasalFor(chars.getOrNull(index + 1))
                    index++
                }

                char == VISARGA -> {
                    units += "h"
                    index++
                }

                char == VIRAMA -> index++

                char.isLetter() && char.code < 128 -> {
                    units += char.lowercaseChar().toString()
                    index++
                }

                else -> index++
            }
        }

        // Word-final schwa deletion is standard in spoken Hindi.
        if (units.size > 1 && units.last() == INHERENT) units.removeAt(units.size - 1)
        return units.joinToString("")
    }

    private fun outputPunctuation(units: MutableList<String>) {
        if (units.isNotEmpty() && units.last() != "|") units += "|"
    }

    private fun nasalFor(next: Char?): String = when (next) {
        'क', 'ख', 'ग', 'घ' -> "ŋ"
        'च', 'छ', 'ज', 'झ' -> "ɲ"
        'ट', 'ठ', 'ड', 'ढ' -> "ɳ"
        'प', 'फ', 'ब', 'भ', 'म' -> "m"
        else -> "n"
    }

    private fun devanagariDigit(char: Char): String =
        mapOf(
            '०' to "0", '१' to "1", '२' to "2", '३' to "3", '४' to "4",
            '५' to "5", '६' to "6", '७' to "7", '८' to "8", '९' to "9",
        )[char] ?: ""

    /** True when the text contains Devanagari characters and should use the Hindi voice. */
    fun isDevanagari(text: String): Boolean =
        text.any { it.code in 0x0900..0x097F }
}
