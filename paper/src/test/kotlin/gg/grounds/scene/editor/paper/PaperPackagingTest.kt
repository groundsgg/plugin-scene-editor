package gg.grounds.scene.editor.paper

import java.util.jar.JarFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaperPackagingTest {
    @Test
    fun `shadow jar embeds the public api and required scene dependencies once`() {
        val (entries, catalogVersion) =
            JarFile(System.getProperty("paper.shadowJar")).use { jar ->
                val entries = jar.entries().asSequence().map { it.name }.toList()
                val catalogVersion =
                    jar.getInputStream(
                            jar.getJarEntry("gg/grounds/resourcepacks/catalog/catalog-version.txt")
                        )
                        .bufferedReader()
                        .use { it.readText().trim() }
                entries to catalogVersion
            }

        assertEquals(1, entries.count { it == "gg/grounds/scene/editor/SceneEditStatus.class" })
        assertEquals(1, entries.count { it == "gg/grounds/scene/format/SceneJson.class" })
        assertEquals(
            1,
            entries.count { it == "gg/grounds/resourcepacks/catalog/GroundsAssetCatalog.class" },
        )
        assertEquals(
            1,
            entries.count { it == "gg/grounds/resourcepacks/catalog/catalog-version.txt" },
        )
        assertEquals("0.6.0", catalogVersion)
        assertFalse(entries.any { it.startsWith("org/junit/") })
    }

    @Test
    fun `plugin descriptor contains expanded version and hard BuildSystem dependency`() {
        val pluginYml =
            JarFile(System.getProperty("paper.shadowJar")).use { jar ->
                jar.getInputStream(jar.getJarEntry("plugin.yml")).bufferedReader().use {
                    it.readText()
                }
            }

        assertFalse(pluginYml.contains("\${VERSION}"))
        assertTrue(pluginYml.contains("depend: [BuildSystem]"))
        assertTrue(pluginYml.contains("scene:"))
    }
}
