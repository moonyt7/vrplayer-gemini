package com.example.vrplayer

import android.content.Context

class LanCredentialStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(host: String): LanAuthConfig? {
        val key = normalizeHost(host)
        if (!prefs.getBoolean(flagKey(key), false)) {
            return null
        }
        return LanAuthConfig(
            isAnonymous = prefs.getBoolean(anonKey(key), true),
            username = prefs.getString(userKey(key), "") ?: "",
            password = prefs.getString(passKey(key), "") ?: "",
            port = prefs.getInt(portKey(key), 445),
            protocol = prefs.getString(protocolKey(key), "SMB") ?: "SMB",
            customShare = prefs.getString(shareKey(key), "") ?: ""
        )
    }

    fun save(host: String, auth: LanAuthConfig) {
        val key = normalizeHost(host)
        prefs.edit()
            .putBoolean(flagKey(key), true)
            .putBoolean(anonKey(key), auth.isAnonymous)
            .putString(userKey(key), auth.username)
            .putString(passKey(key), auth.password)
            .putInt(portKey(key), auth.port)
            .putString(protocolKey(key), auth.protocol)
            .putString(shareKey(key), auth.customShare)
            .apply()
    }

    fun clear(host: String) {
        val key = normalizeHost(host)
        prefs.edit()
            .remove(flagKey(key))
            .remove(anonKey(key))
            .remove(userKey(key))
            .remove(passKey(key))
            .remove(portKey(key))
            .remove(protocolKey(key))
            .remove(shareKey(key))
            .apply()
    }

    private fun normalizeHost(host: String): String = host.trim().lowercase()

    private fun flagKey(host: String) = "$host.saved"
    private fun anonKey(host: String) = "$host.anon"
    private fun userKey(host: String) = "$host.user"
    private fun passKey(host: String) = "$host.pass"
    private fun portKey(host: String) = "$host.port"
    private fun protocolKey(host: String) = "$host.protocol"
    private fun shareKey(host: String) = "$host.share"

    companion object {
        private const val PREFS_NAME = "lan_share_credentials"
    }
}
