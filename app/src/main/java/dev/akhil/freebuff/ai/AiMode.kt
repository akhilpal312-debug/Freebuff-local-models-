package dev.akhil.freebuff.ai

/**
 * AI modes are explicit and never switched implicitly. Each mode changes the
 * prompt, the tool access and the UI status, and each one states honestly what
 * it needs (a loaded model, a network connection, a writable workspace).
 */
enum class AiMode(
    val id: String,
    val label: String,
    val shortLabel: String,
    val description: String,
    val needsModel: Boolean,
    val needsNetwork: Boolean,
    val usesTools: Boolean,
) {
    FAST_CHAT(
        id = "fast",
        label = "Fast Chat",
        shortLabel = "Fast",
        description = "Short context, low overhead. Best for quick questions and explanations.",
        needsModel = true,
        needsNetwork = false,
        usesTools = false,
    ),
    DEEP_THINKING(
        id = "think",
        label = "Deep Thinking",
        shortLabel = "Think",
        description = "Plans, checks assumptions and verifies before answering. Slower on small models.",
        needsModel = true,
        needsNetwork = false,
        usesTools = false,
    ),
    CODING_AGENT(
        id = "code",
        label = "Coding Agent",
        shortLabel = "Code",
        description = "Reads your project, proposes and applies edits, runs tests inside the workspace.",
        needsModel = true,
        needsNetwork = false,
        usesTools = true,
    ),
    WEB_SEARCH(
        id = "search",
        label = "Web Search",
        shortLabel = "Search",
        description = "Searches the web and cites real sources. Requires a network connection.",
        needsModel = true,
        needsNetwork = true,
        usesTools = true,
    ),
    DEEP_RESEARCH(
        id = "research",
        label = "Deep Research",
        shortLabel = "Research",
        description = "Multi-step research with source inspection, comparison and a cited report.",
        needsModel = true,
        needsNetwork = true,
        usesTools = true,
    ),
    BUILD_MODE(
        id = "build",
        label = "Build Mode",
        shortLabel = "Build",
        description = "Generates or edits Android projects and runs Gradle tasks on this device.",
        needsModel = true,
        needsNetwork = false,
        usesTools = true,
    ),
    PLAN_MODE(
        id = "plan",
        label = "Plan Mode",
        shortLabel = "Plan",
        description = "Turns a request into reviewable subtasks you approve before anything runs.",
        needsModel = true,
        needsNetwork = false,
        usesTools = false,
    ),
    ;

    companion object {
        fun fromId(id: String): AiMode = entries.firstOrNull { it.id == id } ?: FAST_CHAT

        fun fromName(name: String): AiMode = entries.firstOrNull { it.name == name } ?: FAST_CHAT
    }
}
