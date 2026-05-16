package agents_engine.ksp

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

/**
 * `agents-kt-ksp/agents_engine/ksp/AgentsKtSymbolProcessorProvider.kt` —
 * the service-loader entry. KSP picks this up via
 * `META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider`.
 * Consumers apply the KSP plugin and `ksp("ai.deep-code:agents-kt-ksp:<v>")`,
 * KSP discovers this provider and runs [AgentsKtSymbolProcessor.process]
 * over their source tree. Two-line factory. See
 * `src/main/resources/internals-agent/ksp/AgentsKtSymbolProcessorProvider.md`
 * (#1837 / #1896).
 */

/**
 * Service-loader entry point. KSP picks this up via
 * `META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider`.
 *
 * Consumers apply the KSP plugin and add `ksp("ai.deep-code:agents-kt-ksp:0.3.0")`
 * to their dependencies; KSP discovers this provider at compile time and runs
 * [AgentsKtSymbolProcessor.process] over their source tree.
 */
class AgentsKtSymbolProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        AgentsKtSymbolProcessor(environment)
}
