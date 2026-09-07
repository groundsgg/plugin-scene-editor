plugins { id("gg.grounds.base-conventions") version "0.8.1" }

allprojects {
    group = "gg.grounds"
    version = providers.gradleProperty("versionOverride").orNull ?: "0.1.0-SNAPSHOT"

    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/groundsgg/*")
            credentials {
                username =
                    providers.gradleProperty("github.user").orNull
                        ?: System.getenv("GITHUB_USER")
                        ?: System.getenv("GITHUB_ACTOR")
                        ?: ""
                password =
                    providers.gradleProperty("github.token").orNull
                        ?: System.getenv("GITHUB_TOKEN")
                        ?: ""
            }
        }
        mavenCentral()
    }
}
