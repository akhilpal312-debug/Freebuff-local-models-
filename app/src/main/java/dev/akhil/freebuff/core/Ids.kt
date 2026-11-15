package dev.akhil.freebuff.core

import java.util.UUID

object Ids {
    fun new(): String = UUID.randomUUID().toString()

    fun short(): String = UUID.randomUUID().toString().replace("-", "").take(12)
}

/** Monotonic-ish ordering helper for list rendering without a DB round trip. */
fun now(): Long = System.currentTimeMillis()
