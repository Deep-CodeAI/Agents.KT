package agents_engine.rag

/**
 * #3863 — a dense vector. Wraps the raw `FloatArray` without copying.
 * Note: `FloatArray` equality is referential — two `Embedding`s with
 * equal contents are not `==`; compare [values] explicitly if needed.
 */
@JvmInline
value class Embedding(val values: FloatArray) {
    val dimensions: Int get() = values.size
}
