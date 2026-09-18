package fuck.system.airtools

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import fuck.system.airtools.databinding.ActivityMainBinding
import java.io.File
import java.io.OutputStreamWriter
import kotlin.concurrent.thread

/**
 * Displays the CAP converter and Hashcat controls, coordinates Storage Access
 * Framework document selection and forwards native Hashcat output to the XML log.
 */
@Suppress("DEPRECATION")
class MainActivity : Activity()
{
    private lateinit var binding: ActivityMainBinding
    private lateinit var hashcatRunner: HashcatRunner

    private var selectedCaptureUri: Uri? = null
    private var selectedHashUri: Uri? = null
    private var selectedWordlistUri: Uri? = null
    private var pendingConversion: CapToHc22000Converter.Result? = null

    /**
     * Inflates the XML layout, initializes the Hashcat runner and connects all
     * capture-conversion and password-recovery controls.
     */
    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hashcatRunner = HashcatRunner(this)
        binding.hashcatBackendTextView.text = hashcatRunner.backendStatus()

        binding.selectCaptureButton.setOnClickListener {
            openDocument(REQUEST_OPEN_CAPTURE)
        }

        binding.convertButton.setOnClickListener {
            startConversion()
        }

        binding.selectHashFileButton.setOnClickListener {
            openDocument(REQUEST_OPEN_HASH_FILE)
        }

        binding.selectWordlistButton.setOnClickListener {
            openDocument(REQUEST_OPEN_WORDLIST)
        }

        binding.startHashcatButton.setOnClickListener {
            startHashcat()
        }

        binding.stopHashcatButton.setOnClickListener {
            appendHashcatLog("Stopping Hashcat...")
            hashcatRunner.stop()
        }

        binding.clearLogButton.setOnClickListener {
            binding.hashcatLogTextView.text = ""
        }

