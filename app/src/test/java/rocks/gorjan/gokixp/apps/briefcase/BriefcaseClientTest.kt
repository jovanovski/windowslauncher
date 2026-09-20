package rocks.gorjan.gokixp.apps.briefcase

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress

/**
 * The Briefcase wire, spoken to a server that answers the way BRIEFCASE-PROTOCOL.md says a
 * winos computer answers.
 *
 * Worth pinning to a real socket rather than a mocked one: what this code gets wrong is never
 * the shape of a data class, it is the things only a connection has - which header the token
 * rides in, whether a name is percent-encoded, whether a 304 is read as "unchanged" or as an
 * error, and what a redirect to somebody's home page turns into. The last one is not
 * hypothetical: gorjan.rocks answers `/briefcase.php/v1/hello` with a 302 to index.html
 * whenever the server half is not deployed, and the person typing the address deserves to be
 * told that rather than "cannot find".
 */
class BriefcaseClientTest {

    private lateinit var server: HttpServer
    private lateinit var host: String

    /** What the server was last asked, so a test can check how it was asked. */
    private var lastMethod: String? = null
    private var lastPath: String? = null
    private var lastQuery: String? = null
    private var lastBody: String? = null
    private var lastBytes: ByteArray? = null
    private var lastHeaders: Map<String, List<String>> = emptyMap()

