package dev.universalai.agent.data.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.int(key: String): Int =
    (this[key] as? JsonPrimitive)?.intOrNull ?: 0

internal fun JsonObject.bool(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

internal fun JsonObject.obj(key: String): JsonObject? = (this[key] as? JsonObject)

internal fun JsonObject.array(key: String): JsonArray? = (this[key] as? JsonArray)

internal fun JsonObject.stringList(key: String): List<String> =
    array(key)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

internal fun JsonObject.raw(key: String): JsonElement? = this[key]

internal fun JsonElement?.asObjectOrNull(): JsonObject? = runCatching { this?.jsonObject }.getOrNull()

internal fun JsonElement?.asArrayOrNull(): JsonArray? = runCatching { this?.jsonArray }.getOrNull()
