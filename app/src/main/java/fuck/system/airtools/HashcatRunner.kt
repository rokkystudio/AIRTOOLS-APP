package fuck.system.airtools

import android.content.Context
import android.os.Build
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs the Hashcat executable packaged with the application and forwards its
 * combined stdout and stderr stream to the caller line by line.
 *
 * The packaged executable is expected at nativeLibraryDir/libhashcat_exec.so.
 * Input files must be regular filesystem files because native Hashcat cannot
 * consume Android content:// URIs directly.
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
     * Describes whether the APK contains the native Hashcat executable expected
     * by this runner and includes the current device ABI in the message.
     */
    fun backendStatus(): String
    {
        val executable = resolveExecutable()
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

        return if (executable.isFile)
        {
            "Hashcat backend: ${executable.absolutePath}\nABI: $abi"
        }
        else
        {
            "Hashcat backend не найден: ${executable.absolutePath}\nABI: $abi"
        }
    }

    /**
     * Starts a straight dictionary attack for the supplied hash mode and forwards
     * live process output to the listener.
     *
     * @throws IOException when the packaged native executable is unavailable.
     * @throws IllegalStateException when another Hashcat process is already alive.
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

        val executable = resolveExecutable()

        if (!executable.isFile)
        {
            throw IOException(
                "Native Hashcat backend отсутствует. Ожидается ${executable.absolutePath}."
            )
        }

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
            put("XDG_DATA_HOME", dataDirectory.absolutePath)
            put("XDG_CACHE_HOME", cacheDirectory.absolutePath)
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
     * Requests termination of the current Hashcat process and forcibly terminates
     * it when it remains alive after the graceful process destroy interval.
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
     * Returns the APK native-library path reserved for the packaged Hashcat
     * executable.
     */
    private fun resolveExecutable(): File
    {
        return File(
            context.applicationInfo.nativeLibraryDir,
            HASHCAT_EXECUTABLE_NAME
        )
    }

    private companion object
    {
        const val HASHCAT_EXECUTABLE_NAME = "libhashcat_exec.so"
        const val STOP_TIMEOUT_SECONDS = 2L
    }
}
