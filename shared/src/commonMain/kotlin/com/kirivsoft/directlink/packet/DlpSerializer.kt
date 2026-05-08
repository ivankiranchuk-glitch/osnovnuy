package com.kirivsoft.directlink.packet

import com.kirivsoft.directlink.crypto.DecryptResult
import com.kirivsoft.directlink.crypto.DlpCrypto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import java.util.UUID

class DlpSerializer(
    private val crypto: DlpCrypto = DlpCrypto()
) {
    fun buildPacket(
        deviceName: String,
        deviceUuid: String,
        platform: String,
        appVersion: String,
        fingerprint: String,
        password: String,
        ttlSeconds: Long,
        nowSeconds: Long = System.currentTimeMillis() / 1000
    ): DlpPacket = DlpPacket(
        packetId = UUID.randomUUID().toString(),
        deviceName = deviceName,
        deviceUuid = deviceUuid,
        platform = platform,
        appVersion = appVersion,
        fingerprint = fingerprint,
        issuedAt = nowSeconds,
        expiresAt = nowSeconds + ttlSeconds
    )

    fun write(packet: DlpPacket, password: String, file: File) {
        val plaintext = encodeProperties(packet)
        file.writeBytes(crypto.encrypt(password, plaintext))
    }

    fun parse(file: File, password: String, nowSeconds: Long = System.currentTimeMillis() / 1000): DlpParseResult {
        val container = runCatching { file.readBytes() }
            .getOrElse { return DlpParseResult.Malformed("Cannot read packet: ${it.message}") }

        val plaintext = when (val result = crypto.decrypt(password, container)) {
            is DecryptResult.Success -> result.plaintext
            DecryptResult.InvalidPassword -> return DlpParseResult.InvalidPassword("Password does not match packet")
            is DecryptResult.Malformed -> return DlpParseResult.Malformed(result.reason)
        }

        val props = runCatching {
            Properties().apply {
                ByteArrayInputStream(plaintext).use { input -> load(input) }
            }
        }.getOrElse { return DlpParseResult.Malformed("Cannot decode packet: ${it.message}") }

        val packet = runCatching {
            DlpPacket(
                version = props.require("version"),
                packetId = props.require("packetId"),
                deviceName = props.require("deviceName"),
                deviceUuid = props.require("deviceUuid"),
                platform = props.require("platform"),
                appVersion = props.require("appVersion"),
                fingerprint = props.require("fingerprint"),
                issuedAt = props.require("issuedAt").toLong(),
                expiresAt = props.require("expiresAt").toLong()
            )
        }.getOrElse { return DlpParseResult.Malformed("Invalid packet fields: ${it.message}") }

        if (packet.version != DlpPacket.CURRENT_VERSION) {
            return DlpParseResult.UnsupportedVersion(packet.version)
        }
        if (packet.isExpired(nowSeconds)) {
            return DlpParseResult.Expired(packet)
        }
        return DlpParseResult.Success(packet)
    }

    private fun encodeProperties(packet: DlpPacket): ByteArray {
        val props = Properties().apply {
            setProperty("version", packet.version)
            setProperty("packetId", packet.packetId)
            setProperty("deviceName", packet.deviceName)
            setProperty("deviceUuid", packet.deviceUuid)
            setProperty("platform", packet.platform)
            setProperty("appVersion", packet.appVersion)
            setProperty("fingerprint", packet.fingerprint)
            setProperty("issuedAt", packet.issuedAt.toString())
            setProperty("expiresAt", packet.expiresAt.toString())
        }
        return ByteArrayOutputStream().use { output ->
            props.store(output, "DirectLink MVP packet")
            output.toByteArray()
        }
    }

    private fun Properties.require(name: String): String =
        getProperty(name) ?: error("Missing $name")
}
