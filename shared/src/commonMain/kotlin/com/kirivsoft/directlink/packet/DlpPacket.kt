package com.kirivsoft.directlink.packet

data class DlpPacket(
    val version: String = CURRENT_VERSION,
    val packetId: String,
    val deviceName: String,
    val deviceUuid: String,
    val platform: String,
    val appVersion: String,
    val fingerprint: String,
    val issuedAt: Long,
    val expiresAt: Long
) {
    fun isExpired(nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean = nowSeconds >= expiresAt

    companion object {
        const val CURRENT_VERSION = "0.1-mvp"
    }
}

sealed class DlpParseResult {
    data class Success(val packet: DlpPacket) : DlpParseResult()
    data class InvalidPassword(val reason: String) : DlpParseResult()
    data class Expired(val packet: DlpPacket) : DlpParseResult()
    data class Malformed(val reason: String) : DlpParseResult()
    data class UnsupportedVersion(val found: String) : DlpParseResult()
}
