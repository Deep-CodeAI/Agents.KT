package agents_engine.detekt

import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * #2884 (epic #2882, Pillar 1 — static layer). Classifies what a tool's executor body
 * actually does — filesystem read/write, network, environment, process exec — by walking
 * its call expressions and matching callee names. The reusable input the #2887 comparator
 * checks against the declared [agents_engine.core.ToolPolicy].
 *
 * **Honest limit (the epic's attack matrix marks these ⚠️):** this is **syntactic** — it
 * matches the call's callee name, not a resolved FQN, so it can't see reflection
 * (`Class.forName`), aliasing, or transitive library state changes. It is also intentionally
 * **conservative**: where a name is ambiguous it errs toward reporting the capability, since
 * for the comparator an over-report only widens review, never hides authority. Those residual
 * risks are covered by Pillar 3 (process isolation).
 */
object ToolCapabilityExtractor {

    /** Capabilities exercised by call expressions anywhere under [scope] (e.g. an executor lambda). */
    fun extract(scope: KtElement): Set<ToolCapability> {
        val capabilities = linkedSetOf<ToolCapability>()
        scope.collectDescendantsOfType<KtCallExpression>().forEach { call ->
            classify(call.calleeExpression?.text?.substringAfterLast('.'))?.let { capabilities += it }
        }
        return capabilities
    }

    private fun classify(callee: String?): ToolCapability? = when (callee) {
        null -> null
        in FS_WRITE -> ToolCapability.FS_WRITE
        in FS_READ -> ToolCapability.FS_READ
        in NETWORK -> ToolCapability.NETWORK
        in ENVIRONMENT -> ToolCapability.ENVIRONMENT
        in EXEC -> ToolCapability.EXEC
        else -> null
    }

    private val FS_WRITE = setOf(
        "writeText", "writeBytes", "appendText", "appendBytes", "write", "createNewFile",
        "createFile", "createDirectory", "createDirectories", "delete", "deleteIfExists",
        "mkdir", "mkdirs", "move", "copy", "newOutputStream", "bufferedWriter", "printWriter",
        "FileOutputStream", "FileWriter", "RandomAccessFile",
    )

    private val FS_READ = setOf(
        "readText", "readBytes", "readLines", "readString", "readAllLines", "readAllBytes",
        "newInputStream", "bufferedReader", "listFiles", "walk",
        "FileInputStream", "FileReader",
    )

    private val NETWORK = setOf(
        "URL", "HttpURLConnection", "Socket", "ServerSocket", "openConnection", "openStream",
        "HttpClient",
    )

    private val ENVIRONMENT = setOf("getenv")

    private val EXEC = setOf("ProcessBuilder", "exec", "getRuntime")
}
