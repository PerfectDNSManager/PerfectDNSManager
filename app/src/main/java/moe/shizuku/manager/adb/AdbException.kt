// Vendored from RikkaApps/Shizuku v13.6.0 (Apache 2.0).
// Source: https://github.com/RikkaApps/Shizuku/tree/master/manager/src/main/java/moe/shizuku/manager/adb
// See NOTICE in repo root for full attribution.

package moe.shizuku.manager.adb

@Suppress("NOTHING_TO_INLINE")
inline fun adbError(message: Any): Nothing = throw AdbException(message.toString())

open class AdbException : Exception {

    constructor(message: String, cause: Throwable?) : super(message, cause)
    constructor(message: String) : super(message)
    constructor(cause: Throwable) : super(cause)
    constructor()
}

class AdbInvalidPairingCodeException : AdbException()

class AdbKeyException(cause: Throwable) : AdbException(cause)
