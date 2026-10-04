package com.multi.encription.sms.utils

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

class ConfigManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    companion object {
        private const val PREFS_NAME = "sms_gateway_config"

        // Existing API/server configuration
        private const val KEY_API_KEY = "api_key"
        private const val KEY_SERVER_PORT = "server_port"
        private const val KEY_SERVER_ENABLED = "server_enabled"

        // Rate limiting
        private const val KEY_RATE_LIMIT_ENABLED = "rate_limit_enabled"
        private const val KEY_RATE_LIMIT_PER_MINUTE = "rate_limit_per_minute"
        private const val KEY_RATE_LIMIT_PER_HOUR = "rate_limit_per_hour"

        // SMS cleanup
        private const val KEY_AUTO_DELETE_OLD_SMS = "auto_delete_old_sms"
        private const val KEY_AUTO_DELETE_DAYS = "auto_delete_days"

        // Notifications
        private const val KEY_NOTIFICATION_ENABLED = "notification_enabled"

        // Existing external domain configuration
        private const val KEY_EXTERNAL_DOMAIN = "external_domain"
        private const val KEY_USE_EXTERNAL_DOMAIN = "use_external_domain"

        // Direct backend WebSocket configuration
        private const val KEY_WEBSOCKET_ENABLED = "websocket_enabled"
        private const val KEY_WEBSOCKET_URL = "websocket_url"
        private const val KEY_GATEWAY_TOKEN = "gateway_token"

