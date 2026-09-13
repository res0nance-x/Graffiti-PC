package r3.gui

import java.awt.Toolkit
import java.io.BufferedInputStream
import java.io.File
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineEvent

object DesktopBellPlayer {

	fun play(soundName: String) {
		Thread {
			try {
				val effectiveName = if (soundName.isBlank()) "chime" else soundName
				val soundFile = File(NativeResources.webDir, "sounds/$effectiveName.wav")
				val audioStream = if (soundFile.exists()) {
					AudioSystem.getAudioInputStream(soundFile)
				} else {
					val res = javaClass.getResourceAsStream("/web/sounds/$effectiveName.wav")
						?: javaClass.getResourceAsStream("/sounds/$effectiveName.wav")
						?: File(NativeResources.webDir, "sounds/chime.wav").takeIf { it.exists() }?.let { AudioSystem.getAudioInputStream(it) }
					if (res is java.io.InputStream) {
						AudioSystem.getAudioInputStream(BufferedInputStream(res))
					} else if (res is javax.sound.sampled.AudioInputStream) {
						res
					} else {
						null
					}
				}

				if (audioStream != null) {
					val clip = AudioSystem.getClip()
					clip.addLineListener { event ->
						if (event.type == LineEvent.Type.STOP) {
							clip.close()
							try {
								audioStream.close()
							} catch (_: Exception) {}
						}
					}
					clip.open(audioStream)
					clip.start()
				} else {
					Toolkit.getDefaultToolkit().beep()
				}
			} catch (_: Exception) {
				try {
					Toolkit.getDefaultToolkit().beep()
				} catch (_: Exception) {}
			}
		}.apply {
			isDaemon = true
			name = "DesktopBellPlayer"
			start()
		}
	}
}
