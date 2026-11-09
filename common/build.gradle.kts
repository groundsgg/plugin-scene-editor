plugins {
    id("gg.grounds.kotlin-conventions")
    `maven-publish`
}

dependencies {
    api("gg.grounds:scene-format:0.4.4")
    implementation("gg.grounds:resourcepacks-catalog:1.3.0")
    implementation("gg.grounds:plugin-lobby-scene-catalog:2.23.0")

    testImplementation("gg.grounds:scene-testkit:0.4.5")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java { withSourcesJar() }

publishing {
    publications {
        withType<MavenPublication>().configureEach { artifactId = "plugin-scene-editor-common" }
    }
}