        updateHashcatStartState()
    }

    /**
     * Stops a native Hashcat process when this Activity is destroyed.
     */
    override fun onDestroy()
    {
        hashcatRunner.stop()
        super.onDestroy()
    }

    /**
     * Receives Storage Access Framework results for capture selection, hc22000
     * output creation, Hashcat input selection and wordlist selection.
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
    {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode)
        {
            REQUEST_OPEN_CAPTURE -> handleCaptureSelection(resultCode, data)
            REQUEST_CREATE_OUTPUT -> handleOutputSelection(resultCode, data)
            REQUEST_OPEN_HASH_FILE -> handleHashFileSelection(resultCode, data)
            REQUEST_OPEN_WORDLIST -> handleWordlistSelection(resultCode, data)
        }
    }

    /**
     * Opens Android's document picker for an input file associated with the
     * supplied request code.
     */
    private fun openDocument(requestCode: Int)
    {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }

        startActivityForResult(intent, requestCode)
    }

    /**
     * Stores the selected capture URI, keeps read access for later conversion and
     * updates the converter controls with the selected document name.
     */
    private fun handleCaptureSelection(resultCode: Int, data: Intent?)
    {
        if (resultCode != RESULT_OK)
        {
            return
        }

        val uri = data?.data ?: return
        persistReadPermission(uri)

        selectedCaptureUri = uri
        pendingConversion = null

        binding.selectedFileTextView.text = getString(
            R.string.selected_file,
            queryDisplayName(uri)
        )
        binding.convertButton.isEnabled = true
        binding.statusTextView.text = getString(R.string.status_ready)
    }

    /**
     * Writes the pending conversion to the destination selected by the user or
     * reports that document creation was cancelled.
     */
    private fun handleOutputSelection(resultCode: Int, data: Intent?)
    {
        if (resultCode != RESULT_OK)
        {
            binding.statusTextView.text = getString(R.string.status_save_cancelled)
            return
        }

        val uri = data?.data ?: return
        writePendingConversion(uri)
    }

    /**
     * Stores the Hashcat hash-file URI and refreshes the Start button state.
     */
    private fun handleHashFileSelection(resultCode: Int, data: Intent?)
    {
        if (resultCode != RESULT_OK)
        {
            return
        }

        val uri = data?.data ?: return
        persistReadPermission(uri)

        selectedHashUri = uri
        binding.hashFileTextView.text = getString(
            R.string.selected_hash_file,
            queryDisplayName(uri)
        )

        updateHashcatStartState()
    }

    /**
     * Stores the Hashcat wordlist URI and refreshes the Start button state.
     */
    private fun handleWordlistSelection(resultCode: Int, data: Intent?)
    {
        if (resultCode != RESULT_OK)
        {
            return
        }

        val uri = data?.data ?: return
        persistReadPermission(uri)

        selectedWordlistUri = uri
        binding.wordlistFileTextView.text = getString(
            R.string.selected_wordlist,
            queryDisplayName(uri)
        )

        updateHashcatStartState()
    }

    /**
     * Requests persistent read access when the selected document provider grants
     * persistable URI permissions.
     */
    private fun persistReadPermission(uri: Uri)
    {
        try
        {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        catch (_: SecurityException)
        {
        }
    }

    /**
     * Reads the selected capture outside the UI thread, converts supported M1+M2
     * handshakes into hc22000 lines and opens the output document picker when at
     * least one record is available.
     */
    private fun startConversion()
    {
        val captureUri = selectedCaptureUri ?: return

        setConverterBusy(true)
        binding.statusTextView.text = getString(R.string.status_converting)

        thread(name = "cap-converter") {
            try
            {
                val result = contentResolver.openInputStream(captureUri)?.use { input ->
                    CapToHc22000Converter().convert(input)
                } ?: error("Не удалось открыть выбранный CAP-файл.")

                pendingConversion = result

                runOnUiThread {
                    setConverterBusy(false)

                    binding.statusTextView.text = getString(
                        R.string.status_conversion_result,
                        result.matchedPairCount,
                        result.lines.size,
                        result.skippedWithoutSsidCount,
                        result.packetCount
                    )

                    if (result.lines.isNotEmpty())
                    {
                        createOutputDocument(captureUri)
                    }
                }
            }
            catch (error: Exception)
            {
                runOnUiThread {
                    setConverterBusy(false)
                    binding.statusTextView.text = getString(
                        R.string.status_error,
                        error.message ?: error.javaClass.simpleName
                    )
                }
            }
        }
    }

    /**
     * Opens Android's document creator with an hc22000 filename derived from the
     * selected capture name.
     */
    private fun createOutputDocument(captureUri: Uri)
    {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, suggestOutputName(captureUri))
        }

        startActivityForResult(intent, REQUEST_CREATE_OUTPUT)
    }

    /**
     * Writes the most recent conversion result as UTF-8 text with one hc22000
     * record per line to the URI selected by the user.
     */
    private fun writePendingConversion(outputUri: Uri)
    {
        val result = pendingConversion ?: return

        setConverterBusy(true)
        binding.statusTextView.text = getString(R.string.status_saving)

        thread(name = "hc22000-writer") {
            try
            {
                contentResolver.openOutputStream(outputUri, "wt")?.use { output ->
                    OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                        result.lines.forEach { line ->
                            writer.write(line)
                            writer.write("\n")
                        }
                    }
                } ?: error("Не удалось открыть выходной файл.")

                runOnUiThread {
                    setConverterBusy(false)
                    binding.statusTextView.text = getString(
                        R.string.status_saved,
                        result.lines.size
                    )
                }
            }
            catch (error: Exception)
            {
                runOnUiThread {
                    setConverterBusy(false)
                    binding.statusTextView.text = getString(
                        R.string.status_error,
                        error.message ?: error.javaClass.simpleName
                    )
                }
            }
        }
    }

    /**
     * Copies selected SAF inputs into the application cache and starts a straight
     * Hashcat dictionary attack with live stdout and stderr forwarding.
     */
    private fun startHashcat()
    {
        val hashUri = selectedHashUri ?: return
        val wordlistUri = selectedWordlistUri ?: return
        val hashMode = binding.hashModeEditText.text.toString().toIntOrNull()

        if (hashMode == null)
        {
            appendHashcatLog(getString(R.string.hashcat_invalid_mode))
            return
        }

        setHashcatBusy(true)
        appendHashcatLog(getString(R.string.hashcat_preparing))

        thread(name = "hashcat-input-copy") {
            try
            {
                val inputDirectory = File(cacheDir, "hashcat-input").apply {
                    mkdirs()
                }
                val hashFile = copyUriToFile(
                    hashUri,
                    File(inputDirectory, "hashes.input")
                )
                val wordlistFile = copyUriToFile(
                    wordlistUri,
                    File(inputDirectory, "wordlist.input")
                )

                hashcatRunner.start(
                    hashFile = hashFile,
                    wordlistFile = wordlistFile,
                    hashMode = hashMode,
                    listener = createHashcatListener()
                )
            }
            catch (error: Throwable)
            {
                runOnUiThread {
                    setHashcatBusy(false)
                    appendHashcatLog(
                        "ERROR: ${error.message ?: error.javaClass.simpleName}"
                    )
                    binding.hashcatBackendTextView.text = hashcatRunner.backendStatus()
                }
            }
        }
    }

    /**
     * Creates callbacks that append native Hashcat output to the XML log and keep
     * Start and Stop controls synchronized with process lifetime.
     */
    private fun createHashcatListener(): HashcatRunner.Listener
    {
        return object : HashcatRunner.Listener
        {
            override fun onStarted(command: List<String>)
            {
                runOnUiThread {
                    appendHashcatLog(
                        "$ " + command.joinToString(" ")
                    )
                }
            }

            override fun onOutput(line: String)
            {
                runOnUiThread {
                    appendHashcatLog(line)
                }
            }

            override fun onFinished(exitCode: Int)
            {
                runOnUiThread {
                    setHashcatBusy(false)
                    appendHashcatLog(
                        getString(R.string.hashcat_finished, exitCode)
                    )
                }
            }

            override fun onError(error: Throwable)
            {
                runOnUiThread {
                    setHashcatBusy(false)
                    appendHashcatLog(
                        "ERROR: ${error.message ?: error.javaClass.simpleName}"
                    )
                }
            }
        }
    }

    /**
     * Copies one content:// document into a regular private file that can be
     * opened by the native Hashcat process.
     */
    private fun copyUriToFile(uri: Uri, destination: File): File
    {
        contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: error("Не удалось открыть ${queryDisplayName(uri)}.")

        return destination
    }

    /**
     * Appends one line to the Hashcat log and scrolls the log viewport to the
     * newest output.
     */
    private fun appendHashcatLog(line: String)
    {
        binding.hashcatLogTextView.append(line)
        binding.hashcatLogTextView.append("\n")
        binding.hashcatLogScrollView.post {
            binding.hashcatLogScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    /**
     * Enables or disables converter controls while capture parsing or output
     * writing is in progress.
     */
    private fun setConverterBusy(busy: Boolean)
    {
        binding.progressBar.visibility = if (busy)
        {
            View.VISIBLE
        }
        else
        {
            View.GONE
        }

        binding.selectCaptureButton.isEnabled = !busy
        binding.convertButton.isEnabled = !busy && selectedCaptureUri != null
    }

    /**
     * Updates Hashcat controls for a running or idle native process.
     */
    private fun setHashcatBusy(busy: Boolean)
    {
        binding.hashcatProgressBar.visibility = if (busy)
        {
            View.VISIBLE
        }
        else
        {
            View.GONE
        }

        binding.hashModeEditText.isEnabled = !busy
        binding.selectHashFileButton.isEnabled = !busy
        binding.selectWordlistButton.isEnabled = !busy
        binding.stopHashcatButton.isEnabled = busy

        if (busy)
        {
            binding.startHashcatButton.isEnabled = false
        }
        else
        {
            updateHashcatStartState()
        }
    }

    /**
     * Enables Start only when both native Hashcat input documents are selected and
     * no native process is currently running.
     */
    private fun updateHashcatStartState()
    {
        binding.startHashcatButton.isEnabled =
            selectedHashUri != null &&
            selectedWordlistUri != null &&
            !hashcatRunner.isRunning()
    }

    /**
     * Returns the display name exposed by the document provider and falls back to
     * the URI path segment when the provider does not expose a display name.
     */
    private fun queryDisplayName(uri: Uri): String
    {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)

            if (nameIndex >= 0 && cursor.moveToFirst())
            {
                return cursor.getString(nameIndex)
            }
        }

        return uri.lastPathSegment ?: getString(R.string.unknown_file_name)
    }

    /**
     * Builds the default hc22000 output filename from the selected capture's
     * display name.
     */
    private fun suggestOutputName(uri: Uri): String
    {
        val inputName = queryDisplayName(uri)
        val baseName = inputName.substringBeforeLast('.', inputName)

        return "$baseName.hc22000"
    }

    private companion object
    {
        const val REQUEST_OPEN_CAPTURE = 1001
        const val REQUEST_CREATE_OUTPUT = 1002
        const val REQUEST_OPEN_HASH_FILE = 1003
        const val REQUEST_OPEN_WORDLIST = 1004
    }
}
