package r3.gui

import r3.gui.NativeResources.libDir
import java.io.File

/**
 * Resolves the `lib/` directory that contains the app's native binaries
 * (`webview.dll`, `WebView2Loader.dll`, `FileSelector.exe`, …).
 *
 * Resolution order
 * ────────────────
 * 1. **Beside the JAR** – the deployed layout where `lib/` sits next to `graffiti.jar`.
 *    Determined from the running JAR's own location via [Class.getProtectionDomain].
 * 2. **Working directory** – `lib/` relative to the JVM's current working directory.
 *    This is the default for IntelliJ run-configurations, which set the project root as
 *    the working directory, where `lib/` already lives during development.
 *
 * Both paths are tested by checking for [MARKER] so neither candidate is accepted
 * unless the expected content is actually there.
 */
internal object NativeResources {
	/**
	 * The resolved `lib/` directory (contains `webview.dll`, `WebView2Loader.dll`, `FileSelector.exe`).
	 * Evaluated lazily once and cached.
	 */
	val libDir: File by lazy { resolveDir("lib", "webview.dll") }

	/**
	 * The resolved `web/` directory (contains `index.html` and static assets).
	 * Evaluated lazily once and cached.
	 */
	val webDir: File by lazy { resolveDir("web", "index.html") }

	/**
	 * The `.webview` user-data directory passed to WebView2 via the
	 * `WEBVIEW2_USER_DATA_FOLDER` environment variable.
	 *
	 * Placed alongside the install root (the parent of [libDir]) so the browser
	 * cache lives next to `graffiti.jar` — not buried in `%LOCALAPPDATA%` under
	 * a java-named folder. The directory is created on first access if absent.
	 */
	val webviewDataDir: File by lazy {
		File(libDir.parentFile, ".webview").also { it.mkdirs() }
	}

	/**
	 * Generic directory resolver.
	 *
	 * @param dirName   The subdirectory name to look for (e.g. `"lib"`, `"web"`).
	 * @param marker    A file that must exist inside that directory to confirm it is valid.
	 */
	private fun resolveDir(dirName: String, marker: String): File {
		// ── 1. Beside the JAR or build output ─────────────────────────────────
		val codeSource = NativeResources::class.java.protectionDomain?.codeSource?.location
		if (codeSource != null) {
			runCatching {
				val codeFile = File(codeSource.toURI())
				val candidate = File(codeFile.parentFile, dirName)
				if (File(candidate, marker).exists()) return candidate

				val buildResCandidate = File(codeFile.parentFile.parentFile, "resources/main/$dirName")
				if (File(buildResCandidate, marker).exists()) return buildResCandidate
			}
		}

		// ── 2. Working-directory & Gradle layout fallbacks ────────────────────
		val candidates = listOf(
			File("src/main/resources/$dirName"),
			File("build/resources/main/$dirName"),
			File(dirName),
			File("../GraffitiCore/$dirName"),
			File("../GraffitiCore/src/main/resources/$dirName")
		)
		for (candidate in candidates) {
			val abs = candidate.absoluteFile
			if (File(abs, marker).exists()) return abs
		}

		// ── 3. Classpath resource extraction fallback ──────────────────────────
		val resourcePath = "/$dirName/$marker"
		val resourceUrl = NativeResources::class.java.getResource(resourcePath)
		if (resourceUrl != null) {
			runCatching {
				val tempDir = File(System.getProperty("java.io.tmpdir"), "graffiti-$dirName").absoluteFile
				tempDir.mkdirs()
				val filesToExtract = if (dirName == "lib") {
					listOf("webview.dll", "WebView2Loader.dll", "FileSelector.exe")
				} else {
					listOf(marker)
				}
				for (fileName in filesToExtract) {
					val res = NativeResources::class.java.getResourceAsStream("/$dirName/$fileName")
					if (res != null) {
						val targetFile = File(tempDir, fileName)
						targetFile.outputStream().use { out -> res.copyTo(out) }
					}
				}
				if (File(tempDir, marker).exists()) return tempDir
			}
		}

		error(
			"Could not locate the '$dirName' directory.\n" +
					"Expected '$dirName/$marker' either:\n" +
					"  • beside the running JAR, or\n" +
					"  • in src/main/resources/$dirName, or\n" +
					"  • in the working directory (${File(".").absolutePath}), or\n" +
					"  • in classpath resources"
		)
	}
}

