package r3.gui

import r3.graffiti.CustomTempFileManagerFactory
import r3.graffiti.GraffitiAPI
import r3.graffiti.GraffitiP2P
import r3.http.HandlerFactory
import r3.http.WebServer
import r3.pke.name
import java.io.File

private object MainAnchor

fun main(args: Array<String>) {
	cli(args)
}

fun cli(args: Array<String>) {
	// ── CLI flags ─────────────────────────────────────────────────────────────
	val dirIdx = args.indexOf("--dir")
	val dir = if (dirIdx >= 0 && dirIdx + 1 < args.size) File(args[dirIdx + 1])
	else {
		System.err.println("Usage: graffiti --dir <storage-dir> [--port <http-port>] [--p2p-port <p2p-port>] [--allow-internet] [--server-only] [--relay] [--quota <quota-mb>]"); return
	}
	val allowInternet = "--allow-internet" in args
	val serverOnly = "--server-only" in args
	val relay = "--relay" in args
	val portIdx = args.indexOf("--port")
	val httpPort = if (portIdx >= 0 && portIdx + 1 < args.size) args[portIdx + 1].toIntOrNull() ?: 0 else 0
	val p2pPortIdx = args.indexOf("--p2p-port")
	val p2pPort = if (p2pPortIdx >= 0 && p2pPortIdx + 1 < args.size) args[p2pPortIdx + 1].toIntOrNull() else null
	val quotaIdx = args.indexOf("--quota")
	val quotaMb = if (quotaIdx >= 0 && quotaIdx + 1 < args.size) args[quotaIdx + 1].toLongOrNull() else null
	val p2p = GraffitiP2P(dir, relayEnabledAtStartup = relay)
	if (quotaMb != null) {
		p2p.setQuotaBytes(quotaMb * 1024 * 1024)
	}
	if (p2pPort != null) {
		p2p.defaultP2PPort = p2pPort
	}
	println("Server identity: ${p2p.serverIdentity.key.name}")
	if (serverOnly) {
		val startPort = p2pPort ?: 38214
		p2p.defaultP2PPort = startPort
		p2p.startTCPServer(startPort)
		val actualP2PPort = p2p.serverPort ?: startPort
		println("P2P server started on port $actualP2PPort. Press Enter to stop.")
		readlnOrNull()
		println("Stopping server...")
		p2p.stopTCPServer()
		return
	}
	// Auto-start P2P server if --p2p-port was provided.
	if (p2pPort != null) {
		p2p.startTCPServer(p2pPort)
		println("P2P server started on port $p2pPort")
	}
	val webserver = WebServer(
		"localhost",
		httpPort,
		p2p.tmpDir
	)
	webserver.handlers.add(HandlerFactory.createLogRouter())
	webserver.handlers.add(HandlerFactory.createFileHandler(NativeResources.webDir))
	val api = GraffitiAPI(
		p2p,
		{ json ->
			webserver.sendToAllWebSockets(json.toString())
		}
	).apply {
		onOpenPack = { source, fileName, password ->
			DesktopPackManager.openPack(source, fileName, password, allowInternet)
		}
		onClosePack = { sessionId ->
			DesktopPackManager.closePack(sessionId)
		}
	}
	webserver.handlers.add(api)
	webserver.tempFileManagerFactory = CustomTempFileManagerFactory { p2p.tmpDir }

	webserver.start(0, true)
	val actualHttpPort = webserver.myServerSocket.localPort
	val url = "http://localhost:$actualHttpPort/index.html"
	val isWindows = System.getProperty("os.name").lowercase().contains("win")
	var useBrowserFallback = !isWindows

	if (isWindows) {
		try {
			WebView(true).use { webView ->
				webView
					.setTitle("Graffiti")
					.setIcon(NativeResources.webDir.resolve("favicon.ico"))
					.setSize(1200, 800)
					.also { if (!allowInternet) it.init(networkGuardScript()) }
					.navigate(url)
					.run()
			}
		} catch (t: Throwable) {
			System.err.println("Could not open custom webview: ${t.message}")
			useBrowserFallback = true
		}
	}

	if (useBrowserFallback) {
		println("Opening browser at $url")
		try {
			if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop()
					.isSupported(java.awt.Desktop.Action.BROWSE)
			) {
				java.awt.Desktop.getDesktop().browse(java.net.URI(url))
			} else {
				System.err.println("Please open the following URL in your browser: $url")
			}
		} catch (e: Exception) {
			System.err.println("Failed to open browser automatically: ${e.message}")
			System.err.println("Please open the following URL in your browser: $url")
		}
		println("Press Enter to stop the server...")
		readlnOrNull()
		println("Stopping server...")
	}
}

/**
 * JavaScript injected via webview_init before every page load.
 * Overrides `fetch`, `XMLHttpRequest`, and `WebSocket` to throw on any URL
 * whose host is not localhost / 127.0.0.1 / ::1.
 *
 * This runs as belt-and-suspenders alongside the HTTP Content-Security-Policy
 * header which already blocks external resources at the browser-engine level.
 */
private fun networkGuardScript() = """
(function () {
    'use strict';
    function isLocal(url) {
        try {
            const u = new URL(url, location.href);
            const h = u.hostname;
            return h === 'localhost' || h === '127.0.0.1' || h === '[::1]' || h === '';
        } catch (_) {
            return true; // relative URLs are always local
        }
    }

    // ── fetch() ──────────────────────────────────────────────────────────────
    const _fetch = window.fetch;
    window.fetch = function (input, init) {
        const url = input instanceof Request ? input.url : String(input);
        if (!isLocal(url)) {
            console.warn('[network-guard] Blocked external fetch:', url);
            return Promise.reject(new TypeError('External network access is disabled'));
        }
        return _fetch.call(this, input, init);
    };

    // ── XMLHttpRequest ───────────────────────────────────────────────────────
    const _xhrOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (method, url) {
        if (!isLocal(String(url))) {
            console.warn('[network-guard] Blocked external XHR:', url);
            throw new TypeError('External network access is disabled');
        }
        return _xhrOpen.apply(this, arguments);
    };

    // ── WebSocket ────────────────────────────────────────────────────────────
    const _WS = window.WebSocket;
    function GuardedWebSocket(url, protocols) {
        if (!isLocal(String(url))) {
            console.warn('[network-guard] Blocked external WebSocket:', url);
            throw new TypeError('External network access is disabled');
        }
        return protocols != null ? new _WS(url, protocols) : new _WS(url);
    }
    GuardedWebSocket.prototype = _WS.prototype;
    GuardedWebSocket.CONNECTING = _WS.CONNECTING;
    GuardedWebSocket.OPEN       = _WS.OPEN;
    GuardedWebSocket.CLOSING    = _WS.CLOSING;
    GuardedWebSocket.CLOSED     = _WS.CLOSED;
    window.WebSocket = GuardedWebSocket;
})();
""".trimIndent()

