package dev.universalai.agent.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Formatting {

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFormatter = DateTimeFormatter.ofPattern("d MMM")

    fun relativeTime(isoTimestamp: String?): String {
        if (isoTimestamp.isNullOrBlank()) return ""
        return runCatching {
            val instant = OffsetDateTime.parse(isoTimestamp).toInstant()
            val now = Instant.now()
            val minutes = Duration.between(instant, now).toMinutes()
            when {
                minutes < 0 -> dateFormatter.format(instant.atZone(ZoneId.systemDefault()))
                minutes < 1 -> "now"
                minutes < 60 -> "${minutes}m ago"
                minutes < 60 * 24 -> "${minutes / 60}h ago"
                minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)}d ago"
                else -> dateFormatter.format(instant.atZone(ZoneId.systemDefault()))
            }
        }.getOrElse { "" }
    }

    fun clockTime(isoTimestamp: String?): String {
        if (isoTimestamp.isNullOrBlank()) return ""
        return runCatching {
            timeFormatter.format(OffsetDateTime.parse(isoTimestamp).atZoneSameInstant(ZoneId.systemDefault()))
        }.getOrElse { "" }
    }

    fun greeting(hour: Int = java.time.LocalTime.now().hour): String = when (hour) {
        in 0..4 -> "Working late"
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Good night"
    }

    fun today(): String = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM"))

    fun fileSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }

    fun tokenCount(tokens: Int): String = when {
        tokens < 1000 -> "$tokens"
        else -> String.format("%.1fk", tokens / 1000.0)
    }

    fun truncate(text: String, max: Int = 120): String =
        if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"
}
