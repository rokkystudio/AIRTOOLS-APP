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
 * The payload is produced by the :hashcat Android library module and merged
 * into this APK through implementation(project(":hashcat")).
 *
 * Supported ABI: arm64-v8a only. Put the executable here:
 * hashcat/build/generated/hashcatRuntime/jniLibs/arm64-v8a/libhashcat_exec.so
 *
 * Put Hashcat shared runtime files here:
 * hashcat/build/generated/hashcatRuntime/assets/hashcat/OpenCL
 * hashcat/build/generated/hashcatRuntime/assets/hashcat/modules
 * hashcat/build/generated/hashcatRuntime/assets/hashcat/bridges
 * hashcat/build/generated/hashcatRuntime/assets/hashcat/feeds
 * hashcat/build/generated/hashcatRuntime/assets/hashcat/rules
 * hashcat/build/generated/hashcatRuntime/assets/hashcat/tunings
 * hashcat/build/generated/hashcatRuntime/assets/hashcat/pcfg
 * hashcat/build/generated/hashcatRuntime/assets/hashcat/hashcat.hcstat2
 *
 * Hashcat resolves its shared directory from the executable location in
 * portable mode, so this runner copies the extracted .so and asset tree into a
 * private runtime directory before starting the process.
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
     * Shows the active ABI, native extraction directory, runtime directory and
     * whether the bundled executable is already present and runnable.
     */
    fun backendStatus(): String
    {
        val bundledExecutable = resolveBundledExecutable()
        val runtimeExecutable = resolveRuntimeExecutable()
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        val nativeDirectory = resolveNativeLibraryDirectory()
        val runtimeDirectory = resolveRuntimeDirectory()

        val bundledStatus = when
        {
            bundledExecutable.isFile && bundledExecutable.canExecute() -> "ready"
            bundledExecutable.isFile -> "found, not executable"
            else -> "missing"
        }

        val runtimeStatus = when
        {
            runtimeExecutable.isFile && runtimeExecutable.canExecute() -> "ready"
            runtimeExecutable.isFile -> "found, not executable"
            else -> "not prepared"
        }

        return buildString {
            append("Hashcat bundled backend: ")
            append(bundledStatus)
            append('\n')
            append("Hashcat runtime backend: ")
            append(runtimeStatus)
            append('\n')
            append("ABI: ")
            append(abi)
            append('\n')
            append("Native dir: ")
            append(nativeDirectory.absolutePath)
            append('\n')
            append("Runtime dir: ")
            append(runtimeDirectory.absolutePath)
            append('\n')
            append("Expected APK binary: ")
            append(bundledExecutable.absolutePath)
        }
    }

    /**
     * Starts the native executable and forwards live stdout/stderr output to the
     * listener. Inputs are regular private files because native code cannot read
     * Android content:// URIs directly.
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
            throw IllegalStateException("Hashcat is already running.")
        }

        val executable = prepareRuntime()
        val runtimeDirectory = resolveRuntimeDirectory()
        val nativeDirectory = resolveNativeLibraryDirectory()

        val workDirectory = File(context.filesDir, "hashcat/work").apply {
            mkdirs()
        }
        val dataDirectory = File(context.filesDir, "hashcat/data").apply {
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
            .directory(runtimeDirectory)
            .redirectErrorStream(true)

        processBuilder.environment().apply {
            put("HOME", dataDirectory.absolutePath)
            put("TMPDIR", cacheDirectory.absolutePath)
            put("XDG_DATA_HOME", dataDirectory.absolutePath)
            put("XDG_CACHE_HOME", cacheDirectory.absolutePath)
            putNativeLibraryPath(runtimeDirectory, nativeDirectory)
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
     * Prepares a writable portable Hashcat tree inside the app sandbox.
     */
    private fun prepareRuntime(): File
    {
        val runtimeDirectory = resolveRuntimeDirectory().apply {
            mkdirs()
        }

        copyBundledExecutable(runtimeDirectory)
        copyHashcatSharedAssets(runtimeDirectory)

        val executable = resolveRuntimeExecutable()

        if (!executable.canExecute())
        {
            executable.setExecutable(true, false)
        }

        if (!executable.canExecute())
        {
            throw IOException("Hashcat runtime backend is not executable: ${executable.absolutePath}")
        }

        return executable
    }

    /**
     * Copies the APK-extracted executable into the portable runtime directory.
     */
    private fun copyBundledExecutable(runtimeDirectory: File)
    {
        val source = resolveBundledExecutable()

        if (!source.isFile)
        {
            throw IOException(
                "Native Hashcat backend is missing. Put the binary in " +
                    "hashcat/build/generated/hashcatRuntime/jniLibs/<abi>/$HASHCAT_EXECUTABLE_NAME"
            )
        }

        val target = File(runtimeDirectory, HASHCAT_EXECUTABLE_NAME)
        val shouldCopy = !target.isFile ||
            target.length() != source.length() ||
            target.lastModified() < source.lastModified()

        if (shouldCopy)
        {
            source.inputStream().use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            target.setLastModified(source.lastModified())
        }
    }

    /**
     * Copies Hashcat's shared runtime tree from APK assets when the app package
     * changes. The tree must contain OpenCL kernels and plugin directories.
     */
    private fun copyHashcatSharedAssets(runtimeDirectory: File)
    {
        val rootEntries = context.assets.list(HASHCAT_ASSET_ROOT)
            ?.filter { it.isNotBlank() }
            .orEmpty()

        if (rootEntries.isEmpty())
        {
            throw IOException(
                "Hashcat shared files are missing. Put the packaged runtime tree in " +
                    "hashcat/build/generated/hashcatRuntime/assets/$HASHCAT_ASSET_ROOT"
            )
        }

        val marker = File(runtimeDirectory, ASSET_MARKER_FILE)
        val assetVersion = resolveAssetVersion()
        val openClDirectory = File(runtimeDirectory, "OpenCL")

        if (marker.isFile && marker.readText() == assetVersion && openClDirectory.isDirectory)
        {
            return
        }

        rootEntries.forEach { entry ->
            copyAssetTree(
                assetPath = "$HASHCAT_ASSET_ROOT/$entry",
                target = File(runtimeDirectory, entry)
            )
        }

        marker.writeText(assetVersion)
    }

    /**
     * Recursively copies an asset path into a regular filesystem path.
     */
    private fun copyAssetTree(assetPath: String, target: File)
    {
        val children = context.assets.list(assetPath)
            ?.filter { it.isNotBlank() }
            .orEmpty()

        if (children.isEmpty())
        {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return
        }

        target.mkdirs()

        children.forEach { child ->
            copyAssetTree(
                assetPath = "$assetPath/$child",
                target = File(target, child)
            )
        }
    }

    /**
     * Returns a stable value that changes when the installed APK changes.
     */
    private fun resolveAssetVersion(): String
    {
        @Suppress("DEPRECATION")
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)

        return packageInfo.lastUpdateTime.toString()
    }

    /**
     * Resolves the APK-extracted native executable path.
     */
    private fun resolveBundledExecutable(): File
    {
        return File(
            resolveNativeLibraryDirectory(),
            HASHCAT_EXECUTABLE_NAME
        )
    }

    /**
     * Resolves the private portable runtime executable path.
     */
    private fun resolveRuntimeExecutable(): File
    {
        return File(
            resolveRuntimeDirectory(),
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
     * Resolves the writable directory used as Hashcat's portable root.
     */
    private fun resolveRuntimeDirectory(): File
    {
        return File(context.filesDir, "hashcat/runtime")
    }

    /**
     * Prepends native library folders to LD_LIBRARY_PATH so companion native
     * dependencies can be placed in the runtime or jniLibs/<abi>/ directory.
     */
    private fun MutableMap<String, String>.putNativeLibraryPath(vararg directories: File)
    {
        val current = this["LD_LIBRARY_PATH"].orEmpty()
        val prefix = directories.joinToString(":") { it.absolutePath }

        this["LD_LIBRARY_PATH"] = if (current.isBlank())
        {
            prefix
        }
        else
        {
            "$prefix:$current"
        }
    }

    private companion object
    {
        const val HASHCAT_EXECUTABLE_NAME = "libhashcat_exec.so"
        const val HASHCAT_ASSET_ROOT = "hashcat"
        const val ASSET_MARKER_FILE = ".airtools-assets-version"
        const val STOP_TIMEOUT_SECONDS = 2L
    }
}
