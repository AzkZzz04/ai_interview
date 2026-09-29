package dev.jiaming.ai_interview.rag

@JvmRecord
data class RagContextSnippet(val id: String, val content: String?, val metadata: Map<String, Any>?, val score: Double?) {
    fun sourceContextId(): String {
        val contextId = metadata?.get("contextId")
        return contextId?.toString() ?: id
    }
}
