package agents_engine.core

class ToolFilesystemPolicyBuilder(initial: ToolFilesystemPolicy = ToolFilesystemPolicy()) {
    private val readGlobs = linkedSetOf<String>()
    private val writeGlobs = linkedSetOf<String>()
    private var readMode: Mode = Mode.UNSPECIFIED
    private var writeMode: Mode = Mode.UNSPECIFIED

    init {
        when (val read = initial.read) {
            is ToolFilesystemAccess.Globs -> {
                readMode = Mode.GLOBS
                readGlobs += read.globs
            }
            ToolFilesystemAccess.None -> readMode = Mode.NONE
            ToolFilesystemAccess.Unspecified -> Unit
        }
        when (val write = initial.write) {
            is ToolFilesystemAccess.Globs -> {
                writeMode = Mode.GLOBS
                writeGlobs += write.globs
            }
            ToolFilesystemAccess.None -> writeMode = Mode.NONE
            ToolFilesystemAccess.Unspecified -> Unit
        }
    }

    fun read(glob: String) {
        readMode = Mode.GLOBS
        readGlobs += nonBlank(glob, "filesystem read glob")
    }

    fun write(glob: String) {
        writeMode = Mode.GLOBS
        writeGlobs += nonBlank(glob, "filesystem write glob")
    }

    fun readNone() {
        readMode = Mode.NONE
        readGlobs.clear()
    }

    fun writeNone() {
        writeMode = Mode.NONE
        writeGlobs.clear()
    }

    fun build(): ToolFilesystemPolicy =
        ToolFilesystemPolicy(
            read = access(readMode, readGlobs.toList()),
            write = access(writeMode, writeGlobs.toList()),
        )

    private fun access(mode: Mode, globs: List<String>): ToolFilesystemAccess =
        when (mode) {
            Mode.UNSPECIFIED -> ToolFilesystemAccess.Unspecified
            Mode.NONE -> ToolFilesystemAccess.None
            Mode.GLOBS -> ToolFilesystemAccess.Globs(globs)
        }

    private enum class Mode { UNSPECIFIED, NONE, GLOBS }
}
