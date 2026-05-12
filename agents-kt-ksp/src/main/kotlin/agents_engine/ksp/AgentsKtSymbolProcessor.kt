package agents_engine.ksp

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier

/**
 * KSP processor entry point for Agents.KT (#1018, P2.1).
 *
 * Currently runs the `@Generable` validation pass (#1700) — walks every
 * `@Generable` class in the consumer's compilation, builds a small data
 * model, and runs [GenerableValidator] against it. Violations land as
 * compile errors via `env.logger.error(...)` with the symbol node attached
 * so the IDE can point at the offending declaration.
 *
 * Schema generation (the `*_GeneratedSchema.kt` codegen) is the next pass
 * and plugs into [process] alongside the validation walk.
 */
class AgentsKtSymbolProcessor(
    private val env: SymbolProcessorEnvironment,
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        // `getSymbolsWithAnnotation` is the canonical KSP entry — it returns
        // every declaration in the current round annotated with the given
        // fully-qualified annotation name.
        val generables = resolver
            .getSymbolsWithAnnotation(GENERABLE_FQN)
            .filterIsInstance<KSClassDeclaration>()

        for (cls in generables) {
            val model = cls.toGenerableClass()
            val errors = GenerableValidator.validate(model)
            errors.forEach { msg -> env.logger.error(msg, cls) }
        }

        // No symbols are deferred — validation runs fully in one round.
        return emptyList()
    }

    private fun KSClassDeclaration.toGenerableClass(): GenerableValidator.GenerableClass {
        val params = primaryConstructor?.parameters
        return GenerableValidator.GenerableClass(
            qualifiedName = qualifiedName?.asString() ?: simpleName.asString(),
            isSealed = Modifier.SEALED in modifiers,
            // ClassKind covers the structural family; modifiers cover concrete-vs-abstract
            // for regular classes. KSP marks interfaces / enums / annotations as
            // "abstract" too, so we test them with the more specific predicates first.
            isAbstract = Modifier.ABSTRACT in modifiers,
            isInterface = classKind == ClassKind.INTERFACE,
            isEnum = classKind == ClassKind.ENUM_CLASS,
            isAnnotation = classKind == ClassKind.ANNOTATION_CLASS,
            hasPrimaryConstructor = primaryConstructor != null,
            primaryConstructorParamCount = params?.size ?: 0,
        )
    }

    private companion object {
        const val GENERABLE_FQN = "agents_engine.generation.Generable"
    }
}
