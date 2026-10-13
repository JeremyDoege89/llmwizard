package dev.dreamz.llmwizard.core.knowledge

/**
 * Screen → articles for the "?" button (BUILD_PLAN E5.3). Ids that have no article yet are
 * dropped by [forScreen], so screens can be wired before every article is written.
 */
object ContextualHelp {
    val map: Map<String, List<String>> = mapOf(
        "model_browser" to listOf("uncensored-models", "dense-vs-moe", "model-sizes", "quantization"),
        "model_configure" to listOf("system-prompts", "temperature-top-p", "context-length"),
        "training_setup" to listOf("finetuning-101", "qlora-explained", "preparing-data"),
        "training_progress" to listOf("cpu-training", "hyperparameters", "when-training-goes-wrong"),
        "agent_hub" to listOf("what-is-agent", "aider-guide", "open-interpreter-guide"),
        "benchmark" to listOf("measuring-speed", "memory-bandwidth", "memory-management"),
        "install" to listOf("understanding-ollama", "memory-bandwidth"),
        "recommend" to listOf("dense-vs-moe", "memory-bandwidth", "uncensored-models"),
    )

    fun forScreen(screen: String, kb: KnowledgeBase): List<Article> = map[screen].orEmpty().mapNotNull { kb[it] }
}