        const val DEFAULT_PORT = 8080
        const val DEFAULT_RATE_LIMIT_PER_MINUTE = 10
        const val DEFAULT_RATE_LIMIT_PER_HOUR = 100
        const val DEFAULT_AUTO_DELETE_DAYS = 30
    }

    // ----------------------------------------------------
    // API KEY
    // ----------------------------------------------------

    var apiKey: String
        get() =
            prefs.getString(KEY_API_KEY, null)
                ?: generateAndSaveApiKey()
        set(value) =
            prefs.edit()
                .putString(KEY_API_KEY, value)
                .apply()

    // ----------------------------------------------------
    // LOCAL HTTP SERVER
    // ----------------------------------------------------

    var serverPort: Int
        get() =
            prefs.getInt(
                KEY_SERVER_PORT,
                DEFAULT_PORT
            )
        set(value) =
            prefs.edit()
                .putInt(KEY_SERVER_PORT, value)
                .apply()

    var isServerEnabled: Boolean
        get() =
            prefs.getBoolean(
                KEY_SERVER_ENABLED,
                false
            )
        set(value) =
            prefs.edit()
                .putBoolean(KEY_SERVER_ENABLED, value)
                .apply()

    // ----------------------------------------------------
    // RATE LIMITING
    // ----------------------------------------------------

    var isRateLimitEnabled: Boolean
        get() =
            prefs.getBoolean(
                KEY_RATE_LIMIT_ENABLED,
                true
            )
        set(value) =
            prefs.edit()
                .putBoolean(KEY_RATE_LIMIT_ENABLED, value)
                .apply()

    var rateLimitPerMinute: Int
        get() =
            prefs.getInt(
                KEY_RATE_LIMIT_PER_MINUTE,
                DEFAULT_RATE_LIMIT_PER_MINUTE
            )
        set(value) =
            prefs.edit()
                .putInt(KEY_RATE_LIMIT_PER_MINUTE, value)
                .apply()

    var rateLimitPerHour: Int
        get() =
            prefs.getInt(
                KEY_RATE_LIMIT_PER_HOUR,
                DEFAULT_RATE_LIMIT_PER_HOUR
            )
        set(value) =
            prefs.edit()
                .putInt(KEY_RATE_LIMIT_PER_HOUR, value)
                .apply()

    // ----------------------------------------------------
    // AUTO DELETE OLD SMS
    // ----------------------------------------------------

    var isAutoDeleteOldSmsEnabled: Boolean
        get() =
            prefs.getBoolean(
                KEY_AUTO_DELETE_OLD_SMS,
                true
            )
        set(value) =
            prefs.edit()
                .putBoolean(
                    KEY_AUTO_DELETE_OLD_SMS,
                    value
                )
                .apply()

    var autoDeleteDays: Int
        get() =
            prefs.getInt(
                KEY_AUTO_DELETE_DAYS,
                DEFAULT_AUTO_DELETE_DAYS
            )
        set(value) =
            prefs.edit()
                .putInt(KEY_AUTO_DELETE_DAYS, value)
                .apply()

    // ----------------------------------------------------
    // NOTIFICATIONS
    // ----------------------------------------------------

    var isNotificationEnabled: Boolean
        get() =
            prefs.getBoolean(
                KEY_NOTIFICATION_ENABLED,
                true
            )
        set(value) =
            prefs.edit()
                .putBoolean(
                    KEY_NOTIFICATION_ENABLED,
                    value
                )
                .apply()

    // ----------------------------------------------------
    // EXTERNAL DOMAIN
    // ----------------------------------------------------

    var externalDomain: String
        get() =
            prefs.getString(
                KEY_EXTERNAL_DOMAIN,
                ""
            ) ?: ""
        set(value) =
            prefs.edit()
                .putString(
                    KEY_EXTERNAL_DOMAIN,
                    value.trim()
                )
                .apply()

    var useExternalDomain: Boolean
        get() =
            prefs.getBoolean(
                KEY_USE_EXTERNAL_DOMAIN,
                false
            )
        set(value) =
            prefs.edit()
                .putBoolean(
                    KEY_USE_EXTERNAL_DOMAIN,
                    value
                )
                .apply()

    // ----------------------------------------------------
    // DIRECT WEBSOCKET BACKEND
    // ----------------------------------------------------

    var isWebSocketEnabled: Boolean
        get() =
            prefs.getBoolean(
                KEY_WEBSOCKET_ENABLED,
                false
            )
        set(value) =
            prefs.edit()
                .putBoolean(
                    KEY_WEBSOCKET_ENABLED,
                    value
                )
                .apply()

    var webSocketUrl: String
        get() =
            prefs.getString(
                KEY_WEBSOCKET_URL,
                ""
            ) ?: ""
        set(value) =
            prefs.edit()
                .putString(
                    KEY_WEBSOCKET_URL,
                    value.trim()
                )
                .apply()

    var gatewayToken: String
        get() =
            prefs.getString(
                KEY_GATEWAY_TOKEN,
                ""
            ) ?: ""
        set(value) =
            prefs.edit()
                .putString(
                    KEY_GATEWAY_TOKEN,
                    value.trim()
                )
                .apply()

    // ----------------------------------------------------
    // API BASE URL
    // ----------------------------------------------------

    fun getApiBaseUrl(localIp: String): String {
        return if (
            useExternalDomain &&
            externalDomain.isNotBlank()
        ) {
            if (
                externalDomain.startsWith(
                    "http://"
                ) ||
                externalDomain.startsWith(
                    "https://"
                )
            ) {
                externalDomain
            } else {
                "https://$externalDomain"
            }
        } else {
            "http://$localIp:$serverPort"
        }
    }

    // ----------------------------------------------------
    // API KEY GENERATION
    // ----------------------------------------------------

    private fun generateAndSaveApiKey(): String {
        val newApiKey =
            "sms_" +
                UUID.randomUUID()
                    .toString()
                    .replace("-", "")

        apiKey = newApiKey

        return newApiKey
    }

    fun regenerateApiKey(): String {
        return generateAndSaveApiKey()
    }

    // ----------------------------------------------------
    // RESET
    // ----------------------------------------------------

    fun resetToDefaults() {
        prefs.edit()
            .clear()
            .apply()
    }

    // ----------------------------------------------------
    // EXPORT
    // ----------------------------------------------------

    fun exportConfig(): Map<String, Any> {

        /*
         * IMPORTANT:
         *
         * gatewayToken is intentionally NOT exported.
         * It is a private authentication credential.
         */

        return mapOf(
            "api_key" to apiKey,
            "server_port" to serverPort,
            "server_enabled" to isServerEnabled,
            "rate_limit_enabled" to isRateLimitEnabled,
            "rate_limit_per_minute" to rateLimitPerMinute,
            "rate_limit_per_hour" to rateLimitPerHour,
            "auto_delete_old_sms" to isAutoDeleteOldSmsEnabled,
            "auto_delete_days" to autoDeleteDays,
            "notification_enabled" to isNotificationEnabled,
            "external_domain" to externalDomain,
            "use_external_domain" to useExternalDomain,
            "websocket_enabled" to isWebSocketEnabled,
            "websocket_url" to webSocketUrl
        )
    }

    // ----------------------------------------------------
    // WEBSOCKET CONFIG VALIDATION
    // ----------------------------------------------------

    fun hasValidWebSocketConfig(): Boolean {

        if (!isWebSocketEnabled) {
            return false
        }

        if (webSocketUrl.isBlank()) {
            return false
        }

        if (gatewayToken.isBlank()) {
            return false
        }

        return webSocketUrl.startsWith("ws://") ||
            webSocketUrl.startsWith("wss://")
    }
}
