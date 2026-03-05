package com.mobixy.proxy.tunnel

import kotlinx.coroutines.CompletableDeferred

internal data class TunnelHello(
    val t: String = "hello",
    val deviceId: String,
    val token: String,
    val cap: Map<String, Any?> = mapOf(
        "cellularBind" to true
    )
)

internal data class TunnelOpen(
    val t: String,
    val sid: Int,
    val host: String,
    val port: Int,
    val timeoutMs: Int = 10_000
)

internal data class TunnelOpenOk(
    val t: String = "open_ok",
    val sid: Int
)

internal data class TunnelOpenFail(
    val t: String = "open_fail",
    val sid: Int,
    val error: String
)

internal data class TunnelClose(
    val t: String = "close",
    val sid: Int,
    val reason: String
)

internal class PendingOpen {
    val result = CompletableDeferred<Boolean>()
    var error: String? = null
}
