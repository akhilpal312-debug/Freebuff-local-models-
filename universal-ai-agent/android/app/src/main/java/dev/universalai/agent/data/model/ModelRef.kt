package dev.universalai.agent.data.model

/** Lightweight reference to a model row, used where a full [ProviderModel] is overkill. */
data class ModelRef(val id: String, val displayName: String)
