package agents_engine.generation

import kotlin.reflect.KClass

/**
 * A partially constructed instance of [T] where fields arrive incrementally —
 * for example, as an LLM streams tokens.
 *
 * Use [get] to access arrived fields by name, [has] to check presence,
 * and [toComplete] to attempt full construction once enough fields have arrived.
 *
 * Fields are accumulated immutably: each [withField] returns a new instance.
 *
 * Note: full typed property access (`partial.fieldName`) requires KSP codegen
 * (planned Phase 2). Use `get("fieldName") as Type` for now.
 */
class PartiallyGenerated<T : Any> @PublishedApi internal constructor(
    val klass: KClass<T>,
    private val arrivedFields: Map<String, Any?> = emptyMap(),
) {
    /** Returns the value of [fieldName] if it has arrived, or null. */
    operator fun get(fieldName: String): Any? = arrivedFields[fieldName]

    /** Returns true if [fieldName] has arrived (even if its value is null). */
    fun has(fieldName: String): Boolean = fieldName in arrivedFields

    /** The names of all fields that have arrived so far. */
    val arrivedFieldNames: Set<String> get() = arrivedFields.keys

    /**
     * Attempts to construct a complete [T] from the fields arrived so far.
     * Returns null if required fields are missing or construction fails.
     */
    fun toComplete(): T? = klass.constructFromMap(arrivedFields)

    /** Returns a new [PartiallyGenerated] with [name] set to [value]. */
    fun withField(name: String, value: Any?): PartiallyGenerated<T> =
        PartiallyGenerated(klass, arrivedFields + (name to value))

    companion object {
        inline fun <reified T : Any> empty(): PartiallyGenerated<T> = PartiallyGenerated(T::class)
    }
}

/** Creates an empty [PartiallyGenerated] for [T]. */
inline fun <reified T : Any> partiallyGenerated(): PartiallyGenerated<T> =
    PartiallyGenerated.empty()
