package com.pocket.storage

import android.content.ContentProvider
import android.companion.CompanionDeviceManager
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class PocketProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.pocket.storage.provider"
        const val BASE_URI = "content://$AUTHORITY"

        private const val METHOD_CONNECT = "connect"
        private const val METHOD_DISCONNECT = "disconnect"
        private const val METHOD_CHANGE_PASSWORD = "change_password"
        private const val METHOD_LOCK = "lock"
        private const val METHOD_PING = "ping"
        private const val METHOD_WHOAMI = "whoami"
        private const val METHOD_LS = "ls"
        private const val METHOD_STAT = "stat"
        private const val METHOD_FELLOW_LIST = "fellow_list"
        private const val METHOD_FELLOW_REMOVE = "fellow_remove"

        private const val PATH_FILES = "files"
        private const val PATH_FILE = "file"

        private val FILE_COLUMNS = arrayOf(
            "path",
            "name",
            "is_directory",
            "size",
            "modified"
        )
    }

    private lateinit var root: File
    private lateinit var auth: AuthManager
    private lateinit var crypto: PocketFileCrypto
    private val ioExecutor = Executors.newCachedThreadPool()
    private lateinit var fellows: FellowSharerRegistry

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        root = PocketPaths.root(ctx)
        auth = AuthManager(ctx)
        crypto = PocketFileCrypto(PocketDataKey.get(ctx))
        fellows = FellowSharerRegistry(ctx)
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val input = extras ?: Bundle.EMPTY

        return when (method) {
            METHOD_CONNECT -> auth.connect(input.getString("password").orEmpty())

            METHOD_DISCONNECT -> {
                val session = input.getString("session").orEmpty()
                auth.requireSession(session)
                auth.disconnect(session)
                Bundle().apply { putBoolean("ok", true) }
            }

            METHOD_CHANGE_PASSWORD -> {
                val session = input.getString("session").orEmpty()
                val newPassword = input.getString("new_password").orEmpty()
                auth.changePassword(session, newPassword)
                Bundle().apply {
                    putBoolean("ok", true)
                    putBoolean("must_change_password", false)
                }
            }

            METHOD_LOCK -> {
                val session = input.getString("session").orEmpty()
                auth.lock(session)
                Bundle().apply { putBoolean("ok", true) }
            }

            METHOD_PING -> Bundle().apply {
                putBoolean("ok", true)
                putString("service", "Pocket Storage")
                putString("version", "0.1.0")
            }

            METHOD_WHOAMI -> {
                val session = input.getString("session").orEmpty()
                auth.requireSession(session)
                Bundle().apply {
                    putString("service", "Pocket Storage")
                    putString("version", "0.1.0")
                    putString("device_public_key", PocketIdentity.publicKeyBase64(requireContext()))
                    putBoolean("must_change_password", auth.mustChangePassword)
                }
            }

            METHOD_LS -> {
                val session = input.getString("session").orEmpty()
                auth.requireSession(session)
                require(!auth.mustChangePassword) { "password change required" }
                listBundle(input.getString("path").orEmpty())
            }

            METHOD_STAT -> {
                val session = input.getString("session").orEmpty()
                auth.requireSession(session)
                require(!auth.mustChangePassword) { "password change required" }
                statBundle(input.getString("path").orEmpty())
            }

            METHOD_FELLOW_LIST -> {
                val session = input.getString("session").orEmpty()
                auth.requireSession(session)
                fellowListBundle()
            }

            METHOD_FELLOW_REMOVE -> {
                val session = input.getString("session").orEmpty()
                auth.requireSession(session)
                val id = input.getInt("association_id", -1)
                require(id >= 0) { "association_id is required" }
                removeFellow(id)
            }

            else -> super.call(method, arg, extras) ?: Bundle()
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor {
        val session = uri.getQueryParameter("session")
        auth.requireSession(session)
        require(!auth.mustChangePassword) { "password change required" }

        val relativePath = uri.getQueryParameter("path").orEmpty()
        val target = if (relativePath.isBlank()) root else PocketPaths.resolve(root, relativePath)

        if (!target.exists()) {
            throw FileNotFoundException("path does not exist")
        }

        val cursor = MatrixCursor(FILE_COLUMNS)

        if (target.isDirectory) {
            target.listFiles()
                ?.filterNot { it.name.startsWith(".pocket-") && it.name.endsWith(".tmp") }
                ?.sortedBy { it.name.lowercase() }
                ?.forEach { cursor.addRow(fileRow(it)) }
        } else {
            cursor.addRow(fileRow(target))
        }

        return cursor
    }

    override fun getType(uri: Uri): String? =
        when (uri.pathSegments.firstOrNull()) {
            PATH_FILES -> "vnd.android.cursor.dir/vnd.pocket.file"
            PATH_FILE -> "application/octet-stream"
            else -> null
        }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val session = uri.getQueryParameter("session")
        auth.requireSession(session)
        require(!auth.mustChangePassword) { "password change required" }

        val relativePath = decodePathAfter(uri, PATH_FILE)
        val target = PocketPaths.resolve(root, relativePath)

        if (mode.contains('w')) {
            target.parentFile?.mkdirs()
            val append = uri.getQueryParameter("append") == "true"
            val pipes = ParcelFileDescriptor.createPipe()

            ioExecutor.execute {
                ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { input ->
                    runCatching {
                        if (append) {
                            crypto.appendAtomic(target, input)
                        } else {
                            crypto.writeAtomic(target, input)
                        }
                    }
                }
            }

            return pipes[1]
        }

        if (!target.exists() || target.isDirectory) {
            throw FileNotFoundException("file does not exist")
        }

        val pipes = ParcelFileDescriptor.createPipe()
        ioExecutor.execute {
            ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { output ->
                runCatching {
                    crypto.readTo(target, output)
                }
            }
        }

        return pipes[0]
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val input = values ?: error("values are required")
        val session = input.getAsString("session").orEmpty()
        auth.requireSession(session)
        require(!auth.mustChangePassword) { "password change required" }

        val relativePath = input.getAsString("path").orEmpty()
        val target = PocketPaths.resolve(root, relativePath)
        val kind = input.getAsString("kind") ?: "file"

        when (kind) {
            "directory" -> require(target.mkdirs() || target.isDirectory) {
                "unable to create directory"
            }
            "file" -> {
                target.parentFile?.mkdirs()
                if (!target.exists()) {
                    crypto.createEmpty(target)
                }
            }
            else -> error("unsupported kind")
        }

        return Uri.parse(
            "$BASE_URI/$PATH_FILE/" + Uri.encode(PocketPaths.cleanRelative(relativePath))
        )
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        val session = uri.getQueryParameter("session")
        auth.requireSession(session)
        require(!auth.mustChangePassword) { "password change required" }

        val relativePath = uri.getQueryParameter("path").orEmpty()
        val target = PocketPaths.resolve(root, relativePath)

        if (!target.exists()) return 0
        require(target.canonicalFile != root.canonicalFile) { "cannot delete Pocket root" }
        target.deleteRecursively()
        return 1
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int {
        val input = values ?: error("values are required")
        val session = input.getAsString("session").orEmpty()
        auth.requireSession(session)
        require(!auth.mustChangePassword) { "password change required" }

        val sourcePath = input.getAsString("path").orEmpty()
        val destinationPath = input.getAsString("rename_to").orEmpty()

        require(sourcePath.isNotBlank() && destinationPath.isNotBlank()) {
            "path and rename_to are required"
        }

        val source = PocketPaths.resolve(root, sourcePath)
        val destination = PocketPaths.resolve(root, destinationPath)

        require(source.exists()) { "source does not exist" }
        require(source.canonicalFile != root.canonicalFile) { "cannot rename Pocket root" }
        require(destination.canonicalFile != root.canonicalFile) { "cannot replace Pocket root" }
        require(!destination.exists()) { "destination already exists" }
        destination.parentFile?.mkdirs()
        require(source.renameTo(destination)) { "rename failed" }
        return 1
    }

    private fun fellowListBundle(): Bundle {
        val json = org.json.JSONArray()
        fellows.list().forEach { fellow ->
            json.put(
                org.json.JSONObject()
                    .put("association_id", fellow.associationId)
                    .put("label", fellow.label)
                    .put("mac_address", fellow.macAddress)
                    .put("registered_at", fellow.registeredAt)
                    .put("nearby", fellow.nearby)
                    .put("last_seen_at", fellow.lastSeenAt)
            )
        }
        return Bundle().apply {
            putString("fellows_json", json.toString())
            putInt("count", fellows.list().size)
        }
    }

    private fun removeFellow(associationId: Int): Bundle {
        val fellow = fellows.get(associationId)
            ?: error("fellow sharer not found")

        val manager = requireContext().getSystemService(CompanionDeviceManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 36) {
            val request =
                android.companion.ObservingDevicePresenceRequest.Builder()
                    .setAssociationId(associationId)
                    .build()
            runCatching { manager.stopObservingDevicePresence(request) }
        } else {
            fellow.macAddress?.let {
                @Suppress("DEPRECATION")
                runCatching { manager.stopObservingDevicePresence(it) }
            }
        }

        runCatching { manager.disassociate(associationId) }
        fellows.remove(associationId)

        return Bundle().apply {
            putBoolean("ok", true)
            putInt("association_id", associationId)
        }
    }

    private fun listBundle(relativePath: String): Bundle {
        val target = if (relativePath.isBlank()) root else PocketPaths.resolve(root, relativePath)
        require(target.isDirectory) { "not a directory" }
        val items = target.listFiles()
            ?.filterNot { it.name.startsWith(".pocket-") && it.name.endsWith(".tmp") }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
        return Bundle().apply {
            putString("path", if (relativePath.isBlank()) "/" else "/" + PocketPaths.cleanRelative(relativePath))
            putParcelableArrayList("items", ArrayList(items.map { fileBundle(it) }))
        }
    }

    private fun statBundle(relativePath: String): Bundle {
        val target = PocketPaths.resolve(root, relativePath)
        require(target.exists()) { "path does not exist" }
        return fileBundle(target)
    }

    private fun fileBundle(file: File): Bundle = Bundle().apply {
        putString("path", "/" + file.relativeTo(root).path.replace(File.separatorChar, '/'))
        putString("name", file.name)
        putBoolean("is_directory", file.isDirectory)
        putLong(
            "size",
            if (file.isFile) {
                runCatching { crypto.plaintextSize(file) }.getOrElse { file.length() }
            } else {
                0L
            }
        )
        putLong("modified", file.lastModified())
    }

    private fun fileRow(file: File): Array<Any> = arrayOf(
        file.relativeTo(root).path.replace(File.separatorChar, '/'),
        file.name,
        file.isDirectory,
        if (file.isFile) {
            runCatching { crypto.plaintextSize(file) }.getOrElse { file.length() }
        } else {
            0L
        },
        file.lastModified()
    )

    private fun decodePathAfter(uri: Uri, segment: String): String {
        val encoded = uri.encodedPath ?: throw FileNotFoundException("invalid path")
        val marker = "/$segment/"
        val index = encoded.indexOf(marker)
        require(index >= 0) { "invalid file URI" }

        return URLDecoder.decode(
            encoded.substring(index + marker.length),
            StandardCharsets.UTF_8.name()
        )
    }
}
