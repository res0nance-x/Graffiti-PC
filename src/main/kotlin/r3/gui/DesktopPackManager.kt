package r3.gui

import r3.content.BinaryContent
import r3.encryption.EncryptedSource
import r3.hash.hash256
import r3.http.HandlerFactory
import r3.http.WebServer
import r3.io.log
import r3.math.EncryptedSequence
import r3.pack.BinaryPack
import r3.pack.Pack
import r3.pack.RAMPack
import r3.pke.Password256
import r3.source.FileSource
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JOptionPane
import javax.swing.JPasswordField
import kotlin.concurrent.thread

object DesktopPackManager {
	private val activePackServers = ConcurrentHashMap<String, WebServer>()

	fun openPack(source: r3.source.Source, fileName: String, passwordStr: String? = null, allowInternet: Boolean = false): Pair<String, Int> {
		val isEncrypted = fileName.endsWith(".epack", ignoreCase = true) || !passwordStr.isNullOrEmpty()
		var pass = passwordStr

		if (isEncrypted && pass.isNullOrEmpty()) {
			val promptResult = showPasswordDialog(fileName)
			if (promptResult.isNullOrEmpty()) {
				throw IllegalArgumentException("PASSWORD_REQUIRED")
			}
			pass = promptResult
		}

		val pack: Pack = if (isEncrypted) {
			val p = Password256(pass!!.toByteArray().hash256())
			val sequence = EncryptedSequence.createSequence(p)
			val encryptedSrc = EncryptedSource(sequence, source)
			BinaryPack(encryptedSrc)
		} else {
			BinaryPack(source)
		}

		// Read keys to validate decryption/password
		try {
			pack.keys.size
		} catch (e: Exception) {
			throw IllegalArgumentException("INVALID_PASSWORD")
		}

		val sessionId = UUID.randomUUID().toString()
		val tmpDir = File(System.getProperty("java.io.tmpdir"))
		val webserver = WebServer("localhost", 0, tmpDir)
		webserver.handlers.add(HandlerFactory.createLogRouter())
		webserver.handlers.add(HandlerFactory.createWelcomeHandler())
		webserver.handlers.add(HandlerFactory.createPackHandler(pack))
		webserver.handlers.add(HandlerFactory.createPackHandler(getDefaultTemplatePack()))

		webserver.start(0, true)
		val port = webserver.listeningPort
		activePackServers[sessionId] = webserver

		thread(name = "PackViewer-$sessionId", isDaemon = true) {
			try {
				val url = "http://localhost:$port/"
				log("Opening PackViewer window at $url")
				WebView(false).use { wv ->
					wv.setTitle("PackViewer")
						.setIcon(NativeResources.webDir.resolve("favicon.ico"))
						.setSize(1024, 768)
						.init(packGuardScript())
						.navigate(url)
						.run()
				}
			} catch (e: Exception) {
				log("Error running pack webview window: ${e.message}")
			} finally {
				// Grace period delay before closing server
				thread(isDaemon = true) {
					Thread.sleep(3000)
					closePack(sessionId)
				}
			}
		}

		return Pair(sessionId, port)
	}

	fun closePack(sessionId: String) {
		val server = activePackServers.remove(sessionId)
		if (server != null) {
			try {
				server.stop()
				log("Stopped Pack WebServer session $sessionId")
			} catch (e: Exception) {
				System.err.println("Error stopping Pack WebServer session $sessionId: ${e.message}")
			}
		}
	}

	private fun showPasswordDialog(packName: String): String? {
		return try {
			val pf = JPasswordField()
			val option = JOptionPane.showConfirmDialog(
				null,
				pf,
				"Enter password for $packName",
				JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.PLAIN_MESSAGE
			)
			if (option == JOptionPane.OK_OPTION) {
				String(pf.password)
			} else {
				null
			}
		} catch (e: Exception) {
			null
		}
	}

	private fun getDefaultTemplatePack(): Pack {
		return try {
			val file = NativeResources.webDir.resolve("playlist/index.html")
			if (file.exists()) {
				val bytes = file.readBytes()
				val pack = RAMPack()
				pack["index.html"] = BinaryContent(bytes, "index.html", "html")
				pack
			} else {
				val resourcePath = "playlist/index.html"
				val stream = Thread.currentThread().contextClassLoader?.getResourceAsStream(resourcePath)
					?: ClassLoader.getSystemResourceAsStream(resourcePath)
				val bytes = stream?.use { it.readBytes() }
				if (bytes != null) {
					val pack = RAMPack()
					pack["index.html"] = BinaryContent(bytes, "index.html", "html")
					pack
				} else {
					RAMPack()
				}
			}
		} catch (e: Exception) {
			RAMPack()
		}
	}

	private fun packGuardScript(): String = """
(function () {
    'use strict';
    const SHIELD_SVG = "data:image/svg+xml;charset=utf-8," + encodeURIComponent(
        '<svg xmlns="http://www.w3.org/2000/svg" width="220" height="130" viewBox="0 0 220 130">' +
        '<rect width="100%" height="100%" fill="#111827" stroke="#374151" stroke-width="1.5" rx="6"/>' +
        '<text x="50%" y="40%" dominant-baseline="middle" text-anchor="middle" font-size="28">🛡️</text>' +
        '<text x="50%" y="68%" dominant-baseline="middle" text-anchor="middle" fill="#e5e7eb" font-family="sans-serif" font-size="11" font-weight="600">Remote Media Blocked</text>' +
        '<text x="50%" y="85%" dominant-baseline="middle" text-anchor="middle" fill="#9ca3af" font-family="sans-serif" font-size="9">Packs run offline</text>' +
        '</svg>'
    );

    function isRemote(url) {
        if (!url) return false;
        try {
            const u = new URL(url, location.href);
            const h = u.hostname.toLowerCase();
            return h !== 'localhost' && h !== '127.0.0.1' && h !== '[::1]' && h !== '' && u.protocol.startsWith('http');
        } catch (_) {
            return false;
        }
    }

    try {
        const desc = Object.getOwnPropertyDescriptor(HTMLImageElement.prototype, 'src');
        if (desc && desc.set) {
            const origSet = desc.set;
            Object.defineProperty(HTMLImageElement.prototype, 'src', {
                set(val) {
                    if (isRemote(val)) {
                        console.warn('[pack-guard] Blocked remote image in pack:', val);
                        this.title = 'Remote image blocked to protect your IP: ' + val;
                        return origSet.call(this, SHIELD_SVG);
                    }
                    return origSet.call(this, val);
                }
            });
        }
    } catch (_) {}

    window.addEventListener('error', function (e) {
        const target = e.target;
        if (target && target.tagName === 'IMG') {
            const src = target.getAttribute('src') || '';
            if (isRemote(src) || (target.src && target.src.startsWith('http') && !target.src.includes('localhost'))) {
                target.src = SHIELD_SVG;
                target.title = 'Remote image blocked: ' + src;
            }
        }
    }, true);
})();
""".trimIndent()
}