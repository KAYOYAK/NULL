package top.nullcmd.app.data.webhistory

import top.nullcmd.app.data.FileCipher
import top.nullcmd.app.data.SecureDelete
import java.io.File

/**
 * The page's conversation history, encrypted at rest.
 *
 * chat-demo keeps its sessions in IndexedDB, which is a plain store: anything that
 * can read the WebView's data directory can read every conversation. This app
 * already had a cipher and a secure delete for its own history, and it is the one
 * thing a server-backed client does not have to solve -- so the page's sessions are
 * written here instead, as opaque JSON.
 *
 * Opaque on purpose: this layer does not know what a session is. The document is
 * the page's, unchanged, which keeps one schema in one place and means a future
 * change to it does not need a companion change here.
 */
class EncryptedHistory(private val directory: File) {

    /** Session ids that have a file, newest first is the caller's business. */
    fun ids(): List<String> =
        directory.listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) }
            ?.mapNotNull { it.name.removeSuffix(SUFFIX).takeIf(::isSafeId) }
            ?.sorted()
            ?: emptyList()

    /** Bytes these documents occupy, for the About screen's storage figures. */
    fun totalBytes(): Long =
        directory.listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) }
            ?.sumOf { it.length() }
            ?: 0L

    /** The stored document, or null when there is none or it cannot be read. */
    fun read(id: String): String? {
        if (!isSafeId(id)) {
            return null
        }
        val file = fileFor(id)
        if (!file.exists()) {
            return null
        }
        return FileCipher.read(file)?.toString(Charsets.UTF_8)
    }

    /**
     * Writes the document.
     *
     * Through a temporary file and a rename, like the native store: a process that
     * dies mid-write leaves the previous version in place rather than half of the
     * new one.
     */
    fun write(id: String, json: String): Boolean {
        if (!isSafeId(id)) {
            return false
        }
        return try {
            directory.mkdirs()
            val target = fileFor(id)
            val temporary = File(directory, "$id$SUFFIX.tmp")
            // writeBytes, then fsync through an append handle: opening an
            // output stream here without append would truncate what was just
            // written, which is how every migrated session ended up as a 0-byte
            // file the first time this ran.
            temporary.writeBytes(FileCipher.encrypt(json.toByteArray(Charsets.UTF_8)))
            java.io.FileOutputStream(temporary, true).use { it.fd.sync() }
            if (target.exists()) {
                target.delete()
            }
            temporary.renameTo(target)
        } catch (error: Throwable) {
            android.util.Log.w("NullWeb", error.javaClass.name, error)
            false
        }
    }

    /** Removes the document the way the native store does, then reports success. */
    suspend fun delete(id: String): Boolean {
        if (!isSafeId(id)) {
            return false
        }
        val file = fileFor(id)
        if (!file.exists()) {
            return true
        }
        return SecureDelete.delete(file)
    }

    private fun fileFor(id: String) = File(directory, "$id$SUFFIX")

    /**
     * Ids come from the page and end up in a path, so only a conservative alphabet
     * is accepted: a document id has no business containing a separator or a dot.
     */
    private fun isSafeId(id: String): Boolean =
        id.isNotEmpty() && id.length <= 64 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private companion object {
        const val SUFFIX = ".session"
    }
}
