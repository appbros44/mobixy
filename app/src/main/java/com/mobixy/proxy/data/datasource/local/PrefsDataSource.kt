package com.mobixy.proxy.data.datasource.local

import android.content.Context

class PrefsDataSource(appContext: Context) {

    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun setProxyCredentials(username: String, password: String) {
        prefs.edit()
            .putString(KEY_PROXY_USERNAME, username)
            .putString(KEY_PROXY_PASSWORD, password)
            .apply()
    }

    fun getProxyCredentials(): Pair<String, String>? {
        val user = prefs.getString(KEY_PROXY_USERNAME, null)?.trim().orEmpty()
        val pass = prefs.getString(KEY_PROXY_PASSWORD, null)?.trim().orEmpty()
        if (user.isEmpty() || pass.isEmpty()) return null
        return user to pass
    }

    fun setBackendHost(host: String) {
        prefs.edit().putString(KEY_BACKEND_HOST, host.trim()).apply()
    }

    fun getBackendHost(): String? {
        val host = prefs.getString(KEY_BACKEND_HOST, null)?.trim().orEmpty()
        return host.ifEmpty { null }
    }

    fun setBackendEnrollToken(token: String) {
        prefs.edit().putString(KEY_BACKEND_ENROLL_TOKEN, token.trim()).apply()
    }

    fun getBackendEnrollToken(): String? {
        val token = prefs.getString(KEY_BACKEND_ENROLL_TOKEN, null)?.trim().orEmpty()
        return token.ifEmpty { null }
    }

    fun setDeviceId(deviceId: String) {
        prefs.edit().putString(KEY_DEVICE_ID, deviceId.trim()).apply()
    }

    fun getDeviceId(): String? {
        val id = prefs.getString(KEY_DEVICE_ID, null)?.trim().orEmpty()
        return id.ifEmpty { null }
    }

    companion object {
        private const val PREFS_NAME = "mobixy_prefs"
        private const val KEY_PROXY_USERNAME = "proxy_username"
        private const val KEY_PROXY_PASSWORD = "proxy_password"

        private const val KEY_BACKEND_HOST = "backend_host"
        private const val KEY_BACKEND_ENROLL_TOKEN = "backend_enroll_token"
        private const val KEY_DEVICE_ID = "device_id"
    }
}