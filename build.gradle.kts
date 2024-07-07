plugins {
    id("setup")
    id("myJavaFx")
    id("publish")
}

group = "io.krystal"
version = "1.13.1"

java {
//    withJavadocJar()
    withSourcesJar()
}

dependencies {
    coreTools()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
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
}