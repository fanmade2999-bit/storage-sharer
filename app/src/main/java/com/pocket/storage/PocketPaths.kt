package com.pocket.storage

import android.content.Context
import java.io.File

internal object PocketPaths {
    fun root(context: Context): File {
        val root = File(context.filesDir, "pocket")
        if (!root.exists() && !root.mkdirs()) {
            throw IllegalStateException("Unable to initialize Pocket storage")
        }
        return root
    }

    fun resolve(root: File, requested: String): File {
        val normalized = requested.trim()
            .replace('\\', '/')
            .removePrefix("/")

        require(normalized.isNotEmpty()) { "path is required" }
        require(!normalized.split('/').any { it == ".." }) { "path traversal is not allowed" }

        val candidate = File(root, normalized).canonicalFile
        val canonicalRoot = root.canonicalFile

        require(
            candidate.path == canonicalRoot.path ||
                candidate.path.startsWith(canonicalRoot.path + File.separator)
        ) { "path escapes Pocket storage" }

        return candidate
    }

    fun cleanRelative(requested: String): String =
        requested.trim()
            .replace('\\', '/')
            .removePrefix("/")
}
