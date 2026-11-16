package dev.universalai.agent.data.remote

/**
 * Incremental parser for text/event-stream frames.
 *
 * Feed it one line at a time; it returns a completed [SseEvent] at each blank line.
 * Kept free of Android/OkHttp types so it can be unit tested on the JVM.
 */
class SseParser {

    private val data = StringBuilder()
    private var eventName: String? = null

    fun feed(line: String): SseEvent? {
        val clean = line.trimEnd('\r')
        if (clean.isEmpty()) {
            return flush()
        }
        if (clean.startsWith(":")) return null
        return when {
            clean.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(clean.removePrefix("data:").removePrefix(" "))
                null
            }
            clean.startsWith("event:") -> {
                eventName = clean.removePrefix("event:").trim()
                null
            }
            clean.startsWith("id:") -> null
            else -> null
        }
    }

    fun flush(): SseEvent? {
        if (data.isEmpty()) {
            eventName = null
            return null
        }
        val event = SseEvent(eventName, data.toString())
        data.setLength(0)
        eventName = null
        return event
    }
}
