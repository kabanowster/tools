import java.net.URI

plugins {
    `java-library`
    id("myJavaFx")
    id("coreTools")
    `maven-publish`
}

group = "io.krystal"
version = "0.1"

//tasks.jar {
////    destinationDirectory.set(file("C:\\Users\\wa_kabanow\\Downloads"))
//W
//    from("src/main/java")
//    exclude("*.java")
//}

publishing {
    // TODO generalize
    repositories {
        maven {
            name = "GitHubPackages"
            url = URI("https://maven.pkg.github.com/kabanowster/SOURCE")
            credentials {
                username = "kabanowster"
                password = "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"
            }
        }
    }

    publications {
        register<MavenPublication>("gpr") {
            from(components["java"])
        }
    }
}