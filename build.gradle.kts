import java.net.URI

plugins {
    `java-library`
    id("myJavaFx")
    id("coreTools")
    `maven-publish`
    id("net.linguica.maven-settings") version "0.5"
}

group = "io.krystal"
version = "1.0.0"

java {
    withJavadocJar()
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = "tools"
            from(components["java"])
            versionMapping {
                usage("java-api") {
                    fromResolutionOf("runtimeClasspath")
                }
                usage("java-runtime") {
                    fromResolutionResult()
                }
            }
            pom {
                name = "Krystal Tools"
                description = "Convenient tools used during development process."
                licenses {
                    license {
                        name = "The Apache License, Version 2.0"
                        url = "http://www.apache.org/licenses/LICENSE-2.0.txt"
                    }
                }
                developers {
                    developer {
                        id = "kabanowster"
                        name = "Wiktor Kabanow"
                        email = "kabanowster@gmail.com"
                    }
                }
            }
        }
    }
    repositories {
        maven {
            name = "BDE-Development"
            url = URI("https://dgd365o.pkgs.visualstudio.com/aa703476-5cc4-43ae-82af-8acbab9dab87/_packaging/BDE-Development/maven/v1")
            authentication {
                create<BasicAuthentication>("basic")
            }
        }
    }
}

tasks.javadoc {
    if (JavaVersion.current().isJava9Compatible) {
        (options as StandardJavadocDocletOptions).addBooleanOption("html5", true)
    }
}
//tasks.jar {
////    destinationDirectory.set(file("C:\\Users\\wa_kabanow\\Downloads"))
//W
//    from("src/main/java")
//    exclude("*.java")
//}

//publishing {
//    // TODO generalize
//    repositories {
//        maven {
//            name = "GitHubPackages"
//            url = URI("https://maven.pkg.github.com/kabanowster/SOURCE")
//            credentials {
//                username = "kabanowster"
//                password = "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"
//            }
//        }
//    }
//
//    publications {
//        register<MavenPublication>("gpr") {
//            from(components["java"])
//        }
//    }
//}