package rw.aurum.pathtracer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RouteStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun route(id: String = "abc-123") = Route(
        id, "Radiology", 1L,
        listOf(
            Leg("leg0.jpg", 0.3f, LegEntry.Start, listOf(TrailPoint(0f, -1f, 0f), TrailPoint(0f, -1f, -2f))),
            Leg("leg1.jpg", 0.2f, LegEntry.Elevator("5"), listOf(TrailPoint(0f, -1f, 0f), TrailPoint(1f, -1f, 0f))),
        ),
    )

    private fun recorded(store: RouteStore, r: Route = route()): Route {
        r.legs.forEach { File(store.draft, it.photo).writeBytes(byteArrayOf(1, 2, 3)) }
        store.saveDraft(r)
        return r
    }

    @Test fun savesListsAndRenames() {
        val store = RouteStore(tmp.newFolder("routes"))
        val r = recorded(store)
        assertEquals(listOf("Radiology"), store.list().map { it.name })
        store.rename(r, "X-ray")
        assertEquals("X-ray", store.load(r.id)?.name)
        // The draft folder is not a route.
        store.draft
        assertEquals(1, store.list().size)
    }

    @Test fun sharedFileOpensOnAnotherPhone() {
        val mine = RouteStore(tmp.newFolder("mine"))
        val r = recorded(mine)
        val file = File(tmp.root, "share/Radiology.pathroute")
        mine.export(r, file)

        val friend = RouteStore(tmp.newFolder("friend"))
        val got = file.inputStream().use(friend::import)
        assertEquals(r.name, got.name)
        assertEquals(r.legs.size, got.legs.size)
        assertTrue(friend.photo(got, got.legs[1]).exists())
        assertNotNull(friend.load(r.id))
    }

    @Test fun sharesCameraViewsWithTheRoute() {
        val mine = RouteStore(tmp.newFolder("mine2"))
        val view = CameraView(
            "leg0_v0.jpg", "leg0_v0.depth", 160, 90, 900f, 900f, 640f, 360f,
            CameraPose(0.1f, 1.4f, 0.5f, 0f, 0f, 0f, 1f),
        )
        val r = route().let { it.copy(legs = listOf(it.legs[0].copy(sign = false, views = listOf(view)), it.legs[1])) }
        recorded(mine, r)
        listOf("leg0_v0.jpg", "leg0_v0.depth").forEach { File(tmp.root, "mine2/${r.id}/$it").writeBytes(byteArrayOf(7)) }
        val file = File(tmp.root, "share2/r.pathroute")
        mine.export(r, file)

        val friend = RouteStore(tmp.newFolder("friend2"))
        val got = file.inputStream().use(friend::import)
        assertEquals(listOf(view), got.legs[0].views)
        assertFalse(got.legs[0].sign)
        assertTrue(friend.file(got, "leg0_v0.depth").exists())
    }

    @Test fun rejectsFilesThatAreNotRoutes() {
        val store = RouteStore(tmp.newFolder("routes"))
        try {
            zip("hello.txt" to "hi".toByteArray()).inputStream().use(store::import)
            fail()
        } catch (e: IllegalStateException) {
            assertEquals("This file isn't a Path Tracer route", e.message)
        }
        assertTrue(store.list().isEmpty())
    }

    @Test fun ignoresPathsThatEscapeTheFolder() {
        val store = RouteStore(tmp.newFolder("routes"))
        val r = route()
        val bytes = zip(
            "route.json" to r.toJson().toString().toByteArray(),
            "leg0.jpg" to byteArrayOf(1),
            "leg1.jpg" to byteArrayOf(1),
            "../evil.jpg" to byteArrayOf(1),
        )
        bytes.inputStream().use(store::import)
        assertFalse(File(tmp.root, "evil.jpg").exists())
    }

    @Test fun rejectsRoutesPointingAtOtherFiles() {
        val store = RouteStore(tmp.newFolder("routes"))
        val bad = route().let { it.copy(legs = listOf(it.legs[0].copy(photo = "../../secret.jpg"))) }
        try {
            zip("route.json" to bad.toJson().toString().toByteArray()).inputStream().use(store::import)
            fail()
        } catch (e: IllegalStateException) {
            assertEquals("This route is missing a photo", e.message)
        }
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            entries.forEach { (name, data) ->
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
