package agents_engine.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

/**
 * #2885 — registers the Agents.KT custom detekt rules. Discovered by detekt via
 * `META-INF/services/io.gitlab.arturbosch.detekt.api.RuleSetProvider` when this
 * module is on the `detektPlugins` classpath. Rule set id `agents-kt`; enable in
 * `detekt.yml` under that key.
 */
class AgentsKtRuleSetProvider : RuleSetProvider {
    override val ruleSetId: String = "agents-kt"

    override fun instance(config: Config): RuleSet =
        RuleSet(ruleSetId, listOf(ToolBodyForbiddenApis(config), ToolPolicyCapabilityComparator(config)))
}
