package agents_engine.detekt

/** A capability a tool's executor body exercises, classified statically from its call sites. */
enum class ToolCapability { FS_READ, FS_WRITE, NETWORK, ENVIRONMENT, EXEC }
