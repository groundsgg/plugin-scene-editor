plugins {
    id("gg.grounds.kotlin-conventions")
    `maven-publish`
}

dependencies {
    api("gg.grounds:scene-format:0.2.0")
    implementation("gg.grounds:resourcepacks-catalog:0.6.0")
    implementation("gg.grounds:plugin-lobby-scene-catalog:1.13.1")

    testImplementation("gg.grounds:scene-testkit:0.2.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java { withSourcesJar() }

publishing {
    publications {
        withType<MavenPublication>().configureEach { artifactId = "plugin-scene-editor-common" }
    }
}
