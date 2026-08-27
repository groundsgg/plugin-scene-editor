import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.tasks.GenerateModuleMetadata
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    id("gg.grounds.paper-conventions")
    `maven-publish`
}

configurations { testImplementation { extendsFrom(compileOnly.get()) } }

repositories { maven("https://repo.papermc.io/repository/maven-public/") }

dependencies {
    implementation(project(":common"))
    compileOnly("de.eintosti:buildsystem-api:4.0.0")

    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v26.1.2:4.114.0")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named("build") { dependsOn("shadowJar") }

tasks.named("jar") { enabled = false }

tasks.withType<ProcessResources>().configureEach {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") { expand(mapOf("VERSION" to project.version)) }
}

val paperShadowJar =
    tasks.named<ShadowJar>("shadowJar") {
        archiveBaseName.set("${rootProject.name}-${project.name}")
        archiveClassifier.set("")
        archiveVersion.set("")
        exclude("META-INF/maven/**")
    }

tasks.withType<Test>().configureEach {
    dependsOn(paperShadowJar)
    doFirst {
        systemProperty(
            "paper.shadowJar",
            paperShadowJar.get().archiveFile.get().asFile.absolutePath,
        )
    }
}

tasks.withType<GenerateModuleMetadata>().configureEach { enabled = false }

publishing {
    publications {
        withType<MavenPublication>().configureEach {
            artifactId = "plugin-scene-editor-paper"
            setArtifacts(listOf(paperShadowJar))
            pom {
                name.set("Grounds Scene Editor Paper")
                description.set("Paper scene authoring for Grounds build servers")
            }
        }
    }
}
