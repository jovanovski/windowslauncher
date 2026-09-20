package rocks.gorjan.gokixp.apps.briefcase

/**
 * The wire, as BRIEFCASE-PROTOCOL.md describes it: one folder on a winos desktop shared with
 * this phone, reached over `/briefcase.php/v1`.
 *
 * Only the shapes live here. [BriefcaseClient] speaks them, [Briefcase] decides what to do
 * about them.
 */
object BriefcaseProtocol {

    /** The only protocol version this client knows how to speak. */
    const val VERSION = 1

    /** What a winos desktop answers `/hello` with; anything else is not one of these. */
    const val SERVICE = "winos-briefcase"

    const val PATH = "/briefcase.php/v1"

    // Codes from section 4. Every failure carries one, and the code - not the sentence - is
    // what the program is allowed to branch on.
    const val CODE_BAD_REQUEST = "bad_request"
    const val CODE_UNAUTHORIZED = "unauthorized"
    const val CODE_NOT_FOUND = "not_found"
    const val CODE_UNKNOWN_CONTENTS = "unknown_contents"
    const val CODE_CHANGED = "changed"
    const val CODE_TOO_LARGE = "too_large"
    const val CODE_RATE_LIMITED = "rate_limited"
    const val CODE_NOT_CONFIGURED = "not_configured"
    const val CODE_NO_SPACE = "no_space"
    const val CODE_SERVER_ERROR = "server_error"

    /** Ours, not the server's: the request never reached anybody. */
    const val CODE_NETWORK = "network"

    /** Ours: the address answered, but not like a winos computer. */
    const val CODE_NOT_WINOS = "not_winos"
}

/**
 * What the server allows. Sent with every answer that starts a session, and worth keeping:
 * an upload that is too big can be refused before a hundred megabytes go up the wire.
 */
data class BriefcaseLimits(
    val fileBytes: Long = 104857600L,
    val nameChars: Int = 200,
    val pollSeconds: Int = 15
)

/** One file in the Briefcase. `sha256` doubles as the version of the contents. */
data class BriefcaseFile(
    val id: String,
    val name: String,
    val size: Long,
    val sha256: String,
    val modified: Long
)

/** The answer to `/hello`: what this is, and how it will let you in. */
data class BriefcaseHello(
    val service: String,
    val protocol: Int,
    val site: String,
    val auth: List<String>,
    val limits: BriefcaseLimits
) {
    val isWinos: Boolean get() = service == BriefcaseProtocol.SERVICE
}

/** The answer to `/pair` and `/logon`: a token, and who it belongs to. */
data class BriefcasePairing(
    val token: String,
    val deviceId: String,
    val deviceName: String,
    val user: String,
    val site: String,
    val rev: Long,
    val limits: BriefcaseLimits
)

/** The answer to `/session`: the account, the device, and how full the disk is. */
data class BriefcaseSession(
    val user: String,
    val quota: Long,
    val usage: Long,
    val deviceId: String,
    val deviceName: String,
    val rev: Long,
    val limits: BriefcaseLimits
) {
    /** Room left in bytes, or null when the account has no limit. */
    val free: Long? get() = if (quota <= 0L) null else (quota - usage).coerceAtLeast(0L)
}

/** The answer to `/files`: what is there, or what has changed since a revision. */
data class BriefcaseListing(
    val files: List<BriefcaseFile>,
    val deleted: List<String>,
    val rev: Long
)

/**
 * A failure with a sentence for the person and a word for the program.
 *
 * [code] is one of BriefcaseProtocol's codes; [status] is the HTTP status, or 0 when the
 * request never got that far. [retryAfter] carries the `retry` seconds of a 429.
 */
class BriefcaseException(
    val code: String,
    val status: Int,
    message: String,
    val retryAfter: Int = 0,
    cause: Throwable? = null
) : Exception(message, cause) {

    /**
     * A revoked or expired token. Not worth retrying: throw it away and ask the person to
     * connect again.
     */
    val isAuthFailure: Boolean get() = code == BriefcaseProtocol.CODE_UNAUTHORIZED

    /**
     * Nothing reached the server. The Briefcase is a folder, not a transaction - keep what
     * you have and try again later, without a dialog.
     */
    val isNetworkFailure: Boolean get() = code == BriefcaseProtocol.CODE_NETWORK
}
