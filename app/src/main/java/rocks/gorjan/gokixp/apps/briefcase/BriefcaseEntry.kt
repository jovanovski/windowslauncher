package rocks.gorjan.gokixp.apps.briefcase

import java.io.File

/**
 * How one file in the Briefcase stands between the two ends.
 *
 * The same four words the desktop's own Briefcase uses, for the same reasons - a person
 * should be able to look at the folder and know what is where.
 */
enum class BriefcaseFileState {
    /** The same bytes are on the computer and on the phone. */
    UP_TO_DATE,

    /** On the computer; not fetched yet. Tapping it fetches it. */
    NOT_DOWNLOADED,

    /** Changed here since it last matched, and waiting to go up. */
    NEEDS_SENDING,

    /** Going up, or coming down, right now. */
    BUSY,

    /** Only on the phone: the computer has never seen it. */
    ORPHAN
}

/**
 * One row of the local mirror, as section 5.1 asks for: the file's identity on the computer,
 * and where its copy sits here.
 *
 * The copy sits at `<folder>/<name>` - the Briefcase is flat and the server keeps names
 * unique, so the name is address enough. [localSha] is the hash of that copy as last
 * measured, with [localLength] and [localModified] the stat that says the measurement is
 * still good; re-hashing a hundred megabytes on every listing would not be.
 */
data class BriefcaseEntry(
    /** The computer's id for this file. Null means it has never been up there. */
    val id: String?,
    val name: String,
    /** The size on the computer, or of the local copy for a file that has never gone up. */
    val size: Long,
    /** The hash the computer holds - the version of the contents, per section 2.1. */
    val serverSha: String,
    /**
     * The version both ends last agreed on: the hash at the moment this copy was fetched or
     * sent.
     *
     * Without it there is no telling "the computer changed this" from "I changed this" -
     * both are only "the two hashes differ" - and a round would either write the phone's
     * copy over a newer one on the computer or never fetch the newer one at all. With it,
     * the three cases are plain: the local copy differs from the base and the computer's
     * does not (send it), the computer's differs and the local one does not (fetch it), or
     * both differ, which is a conflict and keeps both copies.
     */
    val baseSha: String? = null,
    /** When the computer last wrote it, in Unix seconds. */
    val modified: Long,
    val localSha: String? = null,
    val localLength: Long = 0L,
    val localModified: Long = 0L,
    /** Waiting to go up on the next round. */
    val pending: Boolean = false
) {

    /** True when there is a copy on the phone. */
    val isDownloaded: Boolean get() = localSha != null

    /** The version this copy came from; an older row that predates [baseSha] assumes it matched. */
    val base: String get() = baseSha ?: serverSha

    /** True when the copy here has been changed since the two ends last agreed. */
    val isModifiedHere: Boolean get() = localSha != null && localSha != base

    /** True when the computer's copy has moved on since the two ends last agreed. */
    val isChangedThere: Boolean get() = serverSha.isNotEmpty() && serverSha != base

    fun state(busy: Boolean): BriefcaseFileState = when {
        busy -> BriefcaseFileState.BUSY
        id == null -> BriefcaseFileState.ORPHAN
        localSha == null -> BriefcaseFileState.NOT_DOWNLOADED
        isModifiedHere -> BriefcaseFileState.NEEDS_SENDING
        else -> BriefcaseFileState.UP_TO_DATE
    }

    /** The phone's copy, whether or not it is there yet. */
    fun file(folder: File): File = File(folder, name)

    /** The row as it stands after a disconnection: still here, belonging to nobody. */
    fun orphaned(): BriefcaseEntry = copy(id = null, serverSha = "", baseSha = null, pending = false)

    /** The row after the copy on the phone has been measured. */
    fun measured(file: File, sha: String): BriefcaseEntry = copy(
        localSha = sha,
        localLength = file.length(),
        localModified = file.lastModified()
    )

    /** The row after the copy on the phone has gone. */
    fun notDownloaded(): BriefcaseEntry =
        copy(localSha = null, localLength = 0L, localModified = 0L, baseSha = null, pending = false)

    /** The row after both ends have just agreed: what is here is what is there. */
    fun agreed(file: File, sha: String): BriefcaseEntry =
        measured(file, sha).copy(baseSha = sha, pending = false)

    /** Whether the cached hash still describes what is on disk. */
    fun isMeasurementStale(file: File): Boolean =
        localSha == null || localLength != file.length() || localModified != file.lastModified()
}
