package r3.gui

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File

/**
 * JNA mapping for the native `webview` C API exported by `webview.dll`.
 *
 * The `w` pointer returned by [webview_create] is an opaque handle. Treat it as owned by
 * the native library and release it exactly once via [webview_destroy].
 */
interface WebViewLib : Library {
	companion object {
		init {
			val libPath = File("lib").absolutePath
			System.setProperty("jna.library.path", libPath)
		}

		val INSTANCE: WebViewLib by lazy {
			Native.load("webview", WebViewLib::class.java)
		}
	}

	/**
	 * Creates a new webview instance.
	 *
	 * @param debug non-zero enables debug tools in supported builds.
	 * @param window optional parent/native window handle; pass `null` for a standalone window.
	 * @return opaque webview handle, or `null` if creation fails.
	 */
	fun webview_create(debug: Int, window: Pointer?): Pointer?

	/**
	 * Destroys a webview instance and releases native resources.
	 *
	 * @param w handle returned by [webview_create].
	 */
	fun webview_destroy(w: Pointer?)

	/**
	 * Starts the webview event loop and blocks until termination.
	 *
	 * @param w handle returned by [webview_create].
	 */
	fun webview_run(w: Pointer?)

	/**
	 * Requests the event loop to exit, causing [webview_run] to return.
	 *
	 * @param w handle returned by [webview_create].
	 */
	fun webview_terminate(w: Pointer?)

	/**
	 * Schedules [fn] to execute on the webview/UI thread.
	 *
	 * Use this to marshal work from background threads before calling UI-affecting API methods.
	 *
	 * @param w handle returned by [webview_create].
	 * @param fn callback to invoke on the UI thread.
	 * @param arg optional user data forwarded to [DispatchFn.invoke].
	 */
	fun webview_dispatch(w: Pointer?, fn: DispatchFn?, arg: Pointer?)

	/**
	 * Returns the native top-level window handle used by webview.
	 *
	 * @param w handle returned by [webview_create].
	 * @return native window pointer for platform interop.
	 */
	fun webview_get_window(w: Pointer?): Pointer?

	/**
	 * Returns a platform-specific native handle.
	 *
	 * The meaning of `kind` depends on the webview version/platform constants in `webview.h`.
	 *
	 * @param w handle returned by [webview_create].
	 * @param kind selector for which handle to retrieve.
	 * @return native handle pointer, or `null` when unavailable.
	 */
	fun webview_get_native_handle(w: Pointer?, kind: Int): Pointer?

	/**
	 * Sets the native window title.
	 *
	 * @param w handle returned by [webview_create].
	 * @param title title text shown in the window chrome.
	 */
	fun webview_set_title(w: Pointer?, title: String)

	/**
	 * Sets window size constraints.
	 *
	 * @param w handle returned by [webview_create].
	 * @param width target width in pixels.
	 * @param height target height in pixels.
	 * @param hints sizing hint constant from `webview.h` (for example `WEBVIEW_HINT_NONE`).
	 */
	fun webview_set_size(w: Pointer?, width: Int, height: Int, hints: Int)

	/**
	 * Navigates to a URL.
	 *
	 * @param w handle returned by [webview_create].
	 * @param url absolute URL such as `https://example.com`.
	 */
	fun webview_navigate(w: Pointer?, url: String)

	/**
	 * Loads literal HTML content into the webview.
	 *
	 * @param w handle returned by [webview_create].
	 * @param html complete HTML document/source string.
	 */
	fun webview_set_html(w: Pointer?, html: String)

	/**
	 * Injects JavaScript to run early in the page lifecycle.
	 *
	 * Typically used to define helper functions before page scripts execute.
	 *
	 * @param w handle returned by [webview_create].
	 * @param js JavaScript source code.
	 */
	fun webview_init(w: Pointer?, js: String)

	/**
	 * Evaluates JavaScript in the current page context.
	 *
	 * @param w handle returned by [webview_create].
	 * @param js JavaScript source code.
	 */
	fun webview_eval(w: Pointer?, js: String)

	/**
	 * Exposes a native callback as a JavaScript function.
	 *
	 * JavaScript calls `window[name](...)` and receives a Promise. Respond with [webview_return].
	 *
	 * @param w handle returned by [webview_create].
	 * @param name JavaScript function name to expose.
	 * @param fn native callback invoked per JS call.
	 * @param arg optional user data forwarded to [BindFn.invoke].
	 */
	fun webview_bind(w: Pointer?, name: String, fn: BindFn?, arg: Pointer?)

	/**
	 * Removes a previously bound JavaScript function.
	 *
	 * @param w handle returned by [webview_create].
	 * @param name function name previously passed to [webview_bind].
	 */
	fun webview_unbind(w: Pointer?, name: String)

	/**
	 * Completes a pending [webview_bind] callback invocation.
	 *
	 * @param w handle returned by [webview_create].
	 * @param seq request sequence token received by [BindFn.invoke].
	 * @param status `0` for success; non-zero signals error.
	 * @param result JSON payload string returned to JavaScript.
	 */
	fun webview_return(w: Pointer?, seq: String, status: Int, result: String?)

	/**
	 * Callback fired by [webview_drop_register] for each file dropped onto the window.
	 */
	interface DropFn : Callback {
		/**
		 * @param path     Absolute UTF-8 path of the dropped file.
		 * @param userdata Optional user data passed to [webview_drop_register].
		 */
		fun invoke(path: String, userdata: Pointer?)
	}

	/**
	 * Callback used by [webview_dispatch]. Runs on the webview/UI thread.
	 */
	interface DispatchFn : Callback {
		/**
		 * @param w webview handle associated with the dispatch call.
		 * @param arg optional user data passed into [webview_dispatch].
		 */
		fun invoke(w: Pointer?, arg: Pointer?)
	}

	/**
	 * Callback used by [webview_bind] when JavaScript invokes a bound function.
	 */
	interface BindFn : Callback {
		/**
		 * @param seq sequence token used to respond via [webview_return].
		 * @param req JSON-encoded call arguments.
		 * @param arg optional user data passed into [webview_bind].
		 */
		fun invoke(seq: String?, req: String?, arg: Pointer?)
	}
}