    /** What the server should answer with next: status to (body, headers). */
    private var reply: (HttpExchange) -> Unit = { respond(it, 404, """{"error":"no","code":"not_found"}""") }

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            lastMethod = exchange.requestMethod
            lastPath = exchange.requestURI.path
            // Raw, because what is being checked is what went down the wire
            lastQuery = exchange.requestURI.rawQuery
            lastHeaders = exchange.requestHeaders.toMap()
            lastBytes = exchange.requestBody.readBytes()
            lastBody = lastBytes?.toString(Charsets.UTF_8)
            reply(exchange)
        }
        server.start()
        host = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    // ---- Connecting ------------------------------------------------------------------------

    @Test
    fun `hello says what the computer is and what it allows`() {
        reply = {
            respond(
                it, 200,
                """{"service":"winos-briefcase","protocol":1,"site":"Windows 98",
                   "auth":["pair","password"],
                   "limits":{"file_bytes":104857600,"name_chars":200,"poll_seconds":15}}"""
            )
        }

        val hello = BriefcaseClient(host).hello()

        assertEquals("/briefcase.php/v1/hello", lastPath)
        assertTrue(hello.isWinos)
        assertEquals(1, hello.protocol)
        assertEquals("Windows 98", hello.site)
        assertEquals(listOf("pair", "password"), hello.auth)
        assertEquals(104857600L, hello.limits.fileBytes)
        assertEquals(15, hello.limits.pollSeconds)
        assertNull(lastHeaders["Authorization"])
    }

    @Test
    fun `a code is sent as the computer showed it, however it was typed`() {
        reply = {
            respond(
                it, 200,
                """{"token":"wbc_9f2c","device":{"id":"d1d6","name":"Pixel 8","expires":1821381015},
                   "user":{"name":"gorjan"},"site":"Windows 98","rev":5}"""
            )
        }

        val pairing = BriefcaseClient(host).pair(" b2a9ykg5 ", "Pixel 8", "Android 15")

        assertTrue(lastBody!!.contains(""""code":"B2A9-YKG5""""))
        assertTrue(lastBody!!.contains(""""platform":"Android 15""""))
        assertEquals("wbc_9f2c", pairing.token)
        assertEquals("d1d6", pairing.deviceId)
        assertEquals("gorjan", pairing.user)
        assertEquals(5L, pairing.rev)
    }

    @Test
    fun `the token rides in both headers, because some servers eat one of them`() {
        reply = { respond(it, 200, """{"rev":46}""") }

        val rev = BriefcaseClient(host, "wbc_9f2c").rev()

        assertEquals(46L, rev)
        assertEquals(listOf("Bearer wbc_9f2c"), lastHeaders["Authorization"])
        assertEquals(listOf("wbc_9f2c"), lastHeaders["X-winos-token"])
    }

    // ---- Listing and reading -----------------------------------------------------------------

    @Test
    fun `catching up asks only for what has changed`() {
        reply = {
            respond(
                it, 200,
                """{"files":[{"id":"share-5ddb","name":"Note from phone.txt","size":21,
                   "sha256":"3e99","modified":1789844984}],"deleted":["share-1a2b"],"rev":46}"""
            )
        }

        val listing = BriefcaseClient(host, "t").files(since = 41L)

        assertEquals("/briefcase.php/v1/files", lastPath)
        assertEquals("since=41", lastQuery)
        assertEquals(1, listing.files.size)
        assertEquals("Note from phone.txt", listing.files[0].name)
        assertEquals(21L, listing.files[0].size)
        assertEquals(listOf("share-1a2b"), listing.deleted)
        assertEquals(46L, listing.rev)
    }

    @Test
    fun `a first listing asks for everything`() {
        reply = { respond(it, 200, """{"files":[],"deleted":[],"rev":1}""") }

        BriefcaseClient(host, "t").files(since = null)

        assertNull(lastQuery)
    }

    @Test
    fun `reading contents writes the bytes, and a 304 says there was no need`() {
        val into = File.createTempFile("briefcase", ".txt").apply { deleteOnExit() }

        reply = { respond(it, 200, "the bytes") }
        assertTrue(BriefcaseClient(host, "t").download("share-5ddb", into))
        assertEquals("the bytes", into.readText())
        assertEquals("/briefcase.php/v1/files/share-5ddb/content", lastPath)

        reply = { it.sendResponseHeaders(304, -1); it.close() }
        assertFalse(BriefcaseClient(host, "t").download("share-5ddb", into, ifNoneMatch = "3e99"))
        assertEquals(listOf("\"3e99\""), lastHeaders["If-none-match"])
        assertEquals("the bytes", into.readText())
    }

    // ---- Writing -------------------------------------------------------------------------------

    @Test
    fun `putting a file in names it in the query and hashes it on the way`() {
        val source = File.createTempFile("holiday", ".jpg").apply {
            writeText("photo")
            deleteOnExit()
        }
        reply = {
            respond(
                it, 201,
                """{"file":{"id":"share-1","name":"Copy of Holiday photo.jpg","size":5,
                   "sha256":"abc","modified":1789845600},"rev":47}"""
            )
        }

        val saved = BriefcaseClient(host, "t").upload("Holiday photo.jpg", source)

        assertEquals("POST", lastMethod)
        assertEquals("name=Holiday%20photo.jpg", lastQuery)
        assertEquals("photo", lastBody)
        assertEquals(listOf(BriefcaseClient.sha256(source)), lastHeaders["X-winos-sha256"])
        // Section 2.6: the reply tells you the name it ended up with - use it, do not assume.
        assertEquals("Copy of Holiday photo.jpg", saved.name)
    }

    @Test
    fun `bytes the computer already holds are claimed rather than sent`() {
        reply = { respond(it, 200, """{"have":["4c8a"]}""") }
        assertEquals(setOf("4c8a"), BriefcaseClient(host, "t").have(listOf("4c8a", "7f1b")))
        assertTrue(lastBody!!.contains(""""sha256":["4c8a","7f1b"]"""))

        reply = {
            respond(it, 201, """{"file":{"id":"share-2","name":"a.jpg","size":5,"sha256":"4c8a","modified":1},"rev":48}""")
        }
        val saved = BriefcaseClient(host, "t").uploadKnown("a.jpg", "4c8a")
        assertEquals("share-2", saved.id)
        assertEquals("/briefcase.php/v1/files", lastPath)
    }

    @Test
    fun `replacing contents says which version it is replacing`() {
        val source = File.createTempFile("packing", ".txt").apply {
            writeText("socks")
            deleteOnExit()
        }
        reply = {
            respond(it, 200, """{"file":{"id":"share-3","name":"Packing list.txt","size":5,"sha256":"new","modified":2},"rev":49}""")
        }

        BriefcaseClient(host, "t").replace("share-3", source, ifMatch = "old")

        assertEquals("PUT", lastMethod)
        assertEquals("/briefcase.php/v1/files/share-3/content", lastPath)
        assertEquals(listOf("\"old\""), lastHeaders["If-match"])
        assertEquals("socks", lastBody)
    }

    @Test
    fun `renaming and deleting go to the file's own address`() {
        reply = {
            respond(it, 200, """{"file":{"id":"share-4","name":"Packing list.txt","size":1,"sha256":"a","modified":3},"rev":50}""")
        }
        assertEquals("Packing list.txt", BriefcaseClient(host, "t").rename("share-4", "Packing list.txt").name)
        assertEquals("/briefcase.php/v1/files/share-4/name", lastPath)

        reply = { respond(it, 200, """{"rev":51,"deleted":"share-4"}""") }
        assertEquals(51L, BriefcaseClient(host, "t").delete("share-4"))
        assertEquals("DELETE", lastMethod)
    }

    // ---- When it goes wrong ----------------------------------------------------------------------

    @Test
    fun `a refusal keeps the server's sentence and its word for the program`() {
        reply = {
            respond(
                it, 507,
                """{"error":"There is not enough space on the disk to save this file.","code":"no_space"}"""
            )
        }

        try {
            BriefcaseClient(host, "t").files()
            fail("a 507 should have been refused")
        } catch (e: BriefcaseException) {
            assertEquals(BriefcaseProtocol.CODE_NO_SPACE, e.code)
            assertEquals(507, e.status)
            assertEquals("There is not enough space on the disk to save this file.", e.message)
            assertFalse(e.isAuthFailure)
        }
    }

    @Test
    fun `a dead token is told apart from everything else`() {
        reply = { respond(it, 401, """{"error":"Not allowed","code":"unauthorized"}""") }

        try {
            BriefcaseClient(host, "wbc_dead").session()
            fail("a 401 should have been refused")
        } catch (e: BriefcaseException) {
            assertTrue(e.isAuthFailure)
            assertFalse(e.isNetworkFailure)
        }
    }

    @Test
    fun `too many tries carries how long to wait`() {
        reply = { respond(it, 429, """{"error":"Slow down","code":"rate_limited","retry":30}""") }

        try {
            BriefcaseClient(host).pair("B2A9-YKG5", "Pixel 8", "Android 15")
            fail("a 429 should have been refused")
        } catch (e: BriefcaseException) {
            assertEquals(BriefcaseProtocol.CODE_RATE_LIMITED, e.code)
            assertEquals(30, e.retryAfter)
        }
    }

    @Test
    fun `a site that redirects is not a winos computer`() {
        reply = {
            it.responseHeaders.add("Location", "/index.html")
            it.sendResponseHeaders(302, -1)
            it.close()
        }

        try {
            BriefcaseClient(host).hello()
            fail("a redirect should not have been followed")
        } catch (e: BriefcaseException) {
            assertEquals(BriefcaseProtocol.CODE_NOT_WINOS, e.code)
        }
    }

    @Test
    fun `a page of HTML is not a winos computer either`() {
        reply = { respond(it, 200, "<!DOCTYPE html><html><body>Hello</body></html>") }

        try {
            BriefcaseClient(host).hello()
            fail("HTML should not have parsed")
        } catch (e: BriefcaseException) {
            assertEquals(BriefcaseProtocol.CODE_NOT_WINOS, e.code)
        }
    }

    @Test
    fun `nothing at the other end is a network failure, which is nobody's fault`() {
        val port = server.address.port
        server.stop(0)

        try {
            BriefcaseClient("http://127.0.0.1:$port").hello()
            fail("a closed port should have failed")
        } catch (e: BriefcaseException) {
            assertTrue(e.isNetworkFailure)
        } finally {
            startServer()
        }
    }

    // ---- What the person typed ---------------------------------------------------------------

    @Test
    fun `an address becomes an origin, whatever was pasted`() {
        assertEquals("https://gorjan.rocks", BriefcaseClient.normalizeHost("gorjan.rocks"))
        assertEquals("https://gorjan.rocks", BriefcaseClient.normalizeHost("  GORJAN.rocks/ "))
        assertEquals("https://gorjan.rocks", BriefcaseClient.normalizeHost("https://gorjan.rocks/briefcase.php/v1"))
        assertEquals("http://localhost:8080", BriefcaseClient.normalizeHost("http://localhost:8080/"))
        assertEquals("", BriefcaseClient.normalizeHost("   "))
    }

    @Test
    fun `only https leaves this phone, except while building the thing`() {
        assertTrue(BriefcaseClient.isSecure("https://gorjan.rocks"))
        assertTrue(BriefcaseClient.isSecure("http://localhost:8080"))
        assertTrue(BriefcaseClient.isSecure("http://127.0.0.1:8080"))
        assertFalse(BriefcaseClient.isSecure("http://gorjan.rocks"))
        assertFalse(BriefcaseClient.isSecure("http://192.168.1.4"))
    }

    @Test
    fun `a code may be typed in any case, with or without the dash`() {
        assertEquals("B2A9-YKG5", BriefcaseClient.normalizeCode("b2a9ykg5"))
        assertEquals("B2A9-YKG5", BriefcaseClient.normalizeCode("B2A9-YKG5"))
        assertEquals("B2A9-YKG5", BriefcaseClient.normalizeCode(" b2a9 ykg5 "))
    }

    // ---- The server -------------------------------------------------------------------------

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
