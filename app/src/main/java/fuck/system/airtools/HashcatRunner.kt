package fuck.system.airtools

import android.content.Context
import android.os.Build
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Launches a native Hashcat executable stored in the APK as a native shared
 * object. Android extracts jniLibs into applicationInfo.nativeLibraryDir during
 * installation when jniLibs.useLegacyPackaging is enabled in Gradle.
 *
 * Put the executable for every supported ABI here:
 * app/src/main/jniLibs/arm64-v8a/libhashcat_exec.so
 * app/src/main/jniLibs/armeabi-v7a/libhashcat_exec.so
 * app/src/main/jniLibs/x86_64/libhashcat_exec.so
 */
class HashcatRunner(
    private val context: Context
)
{
    interface Listener
    {
        /**
         * Receives the exact command after the native process has started.
         */
        fun onStarted(command: List<String>)

        /**
         * Receives one line from Hashcat's combined stdout and stderr stream.
         */
        fun onOutput(line: String)

        /**
         * Receives the process exit code after Hashcat terminates.
         */
        fun onFinished(exitCode: Int)

        /**
         * Receives process-launch or stream-reading failures.
         */
        fun onError(error: Throwable)
    }

    @Volatile
    private var process: Process? = null

    /**
     * Returns true while the native Hashcat process is alive.
     */
    fun isRunning(): Boolean
    {
        return process?.isAlive == true
    }

    /**
     * Shows the active ABI, native extraction directory and whether the bundled
     * executable is already present and runnable.
     */
    fun backendStatus(): String
    {
        val executable = resolveExecutable()
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        val nativeDirectory = resolveNativeLibraryDirectory()

        val status = when
        {
            executable.isFile && executable.canExecute() -> "ready"
            executable.isFile -> "found, not executable"
            else -> "missing"
        }

        return buildString {
            append("Hashcat native backend: ")
            append(status)
            append('\n')
            append("ABI: ")
            append(abi)
            append('\n')
            append("Native dir: ")
            append(nativeDirectory.absolutePath)
            append('\n')
            append("Expected: ")
            append(executable.absolutePath)
        }
    }

    /**
     * Starts the native .so executable and forwards live stdout/stderr output to
     * the listener. Inputs are regular private files because native code cannot
     * read Android content:// URIs directly.
     */
    @Synchronized
    fun start(
        hashFile: File,
        wordlistFile: File,
        hashMode: Int,
        listener: Listener
    )
    {
        if (isRunning())
        {
            throw IllegalStateException("Hashcat уже запущен.")
        }

        val executable = prepareExecutable()
        val nativeDirectory = resolveNativeLibraryDirectory()

        val workDirectory = File(context.filesDir, "hashcat").apply {
            mkdirs()
        }
        val dataDirectory = File(workDirectory, "data").apply {
            mkdirs()
        }
        val cacheDirectory = File(context.cacheDir, "hashcat").apply {
            mkdirs()
        }
        val potfile = File(dataDirectory, "airtools.potfile")
        val recovered = File(dataDirectory, "recovered.txt")

        val command = listOf(
            executable.absolutePath,
            "-m",
            hashMode.toString(),
            "-a",
            "0",
            "--status",
            "--status-timer=1",
            "--session",
            "airtools",
            "--potfile-path",
            potfile.absolutePath,
            "--outfile",
            recovered.absolutePath,
            hashFile.absolutePath,
            wordlistFile.absolutePath
        )

        val processBuilder = ProcessBuilder(command)
            .directory(workDirectory)
            .redirectErrorStream(true)

        processBuilder.environment().apply {
            put("HOME", workDirectory.absolutePath)
            put("TMPDIR", cacheDirectory.absolutePath)
            put("XDG_DATA_HOME", dataDirectory.absolutePath)
            put("XDG_CACHE_HOME", cacheDirectory.absolutePath)
            putNativeLibraryPath(nativeDirectory)
        }

        val startedProcess = processBuilder.start()
        process = startedProcess
        listener.onStarted(command)

        thread(name = "hashcat-output") {
            try
            {
                startedProcess.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        listener.onOutput(line)
                    }
                }

                val exitCode = startedProcess.waitFor()
                listener.onFinished(exitCode)
            }
            catch (error: Throwable)
            {
                listener.onError(error)
            }
            finally
            {
                synchronized(this)
                {
                    if (process === startedProcess)
                    {
                        process = null
                    }
                }
            }
        }
    }

    /**
     * Requests termination of the current native process and kills it if it does
     * not exit quickly after the graceful signal.
     */
    fun stop()
    {
        val activeProcess = process ?: return

        thread(name = "hashcat-stop") {
            activeProcess.destroy()

            if (!activeProcess.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            {
                activeProcess.destroyForcibly()
            }
        }
    }

    /**
     * Validates that the extracted native .so exists and is executable.
     */
    private fun prepareExecutable(): File
    {
        val executable = resolveExecutable()

        if (!executable.isFile)
        {
            throw IOException(
                "Native Hashcat backend отсутствует. Положи бинарник в " +
                    "app/src/main/jniLibs/<abi>/$HASHCAT_EXECUTABLE_NAME"
            )
        }

        if (!executable.canExecute())
        {
            executable.setExecutable(true, false)
        }

        if (!executable.canExecute())
        {
            throw IOException(
                "Native Hashcat backend найден, но не исполняется: " +
                    executable.absolutePath
            )
        }

        return executable
    }

    /**
     * Resolves the already-extracted native executable path.
     */
    private fun resolveExecutable(): File
    {
        return File(
            resolveNativeLibraryDirectory(),
            HASHCAT_EXECUTABLE_NAME
        )
    }

    /**
     * Resolves Android's install-time native-library extraction directory.
     */
    private fun resolveNativeLibraryDirectory(): File
    {
        return File(context.applicationInfo.nativeLibraryDir)
    }

    /**
     * Prepends APK-extracted native libraries to LD_LIBRARY_PATH so companion
     * native dependencies can be placed in the same jniLibs/<abi>/ directory.
     */
    private fun MutableMap<String, String>.putNativeLibraryPath(nativeDirectory: File)
    {
        val current = this["LD_LIBRARY_PATH"].orEmpty()
        this["LD_LIBRARY_PATH"] = if (current.isBlank())
        {
            nativeDirectory.absolutePath
        }
        else
        {
            nativeDirectory.absolutePath + ":" + current
        }
    }

    private companion object
    {
        const val HASHCAT_EXECUTABLE_NAME = "libhashcat_exec.so"
        const val STOP_TIMEOUT_SECONDS = 2L
    }
}
