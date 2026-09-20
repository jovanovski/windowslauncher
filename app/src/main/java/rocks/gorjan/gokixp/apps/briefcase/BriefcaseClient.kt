package rocks.gorjan.gokixp.apps.briefcase

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Everything this app knows how to say to a winos computer.
 *
 * One instance is one address plus (usually) one token. Nothing here touches the disk mirror
 * or the user interface: it makes a request, reads the answer, and throws a
 * [BriefcaseException] carrying the server's own `code` when the answer is a refusal. Every
 * call blocks, so every call belongs on [Briefcase]'s background thread.
 */
class BriefcaseClient(host: String, private val token: String? = null) {

    /** The origin, normalised: scheme and authority, no path, no trailing slash. */
    val host: String = normalizeHost(host)

    private val base: String get() = host + BriefcaseProtocol.PATH

    // ---- Connecting ------------------------------------------------------------------------

    /** Section 1.1. No token needed - this is the call that asks whether it is one of these. */
    fun hello(): BriefcaseHello {
        val json = json(request("GET", "/hello", authed = false))
        return BriefcaseHello(
            service = json.string("service") ?: "",
            protocol = json.int("protocol") ?: 0,
            site = json.string("site") ?: "Windows",
            auth = json.getAsJsonArray("auth")?.mapNotNull { it.asString } ?: listOf("pair"),
            limits = limits(json)
        )
    }

    /** Section 1.2: the eight-character code the computer is showing. */
    fun pair(code: String, deviceName: String, platform: String): BriefcasePairing {
        val body = JsonObject().apply {
            addProperty("code", normalizeCode(code))
            add("device", device(deviceName, platform))
        }
        return pairing(json(request("POST", "/pair", body = body, authed = false)))
    }

    /** Section 1.3: the account's own name and password, when there is no computer to hand. */
    fun logon(name: String, password: String, deviceName: String, platform: String): BriefcasePairing {
        val body = JsonObject().apply {
            addProperty("name", name)
            addProperty("password", password)
            add("device", device(deviceName, platform))
        }
        return pairing(json(request("POST", "/logon", body = body, authed = false)))
    }

    /** Section 1.5. */
    fun session(): BriefcaseSession {
        val json = json(request("GET", "/session"))
        val user = json.getAsJsonObject("user")
        val dev = json.getAsJsonObject("device")
        return BriefcaseSession(
            user = user?.string("name") ?: "",
            quota = user?.long("quota") ?: 0L,
            usage = user?.long("usage") ?: 0L,
            deviceId = dev?.string("id") ?: "",
            deviceName = dev?.string("name") ?: "",
            rev = json.long("rev") ?: 0L,
            limits = limits(json)
        )
    }

    /** Section 1.6: this device forgets itself, and the computer's list stops showing it. */
    fun deleteSession() {
        request("DELETE", "/session").disconnect()
    }

    // ---- The files -------------------------------------------------------------------------

    /** Section 2.3: a few bytes, and the only thing worth asking for on a timer. */
    fun rev(): Long = json(request("GET", "/rev")).long("rev") ?: 0L

    /** Section 2.2. Pass the `rev` of the last successful sync to be handed only what changed. */
    fun files(since: Long? = null): BriefcaseListing {
        val path = if (since == null) "/files" else "/files?since=$since"
        val json = json(request("GET", path))
        return BriefcaseListing(
            files = json.getAsJsonArray("files")?.mapNotNull { file(it.asJsonObject) } ?: emptyList(),
            deleted = json.getAsJsonArray("deleted")?.mapNotNull { it.asString } ?: emptyList(),
            rev = json.long("rev") ?: 0L
        )
    }

