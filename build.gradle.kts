
plugins {
    signing
    jacoco
    id("java-library")
    id("com.diffplug.spotless") version "8.8.0" apply false
    id("com.github.spotbugs") version "6.5.9" apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}

repositories {
    mavenCentral()
}

var useFutoinDevAPI = providers.gradleProperty("useFutoinDevAPI").getOrElse("false").toBoolean()

subprojects {
    if (project.name != "api") {
        apply {
            plugin("signing")
            plugin("jacoco")
            plugin("java-library")
            plugin("com.diffplug.spotless")
            plugin("com.github.spotbugs")
            plugin("com.vanniktech.maven.publish")
        }

        group = "org.futoin"
        version = "0.0.1"

        repositories {
            mavenCentral()
        }

        dependencies {
            if (useFutoinDevAPI) {
                implementation(project(":api"))
            } else {
                implementation("org.futoin:core-api:*")
            }

            // jUnit 6
            testImplementation(platform("org.junit:junit-bom:6.1.2"))
            testImplementation("org.junit.jupiter:junit-jupiter")
            testCompileOnly("org.junit.jupiter:junit-jupiter-params")
            testRuntimeOnly("org.junit.platform:junit-platform-launcher")

            // Mockito 5
            testImplementation("org.mockito:mockito-core:5.23.0")
        }

        java {
            // Implied by com.vanniktech.maven.publish
            // withJavadocJar()
            // withSourcesJar()

            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        tasks.compileJava {
            options.javaModuleVersion = provider { version as String }
            options.compilerArgs.addAll(listOf("-Xlint:all", "-Xdoclint:all", "-Werror", "-Xdiags:verbose"))
        }
        tasks.compileTestJava {
            options.javaModuleVersion = provider { version as String }
            options.compilerArgs.addAll(listOf("-Xlint:all", "-Xdoclint:none", "-Werror", "-Xdiags:verbose"))
        }


        configure<com.diffplug.gradle.spotless.SpotlessExtension> {
            java {
                googleJavaFormat().aosp().formatJavadoc(false)
                formatAnnotations()
            }
        }

        configure<com.github.spotbugs.snom.SpotBugsExtension> {
            excludeFilter.set(file("src/spotbugs-exclude.xml"))
        }

        tasks.test {
            useJUnitPlatform()
            testLogging {
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                showStandardStreams = true
                showCauses = true

                // events("standardOut", "standardError", "passed", "skipped", "failed")
                events("passed", "skipped", "failed")
            }
            finalizedBy(tasks.jacocoTestReport)
            jvmArgs.add("-XX:+EnableDynamicAgentLoading")
        }
        tasks.jacocoTestReport {
            dependsOn(tasks.test)

            reports {
                xml.required = false
                csv.required = false
                html.outputLocation = layout.buildDirectory.dir("jacocoHtml")
            }
        }

        configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            publishToMavenCentral(automaticRelease = true)

            signAllPublications()

            excludeSignatureChecksums(true)

            coordinates(group as String, name as String, version as String)

            pom {
                name.set("FutoIn Core API")
                description.set("FutoIn Core programmatic API for loose coupling.")
                inceptionYear.set("2013")
                url.set("https://futoin.org/")
                licenses {
                    license {
                        name.set("FutoIn Public License 1.0")
                        url.set("https://specs.futoin.org/LICENSE.txt")
                        distribution.set("https://specs.futoin.org/LICENSE.txt")
                    }
                    }
                developers {
                    developer {
                        id.set("andvgal")
                        name.set("Andrey Galkin")
                        url.set("https://github.com/andvgal/")
                    }
                }
                scm {
                    url.set("https://github.com/futoin/core-java-api/")
                    connection.set("scm:git:git://github.com/futoin/core-java-api.git")
                }
            }
        }

        signing {
            useGpgCmd()
        }

        configure<PublishingExtension> {
            repositories {
                maven {
                    name = "BuildRepo"
                    url = uri(layout.buildDirectory.dir("repo"))
                }
            }
        }
    }
}
