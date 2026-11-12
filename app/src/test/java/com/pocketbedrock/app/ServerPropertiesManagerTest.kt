package com.pocketbedrock.app

import com.pocketbedrock.app.core.ServerPropertiesManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ServerPropertiesManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newManager(initial: String): Pair<ServerPropertiesManager, File> {
        val file = File(tmp.root, "server.properties")
        file.writeText(initial)
        return ServerPropertiesManager { file } to file
    }

    @Test
    fun `reads key value pairs`() {
        val (mgr, _) = newManager("server-name=Test\n\n# comment\nlevel-name=world\n")
        val props = mgr.read()
        assertEquals(2, props.size)
        assertEquals("Test", props["server-name"])
        assertEquals("world", props["level-name"])
    }

    @Test
    fun `write merges and preserves order and unknown keys`() {
        val (mgr, _) = newManager("server-name=Test\nlevel-name=world\nsome-unknown=1\n")
        mgr.write(mapOf("max-players" to "5", "level-name" to "creative"))

        val after = mgr.read()
        assertEquals(4, after.size)
        assertEquals("Test", after["server-name"])       // order preserved
        assertEquals("creative", after["level-name"])    // updated in place
        assertEquals("1", after["some-unknown"])         // unknown key preserved
        assertEquals("5", after["max-players"])          // new key appended
    }

    @Test
    fun `validate catches ranges types and enums`() {
        val (mgr, _) = newManager("")
        val errors = mgr.validate(
            mapOf(
                "max-players" to "0",
                "view-distance" to "99",
                "gamemode" to "nope",
                "allow-cheats" to "maybe",
                "server-port" to "19132",
            ),
        )
        assertEquals("Minimum is 1", errors["max-players"])
        assertEquals("Maximum is 32", errors["view-distance"])
        assertNotNull(errors["gamemode"])
        assertEquals("Must be true or false", errors["allow-cheats"])
        assertEquals(null, errors["server-port"])
    }

    @Test
    fun `catalog has unique keys`() {
        val keys = com.pocketbedrock.app.core.PropertyCatalog.specs.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.contains("allow-list"))
        assertTrue(keys.contains("server-portv6"))
    }
}
