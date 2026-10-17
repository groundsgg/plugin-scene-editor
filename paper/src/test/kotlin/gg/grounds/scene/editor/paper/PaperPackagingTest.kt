package gg.grounds.scene.editor.paper

import java.util.jar.JarFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

class PaperPackagingTest {
    @Test
    fun `catalog includes every declared Bukkit permission with the artifact version`() {
        JarFile(System.getProperty("paper.shadowJar")).use { jar ->
            fun read(name: String) =
                jar.getInputStream(requireNotNull(jar.getJarEntry(name))).bufferedReader().use {
                    it.readText()
                }
            val descriptor = Yaml().load<Map<String, Any>>(read("plugin.yml"))
            val resource = "META-INF/grounds/permissions/plugin-scene-editor.json"
            assertEquals(
                listOf(resource),
                read("META-INF/grounds/permissions.index")
                    .lineSequence()
                    .filter { it.isNotBlank() }
                    .toList(),
            )
            // Parse raw maps so dotted permission names stay literal, rather than
            // being converted into Bukkit configuration paths.
            val catalog = Yaml().load<Map<String, Any>>(read(resource))
            assertEquals("plugin-scene-editor", catalog["source"])
            assertEquals(descriptor["version"], catalog["sourceVersion"])
            @Suppress("UNCHECKED_CAST")
            val entries = catalog["permissions"] as List<Map<String, Any>>
            val declared = (descriptor["permissions"] as Map<*, *>).keys
            assertEquals(declared, entries.map { it["key"] }.toSet())
            entries.forEach { entry ->
                assertTrue((entry["label"] as String).isNotBlank())
                assertTrue((entry["description"] as String).isNotBlank())
                assertEquals(listOf("GLOBAL", "SERVER_TYPE", "SERVER"), entry["supportedScopes"])
            }
        }
    }

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
        assertEquals("1.0.0", catalogVersion)
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
        assertTrue(pluginYml.contains("grounds.scene.npc.collision.set:"))
        assertTrue(pluginYml.contains("grounds.scene.npc.collision.strength:"))
        assertTrue(pluginYml.contains("grounds.scene.npc.collision.distance:"))
    }
}
