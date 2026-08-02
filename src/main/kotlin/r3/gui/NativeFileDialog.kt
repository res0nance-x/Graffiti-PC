package r3.gui// r3.gui.NativeFileDialog.kt
import java.io.File

// TODO: Currently unused, but keeping as may be needed in the future.
/**
 * Native file/folder selector backed by FileSelector.exe.
 *
 * All calls block until the dialog is dismissed — safe to call directly
 * from a gui.WebView bind handler without additional threading.
 *
 * Filter format (passed straight through to the exe):
 *   Single type : "Identity Files|*"
 *   Multiple    : "Images|*.png;*.jpg|All Files|*.*"
 */
object NativeFileDialog {
	/** Absolute path to FileSelector.exe, resolved via [NativeResources] (beside the JAR, or CWD fallback). */
	private val EXE: String by lazy {
		NativeResources.libDir.resolve("FileSelector.exe").absolutePath
	}

	/**
	 * Shows a native open-file dialog.
	 * Returns the selected [File], or null if the user cancelled.
	 *
	 * @param title   Dialog title bar text.
	 * @param filter  Optional file-type filter, e.g. `"Identity Files|*"`.
	 */
	fun open(title: String, filter: String? = null): File? =
		run(mode = "open", title = title, filter = filter, defaultExt = null, multi = false)
			.firstOrNull()

	/**
	 * Shows a native open-file dialog with multi-select enabled.
	 * Returns an empty list if the user cancelled.
	 *
	 * @param title   Dialog title bar text.
	 * @param filter  Optional file-type filter, e.g. `"Identity Files|*"`.
	 */
	fun openMultiple(title: String, filter: String? = null): List<File> =
		run(mode = "open", title = title, filter = filter, defaultExt = null, multi = true)

	/**
	 * Shows a native save-file dialog.
	 * Returns the chosen [File], or null if the user cancelled.
	 *
	 * @param title       Dialog title bar text.
	 * @param filter      Optional file-type filter, e.g. `"Identity Files|*"`.
	 * @param defaultExt  Extension appended when the user omits one.
	 */
	fun save(title: String, filter: String? = null, defaultExt: String? = null): File? =
		run(mode = "save", title = title, filter = filter, defaultExt = defaultExt, multi = false)
			.firstOrNull()

	/**
	 * Shows a native folder-picker dialog.
	 * Returns the chosen [File], or null if the user cancelled.
	 *
	 * @param title  Dialog title bar text.
	 */
	fun folder(title: String): File? =
		run(mode = "folder", title = title, filter = null, defaultExt = null, multi = false)
			.firstOrNull()

	// ─────────────────────────────────────────────────────────────────────────
	private fun run(
		mode: String,
		title: String,
		filter: String?,
		defaultExt: String?,
		multi: Boolean,
	): List<File> {
		val cmd = buildList {
			add(EXE)
			add("-m"); add(mode)
			add("-t"); add(title)
			if (filter != null) {
				add("--filter"); add(filter)
			}
			if (defaultExt != null) {
				add("-e"); add(defaultExt)
			}
			if (multi) {
				add("--multi")
			}
		}
		val process = ProcessBuilder(cmd)
			.redirectError(ProcessBuilder.Redirect.DISCARD) // suppress any exe stderr
			.start()
		// readText() blocks until the process closes its stdout (i.e. exits),
		// so waitFor() afterwards is just for the exit code — no deadlock risk.
		val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
		process.waitFor()

		return if (output.isEmpty()) emptyList()
		else output.lines().filter { it.isNotBlank() }.map { File(it.trim()) }
	}
}
