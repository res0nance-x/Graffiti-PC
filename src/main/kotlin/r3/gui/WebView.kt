package r3.gui

import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import r3.gui.WebView.Companion.WM_SETICON
import java.io.File

/**
 * High-level Kotlin wrapper around the low-level [WebViewLib] JNA bindings.
 *
 * The native library is loaded lazily on first use. Each [WebView] instance owns a single
 * native webview handle and should be closed exactly once.
 */
class WebView(
	debug: Boolean = false,
	parentWindow: Pointer? = null,
) : AutoCloseable {
	private val library = Runtime.library
	private var handle: Pointer? = library.webview_create(if (debug) 1 else 0, parentWindow)
		?: error("webview_create failed (check webview.dll and the installed WebView2 runtime)")
	private val bindCallbacks = mutableMapOf<String, WebViewLib.BindFn>()
	private val dispatchCallbacks = mutableSetOf<WebViewLib.DispatchFn>()

	/**
	 * Sets the window title.
	 */
	fun setTitle(title: String): WebView {
		library.webview_set_title(requireHandle(), title)
		return this
	}

	/**
	 * Sets the window size using a Kotlin enum instead of raw native hint integers.
	 */
	fun setSize(width: Int, height: Int, hint: SizeHint = SizeHint.NONE): WebView {
		library.webview_set_size(requireHandle(), width, height, hint.nativeValue)
		return this
	}

	/**
	 * Sets the window icon from a `.ico` file.
	 *
	 * Loads both a 32×32 icon (used by Alt-Tab / taskbar thumbnail) and a 16×16 icon
	 * (used by the title bar) via Win32 [LoadImage] and applies them with [WM_SETICON].
	 * Silently no-ops if [iconFile] does not exist or the window handle is unavailable.
	 */
	fun setIcon(iconFile: File): WebView {
		if (!iconFile.exists()) return this
		val hwndPtr = library.webview_get_window(requireHandle()) ?: return this
		val hwnd = WinDef.HWND(hwndPtr)
		val path = iconFile.absolutePath
		// ICON_BIG (1) — Alt-Tab switcher and taskbar thumbnail
		User32.INSTANCE.LoadImage(null, path, WinUser.IMAGE_ICON, 32, 32, WinUser.LR_LOADFROMFILE)
			?.let { icon ->
				User32.INSTANCE.SendMessage(
					hwnd, WM_SETICON,
					WinDef.WPARAM(ICON_BIG), WinDef.LPARAM(Pointer.nativeValue(icon.pointer))
				)
			}
		// ICON_SMALL (0) — title-bar corner and taskbar button
		User32.INSTANCE.LoadImage(null, path, WinUser.IMAGE_ICON, 16, 16, WinUser.LR_LOADFROMFILE)
			?.let { icon ->
				User32.INSTANCE.SendMessage(
					hwnd, WM_SETICON,
					WinDef.WPARAM(ICON_SMALL), WinDef.LPARAM(Pointer.nativeValue(icon.pointer))
				)
			}

		return this
	}

	/**
	 * Navigates to the provided URL.
	 */
	fun navigate(url: String): WebView {
		library.webview_navigate(requireHandle(), url)
		return this
	}

	/**
	 * Loads literal HTML into the webview.
	 */
	fun setHtml(html: String): WebView {
		library.webview_set_html(requireHandle(), html)
		return this
	}

	/**
	 * Injects JavaScript to run during page initialization.
	 */
	fun init(script: String): WebView {
		library.webview_init(requireHandle(), script)
		return this
	}

	/**
	 * Evaluates JavaScript in the current page context.
	 */
	fun eval(script: String): WebView {
		library.webview_eval(requireHandle(), script)
		return this
	}

	/**
	 * Exposes a Kotlin handler as a JavaScript function.
	 *
	 * The handler receives the raw JSON request payload and returns a [BindResult], which is
	 * converted to the native [WebViewLib.webview_return] call automatically.
	 */
	fun bind(name: String, handler: (requestJson: String) -> BindResult): WebView {
		val nativeCallback = object : WebViewLib.BindFn {
			override fun invoke(seq: String?, req: String?, arg: Pointer?) {
				if (seq == null || req == null) {
					return
				}
				val response = try {
					handler(req)
				} catch (t: Throwable) {
					BindResult.error(jsonString(t.message ?: t::class.simpleName ?: "Unhandled exception"))
				}

				library.webview_return(requireHandle(), seq, response.status, response.resultJson)
			}
		}

		bindCallbacks[name] = nativeCallback
		library.webview_bind(requireHandle(), name, nativeCallback, null)
		return this
	}

	/**
	 * Removes a previously bound JavaScript function.
	 */
	fun unbind(name: String): WebView {
		library.webview_unbind(requireHandle(), name)
		bindCallbacks.remove(name)
		return this
	}

	/**
	 * Schedules work onto the webview's UI thread.
	 */
	fun dispatch(block: WebView.() -> Unit): WebView {
		val nativeCallback = object : WebViewLib.DispatchFn {
			override fun invoke(w: Pointer?, arg: Pointer?) {
				try {
					this@WebView.block()
				} finally {
					synchronized(dispatchCallbacks) {
						dispatchCallbacks.remove(this)
					}
				}
			}
		}

		synchronized(dispatchCallbacks) {
			dispatchCallbacks += nativeCallback
		}
		library.webview_dispatch(requireHandle(), nativeCallback, null)
		return this
	}

	/**
	 * Returns the native top-level window handle.
	 */
	fun windowHandle(): Pointer? = library.webview_get_window(requireHandle())

	/**
	 * Returns a platform-specific native handle for the given [kind].
	 */
	fun nativeHandle(kind: Int): Pointer? = library.webview_get_native_handle(requireHandle(), kind)

	/**
	 * Enters the native event loop and blocks until the webview is terminated.
	 */
	fun run() {
		library.webview_run(requireHandle())
	}

	/**
	 * Requests the event loop to exit.
	 */
	fun terminate(): WebView {
		library.webview_terminate(requireHandle())
		return this
	}

	/**
	 * Destroys the native handle. Safe to call multiple times.
	 */
	override fun close() {
		val currentHandle = handle ?: return
		handle = null
		bindCallbacks.clear()
		synchronized(dispatchCallbacks) {
			dispatchCallbacks.clear()
		}
		library.webview_destroy(currentHandle)
	}

	private fun requireHandle(): Pointer = handle ?: error("gui.WebView has already been closed")
	enum class SizeHint(internal val nativeValue: Int) {
		NONE(0),
		MIN(1),
		MAX(2),
		FIXED(3),
	}

	data class BindResult(
		val status: Int = 0,
		val resultJson: String? = null,
	) {
		companion object {
			fun success(resultJson: String? = null): BindResult = BindResult(status = 0, resultJson = resultJson)
			fun error(resultJson: String? = null): BindResult = BindResult(status = 1, resultJson = resultJson)
		}
	}

	companion object {
		/**
		 * Absolute path of the `lib` directory that contains `webview.dll`.
		 */
		val libraryDirectory: String
			get() = Runtime.libraryDirectory.absolutePath

		// WM_SETICON wParam values (not exposed as constants by JNA's WinUser)
		private const val ICON_SMALL: Long = 0L
		private const val ICON_BIG: Long = 1L

		// WM_SETICON is absent from JNA platform's WinUser in 5.x
		private const val WM_SETICON: Int = 0x0080
		private fun jsonString(value: String): String {
			return buildString(value.length + 2) {
				append('"')
				for (ch in value) {
					when (ch) {
						'\\' -> append("\\\\")
						'"' -> append("\\\"")
						'\n' -> append("\\n")
						'\r' -> append("\\r")
						'\t' -> append("\\t")
						else -> append(ch)
					}
				}
				append('"')
			}
		}
	}

	private object Runtime {
		private const val LIB_NAME = "webview"
		val libraryDirectory: File by lazy {
			// Delegates to gui.NativeResources which checks beside the JAR first,
			// then falls back to the working directory (IDE run-config default).
			NativeResources.libDir
		}
		val library: WebViewLib by lazy {
			// WebView2Loader.dll reads WEBVIEW2_USER_DATA_FOLDER at environment-creation time
			// (inside CreateCoreWebView2EnvironmentWithOptions). Setting it here — before the
			// first webview_create call — causes the browser engine to store its cache in
			// .webview/ beside the application rather than in %LOCALAPPDATA%\java.WebView2.
			val dataDir = NativeResources.webviewDataDir.absolutePath
			Kernel32.INSTANCE.SetEnvironmentVariable("WEBVIEW2_USER_DATA_FOLDER", dataDir)
			val libDir = libraryDirectory.absolutePath
			runCatching {
				NativeLibrary.getInstance("kernel32").getFunction("SetDllDirectoryW").invoke(arrayOf(libDir))
			}
			System.setProperty("jna.library.path", libDir)
			NativeLibrary.addSearchPath(LIB_NAME, libDir)
			NativeLibrary.addSearchPath("WebView2Loader", libDir)
			Native.load(LIB_NAME, WebViewLib::class.java)
		}
	}
}

