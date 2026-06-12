package agents_engine.rag

/**
 * #3863 — metadata predicate applied to candidate chunks. Deliberately
 * minimal: a function over the chunk's string metadata. Stores that have
 * richer native filters apply this client-side on the returned matches;
 * drop down to the underlying store's API when you need server-side
 * filter pushdown.
 */
fun interface Filter {
    fun matches(metadata: Map<String, String>): Boolean
}
