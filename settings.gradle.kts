plugins {
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "Graffiti"

includeBuild("../R3")
includeBuild("../GraffitiCore")