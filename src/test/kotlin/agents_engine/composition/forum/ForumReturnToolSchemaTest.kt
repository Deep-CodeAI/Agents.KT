package agents_engine.composition.forum

import agents_engine.core.agent
import agents_engine.model.BuiltInToolWireSchema
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #2379 — the built-in `forum_return` tool now declares a closed,
 * `value`-only parameters schema instead of relying on the permissive
 * empty-properties fallback in the provider clients. `forum_return`
 * accepts the captain's `OUT` (heterogeneous, not statically known at
 * build time), so it carries a `parametersSchemaJson` with a single
 * open-typed `value` property rather than a typed `argsType`.
 *
 * Sibling of `MemoryToolSchemaTest` / `SwarmDelegateToolSchemaTest`:
 * pins the wire format so a regression silently re-introducing the
 * untyped path is caught at unit-test time.
 */
class ForumReturnToolSchemaTest {

    // forum_return is registered into the captain's toolMap during forum
    // build (ForumBuilder.build -> captainAgent.prepareForForum(true)). Build
    // a minimal forum, then introspect the captain's wire-level ToolDef.
    private fun captainForumReturnTool() = run {
        val participant = agent<String, String>("p") {
            skills { skill<String, String>("p") { implementedBy { "saw:$it" } } }
        }
        val captain = agent<String, String>("c") {
            skills { skill<String, String>("c") { tools() } }
        }
        forum<String, String> {
            participant(participant)
            captain(captain)
        }
        captain.toolMap["forum_return"]
            ?: error("forum build should have registered forum_return on the captain")
    }

    @Test
    fun `forum_return declares a closed value-only parameters schema`() {
        val schema = captainForumReturnTool().parametersSchemaJson
        assertNotNull(schema, "forum_return must declare its own schema, not fall back")
        val compact = schema.filterNot { it.isWhitespace() }
        assertTrue("\"additionalProperties\":false" in compact, "must be closed: $schema")
        assertTrue("\"value\"" in compact, "must expose the single 'value' property: $schema")
    }

    @Test
    fun `forum_return emits a non-permissive wire schema on every provider client`() {
        // AC (#2379): wire-format fixtures for each of the three provider
        // clients confirm forum_return never hits the permissive fallback.
        val tools = listOf(captainForumReturnTool())
        BuiltInToolWireSchema.assertNoPermissiveFallback(tools)
        BuiltInToolWireSchema.assertAllContain(tools, "\"name\":\"forum_return\"")
    }
}
