package io.github.vad4nus.actioncable.internal.client

import io.github.vad4nus.actioncable.CableOptions
import io.github.vad4nus.actioncable.internal.CableErrors
import io.ktor.http.URLProtocol
import io.ktor.http.Url

private const val SCHEME_SEPARATOR = "://"
private const val AUTHORITY_END = "/?#"
private const val USER_INFO_END = '@'
private const val PORT_START = ':'
private val WEBSOCKET_SCHEMES = listOf(URLProtocol.WS, URLProtocol.WSS)

internal fun validateConfig(url: String, options: CableOptions) {
    if (!isWebSocketUrl(url)) throw CableErrors.invalidUrl()
    if (!options.staleTimeout.isPositive()) throw CableErrors.notPositive(CableOptions::staleTimeout.name)
    if (!options.minReconnectDelay.isPositive()) throw CableErrors.notPositive(CableOptions::minReconnectDelay.name)
    if (!options.maxReconnectDelay.isPositive()) throw CableErrors.notPositive(CableOptions::maxReconnectDelay.name)
    if (options.minReconnectDelay > options.maxReconnectDelay) {
        throw CableErrors.exceeds(CableOptions::minReconnectDelay.name, CableOptions::maxReconnectDelay.name)
    }
    if (options.maxReconnectAttempts < 0) throw CableErrors.negative(CableOptions::maxReconnectAttempts.name)
    if (options.messageBufferSize < 1) throw CableErrors.belowOne(CableOptions::messageBufferSize.name)
}

private fun isWebSocketUrl(url: String): Boolean {
    val scheme = WEBSOCKET_SCHEMES.any { url.startsWith(it.name + SCHEME_SEPARATOR, ignoreCase = true) }
    val host = url.substringAfter(SCHEME_SEPARATOR)
        .takeWhile { it !in AUTHORITY_END }
        .substringAfterLast(USER_INFO_END)
        .substringBefore(PORT_START)
    return scheme && host.isNotEmpty() && runCatching { Url(url) }.isSuccess
}
