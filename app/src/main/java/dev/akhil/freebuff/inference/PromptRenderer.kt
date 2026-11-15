package dev.akhil.freebuff.inference

import dev.akhil.freebuff.models.SupportedArchitectures

/**
 * Renders turns into each model family's chat template.
 *
 * Templates are selected from the GGUF architecture, not from the display name,
 * so a renamed file still uses the right format. Unknown architectures fall back
 * to ChatML, which is the most widely tolerated format.
 */
object PromptRenderer {

    enum class Template { GEMMA, CHATML, LLAMA2, PLAIN }

    fun templateFor(modelName: String, architecture: String? = null): Template {
        val arch = architecture?.lowercase()
            ?: guessArchitecture(modelName)
        return when {
            arch.startsWith("gemma") -> Template.GEMMA
            arch.startsWith("qwen") -> Template.CHATML
            arch.startsWith("llama") && arch.contains("2") -> Template.LLAMA2
            else -> Template.CHATML
        }
    }

    private fun guessArchitecture(modelName: String): String {
        val name = modelName.lowercase()
        return when {
            name.contains("gemma") -> "gemma"
            name.contains("qwen3") -> "qwen3"
            name.contains("qwen") -> "qwen2"
            name.contains("llama") -> "llama"
            name.contains("phi") -> "phi3"
            name.contains("mistral") -> "mistral"
            else -> "unknown"
        }
    }

    fun render(turns: List<ChatTurn>, modelName: String, architecture: String? = null): String =
        render(turns, templateFor(modelName, architecture))

    fun render(turns: List<ChatTurn>, template: Template): String {
        val builder = StringBuilder()
        when (template) {
            Template.GEMMA -> {
                turns.forEach { turn ->
                    when (turn.role) {
                        ChatTurn.ROLE_SYSTEM ->
                            builder.append(turn.content.trim()).append("\n\n")
                        ChatTurn.ROLE_USER ->
                            builder.append("<start_of_turn>user\n").append(turn.content.trim())
                                .append("<end_of_turn>\n")
                        ChatTurn.ROLE_ASSISTANT ->
                            builder.append("<start_of_turn>model\n").append(turn.content.trim())
                                .append("<end_of_turn>\n")
                        else -> Unit
                    }
                }
                builder.append("<start_of_turn>model\n")
            }

            Template.CHATML -> {
                turns.forEach { turn ->
                    val role = when (turn.role) {
                        ChatTurn.ROLE_SYSTEM -> "system"
                        ChatTurn.ROLE_USER -> "user"
                        ChatTurn.ROLE_ASSISTANT -> "assistant"
                        else -> "user"
                    }
                    builder.append("<|im_start|>").append(role).append('\n')
                    builder.append(turn.content.trim()).append("<|im_end|>\n")
                }
                builder.append("<|im_start|>assistant\n")
            }

            Template.LLAMA2 -> {
                var firstSystem = true
                turns.forEach { turn ->
                    when (turn.role) {
                        ChatTurn.ROLE_SYSTEM ->
                            if (firstSystem) {
                                builder.append("<<SYS>>\n").append(turn.content.trim())
                                    .append("\n<</SYS>>\n\n")
                                firstSystem = false
                            }
                        ChatTurn.ROLE_USER ->
                            builder.append("[INST] ").append(turn.content.trim()).append(" [/INST] ")
                        ChatTurn.ROLE_ASSISTANT ->
                            builder.append(turn.content.trim()).append(" </s><s>")
                        else -> Unit
                    }
                }
            }

            Template.PLAIN -> {
                turns.forEach { turn ->
                    val label = when (turn.role) {
                        ChatTurn.ROLE_SYSTEM -> "System"
                        ChatTurn.ROLE_USER -> "User"
                        ChatTurn.ROLE_ASSISTANT -> "Assistant"
                        else -> "User"
                    }
                    builder.append(label).append(": ").append(turn.content.trim()).append('\n')
                }
                builder.append("Assistant: ")
            }
        }
        return builder.toString()
    }

    private const val THINK_OPEN = "<thi" + "nk>"
    private const val THINK_CLOSE = "</thi" + "nk>"

    /** Removes reasoning blocks some models (Qwen3) emit before their final answer. */
    fun stripThinkBlocks(text: String): String {
        if (!text.contains(THINK_OPEN) && !text.contains(THINK_CLOSE)) return text
        var result = text
        while (true) {
            val start = result.indexOf(THINK_OPEN)
            if (start < 0) break
            val end = result.indexOf(THINK_CLOSE, start)
            if (end < 0) {
                // Unterminated block: the model never reached its answer.
                result = result.substring(0, start)
                break
            }
            result = result.removeRange(start, end + THINK_CLOSE.length)
        }
        return result.trim()
    }

    fun hasUnterminatedThink(text: String): Boolean {
        val start = text.indexOf(THINK_OPEN)
        if (start < 0) return false
        return text.indexOf(THINK_CLOSE, start) < 0
    }

    fun isThinkingStream(text: String): Boolean = hasUnterminatedThink(text) && text.trim() == THINK_OPEN.trim()

    fun supportsArchitecture(architecture: String?): Boolean =
        SupportedArchitectures.isSupported(architecture)
}