    /** Section 2.5. Returns false when the server says `304` - what is on disk is current. */
    fun download(id: String, into: File, ifNoneMatch: String? = null): Boolean {
        val headers = mutableMapOf<String, String>()
        if (!ifNoneMatch.isNullOrEmpty()) headers["If-None-Match"] = "\"$ifNoneMatch\""

        val connection = request("GET", "/files/$id/content", headers = headers, readTimeout = 60000)
        try {
            if (connection.responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) return false
            into.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                FileOutputStream(into).use { output -> input.copyTo(output) }
            }
            return true
        } finally {
            connection.disconnect()
        }
    }

    /** Section 2.6, the long way: the bytes themselves. */
    fun upload(name: String, source: File): BriefcaseFile {
        val sha = sha256(source)
        val connection = request(
            "POST", "/files?name=${encode(name)}",
            headers = mapOf("Content-Type" to mimeType(name), "X-Winos-Sha256" to sha),
            readTimeout = 120000,
            contentLength = source.length()
        ) { output -> source.inputStream().use { it.copyTo(output) } }
        return uploaded(connection)
    }

    /**
     * Section 2.6, the short way: bytes the server already holds, named again.
     *
     * Throws `unknown_contents` when it turns out not to have them, which is the caller's
     * cue to send the file itself.
     */
    fun uploadKnown(name: String, sha256: String): BriefcaseFile {
        val body = JsonObject().apply {
            addProperty("name", name)
            addProperty("sha256", sha256)
        }
        return uploaded(request("POST", "/files", body = body))
    }

    /** Section 2.7. [ifMatch] is the hash last seen, so somebody else's change is not written over. */
    fun replace(id: String, source: File, ifMatch: String? = null): BriefcaseFile {
        val headers = mutableMapOf("Content-Type" to mimeType(source.name))
        if (!ifMatch.isNullOrEmpty()) headers["If-Match"] = "\"$ifMatch\""

        val connection = request(
            "PUT", "/files/$id/content",
            headers = headers,
            readTimeout = 120000,
            contentLength = source.length()
        ) { output -> source.inputStream().use { it.copyTo(output) } }
        return uploaded(connection)
    }

    /** Section 2.8. The server may hand back a different name than the one asked for. */
    fun rename(id: String, name: String): BriefcaseFile {
        val body = JsonObject().apply { addProperty("name", name) }
        return uploaded(request("POST", "/files/$id/name", body = body))
    }

    /** Section 2.9. It goes from every device, and not into anybody's Recycle Bin. */
    fun delete(id: String): Long = json(request("DELETE", "/files/$id")).long("rev") ?: 0L

    /** Section 2.6: which of these hashes the server already holds. */
    fun have(hashes: Collection<String>): Set<String> {
        if (hashes.isEmpty()) return emptySet()
        val body = JsonObject().apply {
            add("sha256", com.google.gson.JsonArray().apply { hashes.forEach { add(it) } })
        }
        val json = json(request("POST", "/have", body = body))
        return json.getAsJsonArray("have")?.mapNotNull { it.asString }?.toSet() ?: emptySet()
    }

    // ---- The wire --------------------------------------------------------------------------

    private fun request(
        method: String,
        path: String,
        body: JsonObject? = null,
        headers: Map<String, String> = emptyMap(),
        authed: Boolean = true,
        readTimeout: Int = 30000,
        contentLength: Long? = null,
        writeBody: ((java.io.OutputStream) -> Unit)? = null
    ): HttpURLConnection {
        val connection = try {
            (URL(base + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000
                this.readTimeout = readTimeout
                // A site that redirects is a site, not this API - following it would turn a
                // clear "that is not a winos computer" into a page of HTML that parses as
                // nothing. gorjan.rocks does exactly this when briefcase.php is not deployed.
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                if (authed && !token.isNullOrEmpty()) {
                    setRequestProperty("Authorization", "Bearer $token")
                    // Some Apache setups eat the Authorization header; section 1.4 allows this.
                    setRequestProperty("X-Winos-Token", token)
                }
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                } else if (writeBody != null) {
                    doOutput = true
                    // A length rather than chunks: the server reads the body as a plain
                    // request, and a hundred megabytes never sit in memory either way.
                    if (contentLength != null) {
                        setFixedLengthStreamingMode(contentLength)
                    } else {
                        setChunkedStreamingMode(64 * 1024)
                    }
                }
            }
        } catch (e: Exception) {
            throw BriefcaseException(BriefcaseProtocol.CODE_NETWORK, 0, unreachable(), cause = e)
        }

        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            } else if (writeBody != null) {
                connection.outputStream.use { writeBody(it) }
            }

            val status = connection.responseCode
            // 304 is not a redirect: it is section 2.5's "you already have this file".
            if (status in 300..399 && status != HttpURLConnection.HTTP_NOT_MODIFIED) {
                throw BriefcaseException(
                    BriefcaseProtocol.CODE_NOT_WINOS, status,
                    "${hostLabel()} sent us somewhere else. Check the address the computer is showing."
                )
            }
            if (status >= 400) throw failure(connection, status)
            return connection
        } catch (e: BriefcaseException) {
            connection.disconnect()
            throw e
        } catch (e: Exception) {
            connection.disconnect()
            throw BriefcaseException(BriefcaseProtocol.CODE_NETWORK, 0, unreachable(), cause = e)
        }
    }

    /** Turns a refusal into the sentence the server wrote and the word the program reads. */
    private fun failure(connection: HttpURLConnection, status: Int): BriefcaseException {
        val text = try {
            connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (e: Exception) {
            ""
        }
        val json = parse(text) as? JsonObject

        val code = json?.string("code") ?: when (status) {
            400 -> BriefcaseProtocol.CODE_BAD_REQUEST
            401, 403 -> BriefcaseProtocol.CODE_UNAUTHORIZED
            404 -> BriefcaseProtocol.CODE_NOT_FOUND
            409 -> BriefcaseProtocol.CODE_UNKNOWN_CONTENTS
            412 -> BriefcaseProtocol.CODE_CHANGED
            413 -> BriefcaseProtocol.CODE_TOO_LARGE
            429 -> BriefcaseProtocol.CODE_RATE_LIMITED
            503 -> BriefcaseProtocol.CODE_NOT_CONFIGURED
            507 -> BriefcaseProtocol.CODE_NO_SPACE
            else -> BriefcaseProtocol.CODE_SERVER_ERROR
        }
        val message = json?.string("error") ?: defaultMessage(code)
        Log.w(TAG, "$status $code: $message")
        return BriefcaseException(code, status, message, json?.int("retry") ?: 0)
    }

    private fun json(connection: HttpURLConnection): JsonObject {
        try {
            val text = try {
                connection.inputStream.bufferedReader().use { it.readText() }
            } catch (e: java.io.IOException) {
                // The connection died mid-answer: nothing reached us, which is not an error
                // worth a dialog.
                throw BriefcaseException(BriefcaseProtocol.CODE_NETWORK, 0, unreachable(), cause = e)
            }

            // Something answered but it is not JSON - a login page, a parked domain, a proxy.
            return (parse(text) as? JsonObject)
                ?: throw BriefcaseException(
                    BriefcaseProtocol.CODE_NOT_WINOS, connection.responseCode,
                    "That address answered, but not like a winos computer."
                )
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(text: String): com.google.gson.JsonElement? = try {
        JsonParser.parseString(text)
    } catch (e: Exception) {
        null
    }

    private fun pairing(json: JsonObject): BriefcasePairing {
        val device = json.getAsJsonObject("device")
        return BriefcasePairing(
            token = json.string("token") ?: "",
            deviceId = device?.string("id") ?: "",
            deviceName = device?.string("name") ?: "",
            user = json.getAsJsonObject("user")?.string("name") ?: "",
            site = json.string("site") ?: "Windows",
            rev = json.long("rev") ?: 0L,
            limits = limits(json)
        )
    }

    /** Every call that writes a file answers with `{"file": {...}, "rev": n}`. */
    private fun uploaded(connection: HttpURLConnection): BriefcaseFile {
        val json = json(connection)
        val file = json.getAsJsonObject("file")
            ?: throw BriefcaseException(
                BriefcaseProtocol.CODE_SERVER_ERROR, 0, "The computer did not say what it saved."
            )
        return file(file)
            ?: throw BriefcaseException(
                BriefcaseProtocol.CODE_SERVER_ERROR, 0, "The computer did not say what it saved."
            )
    }

    private fun file(json: JsonObject): BriefcaseFile? {
        val id = json.string("id") ?: return null
        return BriefcaseFile(
            id = id,
            name = json.string("name") ?: id,
            size = json.long("size") ?: 0L,
            sha256 = json.string("sha256") ?: "",
            modified = json.long("modified") ?: 0L
        )
    }

    private fun limits(json: JsonObject): BriefcaseLimits {
        val limits = json.getAsJsonObject("limits") ?: return BriefcaseLimits()
        val fallback = BriefcaseLimits()
        return BriefcaseLimits(
            fileBytes = limits.long("file_bytes") ?: fallback.fileBytes,
            nameChars = limits.int("name_chars") ?: fallback.nameChars,
            pollSeconds = limits.int("poll_seconds") ?: fallback.pollSeconds
        )
    }

    private fun device(name: String, platform: String) = JsonObject().apply {
        addProperty("name", name.take(64))
        addProperty("platform", platform)
    }

    private fun unreachable() = "Cannot find ${hostLabel()}. The computer may be switched off."

    private fun hostLabel() = host.removePrefix("https://").removePrefix("http://")

    // ---- Small helpers ---------------------------------------------------------------------

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString

    private fun JsonObject.long(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asLong

    private fun JsonObject.int(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asInt

    companion object {
        private const val TAG = "BriefcaseClient"

        /**
         * What the person typed, turned into an origin.
         *
         * They may paste the whole address the computer is showing, so the path goes; a bare
         * name gets `https://`, because section 6 says a token never travels in the open.
         */
        fun normalizeHost(input: String): String {
            var text = input.trim().replace(" ", "")
            if (text.isEmpty()) return ""
            if (!text.contains("://")) text = "https://$text"

            return try {
                val url = URL(text)
                val scheme = url.protocol.lowercase()
                val port = if (url.port == -1 || url.port == url.defaultPort) "" else ":${url.port}"
                "$scheme://${url.host.lowercase()}$port"
            } catch (e: Exception) {
                text.trimEnd('/')
            }
        }

        /**
         * Section 6: refuse to pair over plain HTTP, where the token and every file are in
         * the open. localhost is the exception, for building the thing.
         */
        fun isSecure(host: String): Boolean {
            if (host.startsWith("https://")) return true
            val authority = host.removePrefix("http://").substringBefore(':')
            return authority == "localhost" || authority == "127.0.0.1" || authority == "::1"
        }

        /**
         * A connection code, as typed. Any case, dash or no dash: `b2a9ykg5` is `B2A9-YKG5`.
         */
        fun normalizeCode(code: String): String {
            val bare = code.filter { it.isLetterOrDigit() }.uppercase()
            return if (bare.length == 8) "${bare.take(4)}-${bare.drop(4)}" else bare
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream -> digest.update(stream) }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun MessageDigest.update(stream: InputStream) {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                update(buffer, 0, read)
            }
        }

        fun encode(text: String): String =
            URLEncoder.encode(text, "UTF-8").replace("+", "%20")

        /** There is no MIME type on the wire; infer it from the extension, as the desktop does. */
        fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "txt", "log", "ini", "csv" -> "text/plain"
            "htm", "html" -> "text/html"
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "bmp", "dib" -> "image/bmp"
            "webp" -> "image/webp"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            "mp4", "m4v" -> "video/mp4"
            "avi" -> "video/x-msvideo"
            "mkv" -> "video/x-matroska"
            "zip" -> "application/zip"
            "doc" -> "application/msword"
            "xls" -> "application/vnd.ms-excel"
            else -> "application/octet-stream"
        }

        /** A sentence for a refusal that arrived without one. */
        fun defaultMessage(code: String): String = when (code) {
            BriefcaseProtocol.CODE_UNAUTHORIZED ->
                "The computer no longer knows this phone. Connect it again."
            BriefcaseProtocol.CODE_NOT_FOUND ->
                "The computer does not have that."
            BriefcaseProtocol.CODE_TOO_LARGE ->
                "That file is too big for the Briefcase."
            BriefcaseProtocol.CODE_NO_SPACE ->
                "There is not enough space on the disk to save this file."
            BriefcaseProtocol.CODE_NOT_CONFIGURED ->
                "That computer has no accounts yet, so it has no Briefcase to share."
            BriefcaseProtocol.CODE_RATE_LIMITED ->
                "Too many tries. Wait a moment and try again."
            BriefcaseProtocol.CODE_CHANGED ->
                "Somebody changed that file on the computer."
            BriefcaseProtocol.CODE_NOT_WINOS ->
                "That address answered, but not like a winos computer."
            else -> "The computer could not do that."
        }
    }
}
