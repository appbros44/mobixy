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

    companion object {
        private const val PREFS_NAME = "mobixy_prefs"
        private const val KEY_PROXY_USERNAME = "proxy_username"
        private const val KEY_PROXY_PASSWORD = "proxy_password"
    }
}