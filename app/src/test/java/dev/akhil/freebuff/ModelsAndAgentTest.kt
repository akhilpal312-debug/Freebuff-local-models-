package dev.akhil.freebuff

import dev.akhil.freebuff.agent.ContentExtractor
import dev.akhil.freebuff.agent.IntentParser
import dev.akhil.freebuff.agent.TaskIntent
import dev.akhil.freebuff.core.Format
import dev.akhil.freebuff.inference.ChatTurn
import dev.akhil.freebuff.inference.PromptRenderer
import dev.akhil.freebuff.models.GgufInspector
import dev.akhil.freebuff.models.SupportedArchitectures
import dev.akhil.freebuff.voice.tts.HindiPhonemizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GgufInspectorTest {

    /** Writes a minimal but structurally valid GGUF header. */
    private fun ggufFile(
        architecture: String = "qwen3",
        fileType: Int = 15,
        contextLength: Int = 4096,
    ): File {
        val buffer = ByteArrayOutputStream()
        fun le32(value: Int) = buffer.write(
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array(),
        )
        fun le64(value: Long) = buffer.write(
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array(),
        )
        fun str(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            le64(bytes.size.toLong())
            buffer.write(bytes)
        }

        le32(0x46554747) // "GGUF"
        le32(3)
        le64(4) // tensor count
        le64(6) // kv count

        str("general.architecture"); le32(8); str(architecture)
        str("general.name"); le32(8); str("test-model")
        str("general.file_type"); le32(4); le32(fileType)
        str("$architecture.context_length"); le32(4); le32(contextLength)
        str("tokenizer.ggml.tokens"); le32(9); le32(8); le64(3); str("a"); str("b"); str("c")
        str("general.alignment"); le32(4); le32(32)

        val file = File.createTempFile("test", ".gguf")
        file.writeBytes(buffer.toByteArray())
        return file
    }

    @Test
    fun readsArchitectureAndQuantisation() {
        val file = ggufFile(architecture = "qwen3", fileType = 15)
        val info = GgufInspector.inspect(file).getOrThrow()
        assertEquals("qwen3", info.architecture)
        assertEquals("Q4_K_M", info.quantization)
        assertEquals(4096, info.contextLength)
        assertEquals("test-model", info.name)
        assertEquals(4L, info.tensorCount)
        assertTrue(info.architectureSupported)
    }

    @Test
    fun skipsNestedArraysWithoutCorruptingLaterKeys() {
        // tokenizer.ggml.tokens is an array of 3 strings placed before the last key;
        // a wrong skip would misread general.alignment.
        val info = GgufInspector.inspect(ggufFile()).getOrThrow()
        assertEquals(4096, info.contextLength)
    }

    @Test
    fun rejectsNonGgufFiles() {
        val file = File.createTempFile("bad", ".gguf")
        file.writeText("this is not a model")
        val result = GgufInspector.inspect(file)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("magic", ignoreCase = true))
    }

    @Test
    fun unsupportedArchitectureIsReported() {
        assertFalse(SupportedArchitectures.isSupported("bert"))
        assertTrue(SupportedArchitectures.isSupported("gemma3"))
        assertTrue(SupportedArchitectures.isSupported("qwen2"))
        val message = SupportedArchitectures.explanation("bert")
        assertTrue(message.contains("not supported"))
    }
}

class PromptRendererTest {

    @Test
    fun gemmaUsesTurnTokens() {
        val rendered = PromptRenderer.render(
            listOf(
                ChatTurn("system", "be brief"),
                ChatTurn("user", "hello"),
            ),
            PromptRenderer.Template.GEMMA,
        )
        assertTrue(rendered.contains("<start_of_turn>user"))
        assertTrue(rendered.contains("<end_of_turn>"))
        assertTrue(rendered.endsWith("<start_of_turn>model\n"))
    }

    @Test
    fun chatmlClosesWithAssistantHeader() {
        val rendered = PromptRenderer.render(
            listOf(ChatTurn("user", "hi")),
            PromptRenderer.Template.CHATML,
        )
        assertTrue(rendered.contains("<|im_start|>user"))
        assertTrue(rendered.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun thinkBlocksAreStrippedFromQwen3Output() {
        val open = "<thi" + "nk>"
        val close = "</thi" + "nk>"
        val raw = open + "reasoning here" + close + "final answer"
        assertEquals("final answer", PromptRenderer.stripThinkBlocks(raw))
    }

    @Test
    fun unterminatedThinkBlockIsDetected() {
        val open = "<thi" + "nk>"
        assertTrue(PromptRenderer.hasUnterminatedThink(open + "still thinking"))
    }
}

class AgentIntentTest {

    @Test
    fun classifiesWebsiteAndAndroidRequests() {
        assertEquals(TaskIntent.CREATE_WEBSITE, IntentParser.classify("Create a website for a bakery"))
        assertEquals(TaskIntent.CREATE_ANDROID_APP, IntentParser.classify("Build an Android app for notes"))
        assertEquals(TaskIntent.EXPLAIN, IntentParser.classify("Explain what a GGUF file is"))
        assertEquals(TaskIntent.NEEDS_CLARIFICATION, IntentParser.classify("hmm"))
    }

    @Test
    fun plansWebsiteWorkWithVerificationSteps() {
        val plan = IntentParser.plan(TaskIntent.CREATE_WEBSITE, "create a site")
        assertEquals(5, plan.size)
        assertTrue(plan.any { it.kind == dev.akhil.freebuff.agent.StepKind.VERIFY })
        assertTrue(plan.any { it.kind == dev.akhil.freebuff.agent.StepKind.CHECK })
    }

    @Test
    fun extractsLargestCodeBlock() {
        val reply = "Here you go:\n```kotlin\nval a = 1\n```\nand\n```kotlin\nval b = 2\nval c = 3\n```\n"
        assertEquals("val b = 2\nval c = 3", ContentExtractor.extract(reply, "Main.kt"))
    }

    @Test
    fun extractsHtmlBlock() {
        val reply = "Text\n```html\n<!doctype html>\n<html><body>hi</body></html>\n```"
        val extracted = ContentExtractor.extract(reply, "index.html")
        assertTrue(extracted.startsWith("<!doctype html>"))
    }
}

class HindiPhonemizerTest {

    @Test
    fun producesConsonantVowelPhonemes() {
        val result = HindiPhonemizer.phonemize("नमस्ते")
        assertTrue(result.isNotBlank())
        // न -> n, म -> m, स -> s, त -> t
        assertTrue(result.contains("n"))
        assertTrue(result.contains("m"))
    }

    @Test
    fun detectsDevanagari() {
        assertTrue(HindiPhonemizer.isDevanagari("नमस्ते"))
        assertFalse(HindiPhonemizer.isDevanagari("hello"))
        assertFalse(HindiPhonemizer.isDevanagari("namaste"))
    }
}

class FormatTest {

    @Test
    fun formatsByteSizes() {
        assertEquals("0 B", Format.bytes(0))
        assertEquals("512 B", Format.bytes(512))
        assertTrue(Format.bytes(253_114_880).startsWith("241"))
        assertTrue(Format.bytes(253_114_880).endsWith("MB"))
    }

    @Test
    fun slugIsFilesystemSafe() {
        assertEquals("hello-world", Format.slug("Hello, World!"))
        assertEquals("item", Format.slug("!!!"))
    }
}
