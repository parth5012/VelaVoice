package com.velavoice.sdk

/**
 * Registry-driven selected-model resolution (Ticket 806, map #290).
 *
 * Pure contract shared by the velavoice app and the velaboard keyboard:
 * both apps resolve "which local model do I run this session" the same way.
 *
 * Fallback order: selected registry id → any completed model of the selected
 * engine → any completed model (first registry order) → null (caller falls
 * back to cloud voice input). The single-Selection result also encodes the
 * session-churn invariant from maps #105/#290: at most one engine is loaded —
 * callers must close the previous engine before instantiating another kind.
 */
object ModelSelection {

    /** A model row that is present on disk (download completed). */
    data class CompletedModel(
        val id: String,
        val engine: EngineKind,
        val path: String
    )

    /** Resolved selection: one model id + its engine + the load path. */
    data class Selection(
        val modelId: String,
        val engine: EngineKind,
        val path: String
    )

    /**
     * @param selectedModelId the user's per-app default-local-model selection;
     *        null or empty means "no explicit selection".
     * @param completedModels the rows from models.db / local storage whose
     *        files exist; order should follow [ModelRegistry] order.
     */
    fun resolve(selectedModelId: String?, completedModels: List<CompletedModel>): Selection? {
        val registryIds = ModelRegistry.ALL.associateBy { it.id }
        val byId = completedModels.filter { registryIds.containsKey(it.id) }
            .associateBy { it.id }

        // 1. Explicit selection (only registry-recognized ids count).
        if (!selectedModelId.isNullOrBlank()) {
            byId[selectedModelId]?.let { return it.toSelection() }
        }

        // 2. Same engine as the selected (or preferred) engine.
        val preferredEngine = selectedModelId?.let { registryIds[it]?.engine }
        if (preferredEngine != null) {
            completedModels.firstOrNull { it.engine == preferredEngine && registryIds.containsKey(it.id) }
                ?.let { return it.toSelection() }
        }

        // 3. Any completed registry model, first in registry order.
        for (row in ModelRegistry.ALL) {
            byId[row.id]?.let { return it.toSelection() }
        }
        return null
    }

    private fun CompletedModel.toSelection() = Selection(id, engine, path)
}
