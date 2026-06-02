package agents_engine.observability

import agents_engine.core.Agent
import agents_engine.core.observe
import java.io.File
import java.time.Clock
import java.util.logging.Level
import java.util.logging.Logger

class AgentJsonlExportBuilder internal constructor(private val agent: Agent<*, *>) {
    internal val exporters = mutableListOf<JsonlAuditExporter>()

    fun file(path: String): File = File(path)

    fun jsonl(
        file: File,
        rotation: JsonlRotation = JsonlRotation.None,
        maxBufferedLines: Int = 1_024,
        clock: Clock = Clock.systemUTC(),
        logger: (message: String, cause: Throwable?) -> Unit = DEFAULT_EXPORT_LOGGER,
    ): JsonlAuditExporter {
        val exporter = JsonlAuditExporter(
            file = file,
            rotation = rotation,
            maxBufferedLines = maxBufferedLines,
            logger = logger,
            clock = clock,
        )
        agent.observe { exporter.write(it) }
        exporters += exporter
        return exporter
    }

    private companion object {
        private val DEFAULT_EXPORT_LOGGER: (String, Throwable?) -> Unit =
            { message, cause ->
                if (cause == null) {
                    Logger.getLogger(JsonlAuditExporter::class.java.name).warning(message)
                } else {
                    Logger.getLogger(JsonlAuditExporter::class.java.name).log(Level.WARNING, message, cause)
                }
            }
    }
}
