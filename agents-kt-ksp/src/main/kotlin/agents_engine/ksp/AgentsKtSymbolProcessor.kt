package agents_engine.ksp

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSAnnotated

/**
 * KSP processor entry point for Agents.KT (#1018, P2.1).
 *
 * Currently a no-op skeleton — exists so the `:agents-kt-ksp` artifact can be
 * published, applied via the KSP plugin in consumer projects, and exercised
 * end-to-end without doing any work yet. The validation pass (#1019) and
 * schema-generation pass (#1020) plug into [process] in subsequent issues.
 */
class AgentsKtSymbolProcessor(
    @Suppress("unused") private val env: SymbolProcessorEnvironment,
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        // #1019 will walk every @Generable class here and emit compile-time
        // validation errors via env.logger.error(...).
        // #1020 will then generate per-class *_GeneratedSchema.kt files using
        // env.codeGenerator.createNewFile(...).
        return emptyList()
    }
}
