package r3.gui

import com.sun.jna.Function
import com.sun.jna.NativeLibrary

private val requiredSymbols = listOf(
	"webview_create",
	"webview_destroy",
	"webview_run",
	"webview_set_title",
	"webview_set_size",
	"webview_set_html"
)
private val optionalSymbols = listOf(
	"webview_navigate",
	"webview_init",
	"webview_eval",
	"webview_bind",
	"webview_unbind",
	"webview_return",
	"webview_dispatch",
	"webview_terminate",
	"webview_get_window",
	"webview_get_native_handle",
)

// Unused but may be helpful sometime in the future
fun probeSymbols(libName: String) {
	val lib = NativeLibrary.getInstance(libName)
	fun has(name: String): Boolean = try {
		lib.getFunction(name, Function.ALT_CONVENTION) // stdcall on Windows if needed
		true
	} catch (_: UnsatisfiedLinkError) {
		false
	}

	val missingRequired = requiredSymbols.filterNot(::has)
	val missingOptional = optionalSymbols.filterNot(::has)

	if (missingRequired.isNotEmpty()) {
		error("Missing required exports in $libName.dll: $missingRequired")
	}

	if (missingOptional.isNotEmpty()) {
		println("Optional exports not found: $missingOptional")
	}
}