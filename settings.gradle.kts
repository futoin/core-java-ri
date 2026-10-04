
plugins {
    id("io.github.ben-manes.versions.settings") version "0.64.0"
}

rootProject.name = "core-ri"

val useFutoinDevAPI = providers.gradleProperty("useFutoinDevAPI").getOrElse("false")

if (useFutoinDevAPI.toBoolean()) {
    include("api")
    findProject(":api")?.projectDir = File("../core-java-api")
}

include("asyncsteps")
