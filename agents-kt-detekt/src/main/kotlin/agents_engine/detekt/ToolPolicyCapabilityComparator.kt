package agents_engine.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * #2887 (epic #2882) — the declare-vs-do comparator, compile-time layer.
 * For a `tool(...) { policy { … }; executor { … } }` declaration, the
 * executor body's statically-extracted capabilities
 * ([ToolCapabilityExtractor]) must be a SUBSET of what the declared
 * `ToolPolicy` grants. Using more than you declared fails the build;
 * over-declaring (granting more than the body uses) is allowed here —
 * that's a manifest-review concern, not a violation.
 *
 * Fires only when a `policy { }` block is present: un-policied tools are
 * the legacy default and stay the business of `ToolBodyForbiddenApis`.
 *
 * Honest limit (same as the extractor): syntactic, callee-name based —
 * reflection, aliasing, and library-internal effects are invisible;
 * residual risk is covered by the Layer-2 sandbox.
 */
class ToolPolicyCapabilityComparator(config: Config = Config.empty) : Rule(config) {

    override val issue = Issue(
        id = "ToolPolicyCapabilityComparator",
        severity = Severity.Security,
        description = "A tool executor body must not use capabilities its declared ToolPolicy does not grant.",
        debt = Debt.TWENTY_MINS,
    )

    override fun visitCallExpression(call: KtCallExpression) {
        super.visitCallExpression(call)
        if (call.calleeExpression?.text != "tool") return

        val lambdas = call.collectDescendantsOfType<KtLambdaExpression>()
        val policyLambda = lambdaArgumentOf(call, "policy") ?: return
        val executorLambda = lambdaArgumentOf(call, "executor") ?: return
        if (lambdas.isEmpty()) return

        val declared = declaredCapabilities(policyLambda)
        val used = ToolCapabilityExtractor.extract(executorLambda)
        val undeclared = used - declared
        undeclared.forEach { capability ->
            report(
                CodeSmell(
                    issue,
                    Entity.from(call),
                    "Tool executor body uses $capability but the declared policy does not grant it. " +
                        "Widen the policy (${grantHint(capability)}) or remove the call.",
                ),
            )
        }
    }

    /** The lambda passed to a call named [name] anywhere inside [call]'s arguments. */
    private fun lambdaArgumentOf(call: KtCallExpression, name: String): KtLambdaExpression? {
        val named = call.collectDescendantsOfType<KtCallExpression>()
            .firstOrNull { it.calleeExpression?.text == name } ?: return null
        // The call's OWN lambda argument — descendants traversal is
        // innermost-first, which would hand back a nested block's lambda.
        return named.lambdaArguments.firstOrNull()?.getLambdaExpression()
            ?: named.valueArguments.firstNotNullOfOrNull { it.getArgumentExpression() as? KtLambdaExpression }
    }

    /** Capabilities granted by the `policy { }` block, mapped from the DSL's callee names. */
    private fun declaredCapabilities(policy: KtLambdaExpression): Set<ToolCapability> {
        val granted = linkedSetOf<ToolCapability>()
        policy.collectDescendantsOfType<KtCallExpression>().forEach { call ->
            when (call.calleeExpression?.text) {
                "read" -> granted += ToolCapability.FS_READ
                "write" -> granted += ToolCapability.FS_WRITE
                "network" -> if (grantsInside(call, setOf("allowAll", "allow"))) granted += ToolCapability.NETWORK
                "environment" -> if (grantsInside(call, setOf("allow"))) granted += ToolCapability.ENVIRONMENT
                "exec" -> if (grantsInside(call, setOf("allow"))) granted += ToolCapability.EXEC
            }
        }
        return granted
    }

    private fun grantsInside(block: KtCallExpression, grantingCallees: Set<String>): Boolean =
        block.collectDescendantsOfType<KtCallExpression>()
            .any { it.calleeExpression?.text in grantingCallees }

    private fun grantHint(capability: ToolCapability): String = when (capability) {
        ToolCapability.FS_READ -> "filesystem { read(\"…\") }"
        ToolCapability.FS_WRITE -> "filesystem { write(\"…\") }"
        ToolCapability.NETWORK -> "network { allow(\"host\") } or allowAll()"
        ToolCapability.ENVIRONMENT -> "environment { allow(\"VAR\") }"
        ToolCapability.EXEC -> "exec { allow() }"
    }
}
