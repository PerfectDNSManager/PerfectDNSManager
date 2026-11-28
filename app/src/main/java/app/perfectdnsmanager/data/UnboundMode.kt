package app.perfectdnsmanager.data

/** Exact local mode marker, never a hostname to resolve or a fallback destination. */
object UnboundMode {
    const val RECURSIVE_ENDPOINT = "unbound://recursive"
    const val RECURSIVE_PRESET_ID = 90000L
    fun isRecursive(endpoint: String) = endpoint == RECURSIVE_ENDPOINT
}
