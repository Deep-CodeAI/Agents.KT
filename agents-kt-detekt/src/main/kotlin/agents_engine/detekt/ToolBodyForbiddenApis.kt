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
import org.jetbrains.kotlin.psi.psiUtil.parents

/**
 * #2885 (epic #2882, Pillar 1 — static layer). Forbids raw outside-world APIs
 * inside a tool **executor** body. A tool executor must reach the filesystem /
 * network / environment / processes only through the closed `ToolEnvironment` ABI
 * (#2883), never via `java.io.File`, `java.net.URL`/`HttpURLConnection`,
 * `ProcessBuilder`/`Runtime.exec`, `Class.forName`, etc. — so every action is
 * gated by the declared `ToolPolicy` and recorded to the audit ledger.
 *
 * Scope: a call is flagged only when it is lexically inside the lambda passed to
 * an `executor { … }` call. The same API elsewhere is untouched. Suppress a
 * legitimate use with `@Suppress("ToolBodyForbiddenApis")` (+ a reviewed reason).
 *
 * Honest limit (the epic's attack matrix marks these ⚠️): this is **syntactic** —
 * it matches the call's callee name, not a resolved FQN, and cannot see reflection
 * (`Class.forName` of a forbidden type), aliasing, or transitive library state
 * changes. Those residual risks are covered by Pillar 3 (process isolation).
 */
class ToolBodyForbiddenApis(config: Config = Config.empty) : Rule(config) {

    override val issue = Issue(
        id = "ToolBodyForbiddenApis",
        severity = Severity.Security,
        description = "A tool executor body must reach the outside world only through the closed " +
            "ToolEnvironment ABI, not raw java.io / java.net / Runtime / reflection APIs.",
        debt = Debt.TWENTY_MINS,
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val callee = expression.calleeExpression?.text?.substringAfterLast('.') ?: return
        if (callee in FORBIDDEN && expression.isInsideToolExecutor()) {
            report(
                CodeSmell(
                    issue,
                    Entity.from(expression),
                    "Forbidden API '$callee' inside a tool executor body — use the ToolEnvironment " +
                        "(env.fs / env.net / env.env) so the action is policy-gated and audited, or " +
                        "@Suppress(\"ToolBodyForbiddenApis\") with a reviewed reason.",
                ),
            )
        }
    }

    /** True when [this] call is lexically inside the lambda passed to an `executor { … }` call. */
    private fun KtCallExpression.isInsideToolExecutor(): Boolean =
        parents.filterIsInstance<KtLambdaExpression>().any { lambda ->
            lambda.parents.filterIsInstance<KtCallExpression>().firstOrNull()
                ?.calleeExpression?.text == "executor"
        }

    private companion object {
        val FORBIDDEN = setOf(
            // filesystem
            "File", "RandomAccessFile", "FileInputStream", "FileOutputStream", "FileReader", "FileWriter",
            // network
            "URL", "HttpURLConnection", "Socket",
            // process / reflection / unsafe
            "ProcessBuilder", "exec", "getRuntime", "forName", "Unsafe",
        )
    }
}
