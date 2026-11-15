package dev.akhil.freebuff.core

import java.util.Locale
import java.util.concurrent.TimeUnit

object Format {

    fun bytes(value: Long): String {
        if (value <= 0) return "0 B"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var size = value.toDouble()
        var unit = 0
        while (size >= 1024.0 && unit < units.lastIndex) {
            size /= 1024.0
            unit++
        }
        return if (unit == 0) {
            "${size.toInt()} ${units[unit]}"
        } else {
            String.format(Locale.US, "%.1f %s", size, units[unit])
        }
    }

    fun duration(millis: Long): String {
        if (millis < 1000) return "${millis}ms"
        val seconds = millis / 1000
        if (seconds < 60) return "${seconds}s"
        val minutes = seconds / 60
        if (minutes < 60) return "${minutes}m ${seconds % 60}s"
        val hours = minutes / 60
        return "${hours}h ${minutes % 60}m"
    }

    fun relative(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        if (timestamp <= 0) return "—"
        val diff = now - timestamp
        if (diff < 0) return "just now"
        val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
        val hours = TimeUnit.MILLISECONDS.toHours(diff)
        val days = TimeUnit.MILLISECONDS.toDays(diff)
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            days < 7 -> "${days}d ago"
            days < 30 -> "${days / 7}w ago"
            else -> date(timestamp)
        }
    }

    fun date(timestamp: Long): String {
        if (timestamp <= 0) return "—"
        val fmt = java.text.SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
        return fmt.format(java.util.Date(timestamp))
    }

    fun shortDate(timestamp: Long): String {
        if (timestamp <= 0) return "—"
        val fmt = java.text.SimpleDateFormat("d MMM", Locale.getDefault())
        return fmt.format(java.util.Date(timestamp))
    }

    fun tokensPerSecond(count: Int, elapsedMillis: Long): String {
        if (elapsedMillis <= 0 || count <= 0) return "—"
        val tps = count * 1000.0 / elapsedMillis
        return String.format(Locale.US, "%.1f tok/s", tps)
    }

    fun truncate(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"

    fun slug(text: String): String {
        val cleaned = text.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        return cleaned.ifBlank { "item" }.take(40)
    }

    fun percent(fraction: Float): String = "${(fraction * 100).toInt()}%"
}
