package com.kirivsoft.directlink.packet

import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

class DlpSerializer {
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
        expiresAt = nowSeconds + ttlSeconds,
        passwordHash = hashPassword(password)
    )

    fun write(packet: DlpPacket, file: File) {
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
            setProperty("passwordHash", packet.passwordHash)
        }
        file.outputStream().use { output -> props.store(output, "DirectLink MVP packet") }
    }

    fun parse(file: File, password: String, nowSeconds: Long = System.currentTimeMillis() / 1000): DlpParseResult {
        val props = runCatching {
            Properties().apply {
                file.inputStream().use { input -> load(input) }
            }
        }.getOrElse { return DlpParseResult.Malformed("Cannot read packet: ${it.message}") }

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
                expiresAt = props.require("expiresAt").toLong(),
                passwordHash = props.require("passwordHash")
            )
        }.getOrElse { return DlpParseResult.Malformed("Invalid packet fields: ${it.message}") }

        if (packet.version != DlpPacket.CURRENT_VERSION) {
            return DlpParseResult.UnsupportedVersion(packet.version)
        }
        if (packet.passwordHash != hashPassword(password)) {
            return DlpParseResult.InvalidPassword("Password does not match packet")
        }
        if (packet.isExpired(nowSeconds)) {
            return DlpParseResult.Expired(packet)
        }
        return DlpParseResult.Success(packet)
    }

    private fun Properties.require(name: String): String =
        getProperty(name) ?: error("Missing $name")

    private fun hashPassword(password: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(password.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
