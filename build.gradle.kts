plugins {
	kotlin("jvm") version "2.4.0"
}

group = "r3.graffiti"
version = "1.0"

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.example:R3:1.0-SNAPSHOT")
	implementation("org.example:GraffitiCore:1.0-SNAPSHOT")
	testImplementation(kotlin("test"))
	implementation("net.java.dev.jna:jna:5.14.0")
	implementation("net.java.dev.jna:jna-platform:5.14.0")
}


kotlin {
	jvmToolchain(25)
}


tasks.named<Jar>("jar") {
	archiveFileName.set("graffiti.jar")
	manifest {
		attributes["Main-Class"] = "r3.gui.MainKt"
	}
	from(sourceSets.main.get().output)
	from({
		configurations.runtimeClasspath.get().files.map {
			if (it.isDirectory) it else zipTree(it)
		}
	})
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}




val copyLib = tasks.register<Copy>("copyLib") {
	from(file("lib"))
	into(layout.buildDirectory.dir("libs/lib"))
}

val copyWeb = tasks.register<Copy>("copyWeb") {
	from(file("../GraffitiCore/src/main/resources/web"))
	into(layout.buildDirectory.dir("libs/web"))
}

val createAppImage = tasks.register<Exec>("createAppImage") {
	dependsOn("jar", copyLib, copyWeb)

	val outputDir = layout.buildDirectory.dir("tmp/app-image/Graffiti").get().asFile
	outputs.dir(outputDir)

	doFirst {
		if (outputDir.exists()) {
			outputDir.deleteRecursively()
		}
	}

	val javaHome = System.getProperty("java.home")
	val isWindows = System.getProperty("os.name").lowercase().contains("windows")
	val jpackageExec = if (isWindows) "$javaHome/bin/jpackage.exe" else "$javaHome/bin/jpackage"

	val iconFile = file("../GraffitiCore/src/main/resources/web/favicon.ico")

	commandLine(
		jpackageExec,
		"--name", "Graffiti",
		"--input", layout.buildDirectory.dir("libs").get().asFile.absolutePath,
		"--main-jar", "graffiti.jar",
		"--main-class", "r3.gui.MainKt",
		"--type", "app-image",
		"--icon", iconFile.absolutePath,
		"--java-options", "--enable-native-access=ALL-UNNAMED",
		"--jlink-options", "--strip-debug --no-man-pages --no-header-files",
		"--dest", layout.buildDirectory.dir("tmp/app-image").get().asFile.absolutePath
	)
}

val createLauncher = tasks.register("createLauncher") {
	dependsOn(createAppImage)
	val batFile = layout.buildDirectory.file("tmp/app-image/Graffiti/graffiti.bat").get().asFile
	outputs.file(batFile)
	doLast {
		batFile.writeText(
			"""
			@echo off
			set "APP_DIR=%~dp0"
			"%APP_DIR%runtime\bin\java.exe" --enable-native-access=ALL-UNNAMED -jar "%APP_DIR%app\graffiti.jar" %*
			""".trimIndent()
		)
	}
}

val distZip = tasks.register<Zip>("distZip") {
	dependsOn(createLauncher)
	archiveFileName.set("graffiti.zip")
	destinationDirectory.set(layout.buildDirectory.dir("dist"))
	from(layout.buildDirectory.dir("tmp/app-image/Graffiti")) {
		into("Graffiti")
	}
}

val dist = tasks.register("dist") {
	group = "distribution"
	description = "Cleans and builds fresh application distribution zip."
	dependsOn("clean", distZip)
}

distZip.get().mustRunAfter("clean")

tasks.withType<JavaExec> {
	jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.test {
	useJUnitPlatform()
}