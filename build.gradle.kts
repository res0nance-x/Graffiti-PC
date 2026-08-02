plugins {
	kotlin("jvm") version "2.4.0"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
	mavenCentral()
}

dependencies {
	testImplementation(kotlin("test"))
	implementation("net.java.dev.jna:jna:5.14.0")
	implementation("net.java.dev.jna:jna-platform:5.14.0")
}

sourceSets {
	main {
		java.srcDirs(
			file("src/main/kotlin"),
			file("../R3/src/main/kotlin"),
			file("../GraffitiCore/src/main/kotlin")
		)
		kotlin.srcDirs(
			file("src/main/kotlin"),
			file("../R3/src/main/kotlin"),
			file("../GraffitiCore/src/main/kotlin")
		)
		resources {
			srcDir(file("src/main/resources"))
			srcDir(file("../GraffitiCore/src/main/resources"))
		}
	}
}

kotlin {
	jvmToolchain(26)
}

tasks.test {
	useJUnitPlatform()
}