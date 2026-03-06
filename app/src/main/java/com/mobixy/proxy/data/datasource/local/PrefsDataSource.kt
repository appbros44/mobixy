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

    fun setFcmToken(token: String) {
        prefs.edit().putString(KEY_FCM_TOKEN, token.trim()).apply()
    }

    fun getFcmToken(): String? {
        val token = prefs.getString(KEY_FCM_TOKEN, null)?.trim().orEmpty()
        return token.ifEmpty { null }
    }

    fun setDeviceSecret(secret: String) {
        prefs.edit().putString(KEY_DEVICE_SECRET, secret.trim()).apply()
    }

    fun getDeviceSecret(): String? {
        val s = prefs.getString(KEY_DEVICE_SECRET, null)?.trim().orEmpty()
        return s.ifEmpty { null }
    }

    fun setDeviceJwt(token: String) {
        prefs.edit().putString(KEY_DEVICE_JWT, token.trim()).apply()
    }

    fun getDeviceJwt(): String? {
        val t = prefs.getString(KEY_DEVICE_JWT, null)?.trim().orEmpty()
        return t.ifEmpty { null }
    }

    fun setLastOpenEpochDay(epochDay: Long) {
        prefs.edit().putLong(KEY_LAST_OPEN_EPOCH_DAY, epochDay).apply()
    }

    fun getLastOpenEpochDay(): Long? {
        val d = prefs.getLong(KEY_LAST_OPEN_EPOCH_DAY, -1L)
        return if (d >= 0L) d else null
    }

    fun setDayStreakCount(count: Int) {
        prefs.edit().putInt(KEY_DAY_STREAK_COUNT, count).apply()
    }

    fun getDayStreakCount(): Int {
        return prefs.getInt(KEY_DAY_STREAK_COUNT, 0)
    }

    fun setAgentConnected(connected: Boolean) {
        prefs.edit().putBoolean(KEY_AGENT_CONNECTED, connected).apply()
    }

    fun isAgentConnected(): Boolean {
        return prefs.getBoolean(KEY_AGENT_CONNECTED, false)
    }

    fun setAgentSelectedAction(action: String) {
        prefs.edit().putString(KEY_AGENT_SELECTED_ACTION, action.trim()).apply()
    }

    fun getAgentSelectedAction(): String? {
        val a = prefs.getString(KEY_AGENT_SELECTED_ACTION, null)?.trim().orEmpty()
        return a.ifEmpty { null }
    }

    companion object {
        private const val PREFS_NAME = "mobixy_prefs"
        private const val KEY_PROXY_USERNAME = "proxy_username"
        private const val KEY_PROXY_PASSWORD = "proxy_password"

        private const val KEY_BACKEND_HOST = "backend_host"
        private const val KEY_BACKEND_ENROLL_TOKEN = "backend_enroll_token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val KEY_DEVICE_SECRET = "device_secret"
        private const val KEY_DEVICE_JWT = "device_jwt"

        private const val KEY_LAST_OPEN_EPOCH_DAY = "last_open_epoch_day"
        private const val KEY_DAY_STREAK_COUNT = "day_streak_count"
        private const val KEY_AGENT_CONNECTED = "agent_connected"
        private const val KEY_AGENT_SELECTED_ACTION = "agent_selected_action"
    }
}