package com.pocket.storage

import android.content.Context
import android.os.Binder

internal object PocketCallerGuard {
    private const val TERMUX_PACKAGE = "com.termux"

    fun requireAllowedCaller(context: Context) {
        val callingUid = Binder.getCallingUid()
        val ownUid = context.applicationInfo.uid
        if (callingUid == ownUid) return

        val packages = context.packageManager.getPackagesForUid(callingUid).orEmpty()
        require(packages.contains(TERMUX_PACKAGE)) {
            "Pocket provider access denied for caller"
        }
    }
}
