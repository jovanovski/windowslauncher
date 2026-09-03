package rocks.gorjan.gokixp.apps.iexplore

import android.app.DownloadManager
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.URLUtil

/**
 * The two things a WebView download has to be told, and that both browsers in here have
 * to tell it the same way.
 *
 * A WebView hands a file off to the system download manager, which is a different process
 * with its own connection, its own user agent and none of the cookies the page was using.
 * Everything about the request is therefore weaker than the one the page made, and the
 * naming is worse - so both are fixed in one place rather than twice, badly.
 */

/**
 * Sends the request out wearing what the page was wearing.
 *
 * Plenty of files exist only for the session that asked for them: a share link, anything
 * behind a sign-in, anything behind a bot check. To a second, bare request from another
 * process the site sends its refusal page instead of the file, and the download fails on a
 * link that had just worked in the browser it was clicked in. WeTransfer is the plain case
 * - its download address is the page's own address, sitting behind a bot check that only
 * answers a request carrying the browser's cookies.
 */
internal fun DownloadManager.Request.asThePageAsked(
    url: String, userAgent: String?, referer: String?
): DownloadManager.Request {
    CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let {
        addRequestHeader("Cookie", it)
    }
    userAgent?.takeIf { it.isNotBlank() }?.let { addRequestHeader("User-Agent", it) }
    referer?.takeIf { URLUtil.isNetworkUrl(it) }?.let { addRequestHeader("Referer", it) }
    return this
}

/**
 * What to call the file.
 *
 * [URLUtil.guessFileName] reads `Content-Disposition` with a pattern older than
 * `filename*=`, the encoded form a server has to use for a name that is not plain ASCII
 * and that most of the web now sends alongside the plain one. When it cannot read the
 * header it falls back to the last part of the address - which, on a download a page
 * started rather than a link to a file, is the page. That is where `local-bartender.bin`
 * comes from on a WeTransfer link: the header went unread, the address ends
 * `/download/local-bartender`, and `.bin` is what is left when nothing said what the file
 * was. So the header is read here first, both forms of it, and the guess is the fallback.
 */
internal fun downloadFileName(
    url: String, contentDisposition: String?, mimetype: String?
): String {
    val named = contentDisposition?.let { fileNameIn(it) }
        ?: URLUtil.guessFileName(url, contentDisposition, mimetype)
    // A name is one entry in a folder. A separator or a walk up the tree is part of what
    // the server called the file, not somewhere this is allowed to write.
    val flat = named.replace('\\', '/').substringAfterLast('/')
        .filterNot { it < ' ' || it == ':' }
        .trim()
    return flat.takeIf { it.isNotBlank() && it != "." && it != ".." }
        ?: URLUtil.guessFileName(url, null, mimetype)
}

/** The file name a `Content-Disposition` is carrying, in either form it comes in. */
private fun fileNameIn(header: String): String? {
    // filename*=UTF-8''holiday%20photos.zip - percent-encoded, and preferred when both
    // are present, because the plain one beside it is the lossy copy.
    ENCODED_FILE_NAME.find(header)?.groupValues?.get(1)?.let { encoded ->
        Uri.decode(encoded.trim().trim('"'))?.takeIf { it.isNotBlank() }?.let { return it }
    }
    // filename="holiday photos.zip", or bare up to the next parameter.
    PLAIN_FILE_NAME.find(header)?.groupValues?.let { found ->
        val name = found[1].ifEmpty { found[2] }.trim()
        if (name.isNotBlank()) return name
    }
    return null
}

/**
 * The two shapes a `Content-Disposition` file name comes in.
 *
 * Neither can swallow the other: a name is only a name where an `=` follows it, and
 * `filename*=` has a `*` in the way. The plain one runs to the next parameter or to the
 * end of the header, quoted or bare.
 */
private val ENCODED_FILE_NAME =
    Regex("""filename\*\s*=\s*[^']*'[^']*'([^;]+)""", RegexOption.IGNORE_CASE)
private val PLAIN_FILE_NAME = Regex(
    """(?:^|;|\s)filename\s*=\s*(?:"([^"]*)"|([^;]*))""", RegexOption.IGNORE_CASE)
