package com.shilapi.xcertplay.adb

import android.content.Context

/**
 * The Android user DiPlay runs as. BYD head units start apps as a secondary user (10 on this
 * firmware), while `adb shell settings` and `appops` act on the system user unless `--user` is
 * given: a grant written without it lands on user 0, and the app, running as user 10, never sees
 * it. The flag is added only for a secondary user, so single-user cars keep their exact commands.
 */
object AdbUser {
    private const val PER_USER_RANGE = 100_000

    fun id(context: Context): Int = context.applicationInfo.uid / PER_USER_RANGE

    /** "settings" or "settings --user N", ready for get/put. */
    fun settings(context: Context): String = "settings" + flag(context)

    /** "appops set" or "appops set --user N", ready for the package and op. */
    fun appopsSet(context: Context): String = "appops set" + flag(context)

    /** "appops get" or "appops get --user N". */
    fun appopsGet(context: Context): String = "appops get" + flag(context)

    private fun flag(context: Context): String = id(context).let { if (it == 0) "" else " --user $it" }
}
