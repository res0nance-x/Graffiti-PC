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




val createJre = tasks.register<Exec>("createJre") {
	val jreDir = layout.buildDirectory.dir("libs/jre").get().asFile
	doFirst {
		if (jreDir.exists()) {
			jreDir.deleteRecursively()
		}
	}
	val javaHome = System.getProperty("java.home")
	val isWindows = System.getProperty("os.name").lowercase().contains("windows")
	val jlinkExec = if (isWindows) "$javaHome/bin/jlink.exe" else "$javaHome/bin/jlink"

	commandLine(
		jlinkExec,
		"--add-modules", "java.base,java.desktop,java.logging,java.net.http,java.scripting,java.sql,java.naming,java.management,java.xml,jdk.unsupported",
		"--strip-debug",
		"--no-man-pages",
		"--no-header-files",
		"--compress=zip-6",
		"--output", jreDir.absolutePath
	)
}

val createLauncher = tasks.register("createLauncher") {
	val batFile = layout.buildDirectory.file("libs/graffiti.bat").get().asFile
	outputs.file(batFile)
	doLast {
		batFile.writeText(
			"""
			@echo off
			set "APP_DIR=%~dp0"
			"%APP_DIR%jre\bin\java.exe" --enable-native-access=ALL-UNNAMED -jar "%APP_DIR%graffiti.jar" %*
			""".trimIndent()
		)
	}
}


val copyLib = tasks.register<Copy>("copyLib") {
	from(file("lib"))
	into(layout.buildDirectory.dir("libs/lib"))
}

val copyWeb = tasks.register<Copy>("copyWeb") {
	from(file("../GraffitiCore/src/main/resources/web"))
	into(layout.buildDirectory.dir("libs/web"))
}

val distZip = tasks.register<Zip>("distZip") {
	dependsOn("jar", copyLib, copyWeb, createJre, createLauncher)
	archiveFileName.set("graffiti.zip")
	destinationDirectory.set(layout.buildDirectory.dir("distributions"))
	from(layout.buildDirectory.dir("libs").get().asFile) {
		into("graffiti")
	}
}

tasks.withType<JavaExec> {
	jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.named("build") {
	dependsOn(distZip)
}

tasks.test {
	useJUnitPlatform()
}