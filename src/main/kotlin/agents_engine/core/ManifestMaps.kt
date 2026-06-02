package agents_engine.core

internal object ManifestMaps {
    fun map(value: Any?): Map<*, *> = value as? Map<*, *> ?: emptyMap<Any?, Any?>()

    fun stringList(value: Any?): List<String> =
        when (value) {
            is Iterable<*> -> value.map { it.toString() }
            is Array<*> -> value.map { it.toString() }
            null -> emptyList()
            else -> listOf(value.toString())
        }
}
