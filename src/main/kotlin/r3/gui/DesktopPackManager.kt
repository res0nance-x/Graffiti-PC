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
}