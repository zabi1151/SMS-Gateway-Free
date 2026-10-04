package com.multi.encription.sms.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.multi.encription.sms.MainActivity
import com.multi.encription.sms.R
import com.multi.encription.sms.api.SmsApiServer
import com.multi.encription.sms.database.SmsDatabase
import com.multi.encription.sms.utils.ConfigManager
import kotlinx.coroutines.*
import java.net.NetworkInterface
import java.net.SocketException

class SmsGatewayService : Service() {

    private var apiServer: SmsApiServer? = null
    private var gatewayWebSocketClient: GatewayWebSocketClient? = null

    private lateinit var configManager: ConfigManager
    private lateinit var database: SmsDatabase

    private val serviceScope =
        CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val TAG = "SmsGatewayService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "sms_gateway_channel"

        fun startService(context: Context) {
            val intent =
                Intent(context, SmsGatewayService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent =
                Intent(context, SmsGatewayService::class.java)

            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()

        configManager = ConfigManager(this)
        database = SmsDatabase.getDatabase(this)

        createNotificationChannel()

        Log.d(TAG, "SMS Gateway Service created")
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        Log.d(TAG, "SMS Gateway Service starting")

        /*
         * Android requires a foreground service to display its
         * notification promptly after startForegroundService().
         */
        startForeground(
            NOTIFICATION_ID,
            createNotification("SMS Gateway starting...")
        )

        // Existing local HTTP API server
        if (configManager.isServerEnabled) {
            startApiServer()
        }

        // New direct connection to Deplexo backend
        startGatewayWebSocket()

        // Existing database cleanup
        startCleanupTask()

        updateServiceNotification()

        return START_STICKY
    }

    override fun onDestroy() {

        Log.d(TAG, "SMS Gateway Service destroying")

        stopGatewayWebSocket()
        stopApiServer()

        serviceScope.cancel()

        super.onDestroy()

        Log.d(TAG, "SMS Gateway Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ----------------------------------------------------
    // DIRECT BACKEND CONNECTION
    // ----------------------------------------------------

    private fun startGatewayWebSocket() {

        if (!configManager.isWebSocketEnabled) {
            Log.d(TAG, "WebSocket gateway is disabled")
            return
        }

        if (!configManager.hasValidWebSocketConfig()) {
            Log.w(TAG, "WebSocket configuration is incomplete")
            updateNotification(
                "Direct gateway configuration required"
            )
            return
        }

        if (gatewayWebSocketClient != null) {
            Log.d(TAG, "WebSocket client already initialized")
            return
        }

        try {

            Log.i(
                TAG,
                "Starting direct backend connection"
            )

            gatewayWebSocketClient =
                GatewayWebSocketClient(
                    context = applicationContext,
                    serverUrl = configManager.webSocketUrl,
                    gatewayToken = configManager.gatewayToken
                )

            gatewayWebSocketClient?.connect()

            updateNotification(
                "Connecting directly to OTP backend..."
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Unable to start WebSocket gateway",
                e
            )

            gatewayWebSocketClient = null

            updateNotification(
                "Backend connection failed"
            )
        }
    }

    private fun stopGatewayWebSocket() {

        try {

            gatewayWebSocketClient?.disconnect()
            gatewayWebSocketClient = null

            Log.i(
                TAG,
                "Direct backend connection stopped"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Unable to stop WebSocket gateway",
                e
            )
        }
    }

    fun restartGatewayWebSocket() {

        serviceScope.launch {

            stopGatewayWebSocket()

            delay(1000)

            startGatewayWebSocket()

            updateServiceNotification()
        }
    }

    // ----------------------------------------------------
    // EXISTING LOCAL HTTP API SERVER
    // ----------------------------------------------------

    private fun startApiServer() {

        try {

            if (apiServer?.isAlive == true) {
                Log.d(TAG, "API Server already running")
                return
            }

            apiServer =
                SmsApiServer(
                    this,
                    configManager.serverPort
                )

            val started =
                apiServer?.startServer() ?: false

            if (started) {

                Log.i(
                    TAG,
                    "API Server started successfully on port ${configManager.serverPort}"
                )

                logServerUrls()

            } else {

                Log.e(
                    TAG,
                    "Failed to start API Server"
                )
            }

            updateServiceNotification()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Error starting API Server",
                e
            )

            updateNotification(
                "Error starting API Server"
            )
        }
    }

    private fun stopApiServer() {

        try {

            apiServer?.stopServer()
            apiServer = null

            Log.i(TAG, "API Server stopped")

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Error stopping API Server",
                e
            )
        }
    }

    fun restartApiServer() {

        serviceScope.launch {

            stopApiServer()

            delay(1000)

            if (configManager.isServerEnabled) {
                startApiServer()
            }

            updateServiceNotification()
        }
    }

    // ----------------------------------------------------
    // SERVER URL LOGGING
    // ----------------------------------------------------

    private fun logServerUrls() {

        try {

            val port = configManager.serverPort
            val interfaces =
                NetworkInterface.getNetworkInterfaces()

            Log.i(TAG, "SMS Gateway API Server URLs:")
            Log.i(
                TAG,
                "- Local: http://localhost:$port/api/info"
            )
            Log.i(
                TAG,
                "- Local: http://127.0.0.1:$port/api/info"
            )

            while (interfaces.hasMoreElements()) {

                val networkInterface =
                    interfaces.nextElement()

                if (
                    !networkInterface.isLoopback &&
                    networkInterface.isUp
                ) {

                    val addresses =
                        networkInterface.inetAddresses

                    while (addresses.hasMoreElements()) {

                        val address =
                            addresses.nextElement()

                        if (
                            !address.isLoopbackAddress &&
                            address.hostAddress
                                ?.contains(':') == false
                        ) {

                            Log.i(
                                TAG,
                                "- Network: http://${address.hostAddress}:$port/api/info"
                            )
                        }
                    }
                }
            }

        } catch (e: SocketException) {

            Log.w(
                TAG,
                "Could not enumerate network interfaces",
                e
            )
        }
    }

    // ----------------------------------------------------
    // DATABASE CLEANUP
    // ----------------------------------------------------

    private fun startCleanupTask() {

        if (!configManager.isAutoDeleteOldSmsEnabled) {
            return
        }

        serviceScope.launch {

            while (isActive) {

                try {

                    val cutoffTime =
                        System.currentTimeMillis() -
                            (
                                configManager.autoDeleteDays *
                                    24L *
                                    60L *
                                    60L *
                                    1000L
                                )

                    val deletedCount =
                        database.smsDao().run {

                            val oldSmsCount =
                                getSmsCountSince(
                                    cutoffTime
                                )

                            deleteOldSms(
                                cutoffTime
                            )

                            oldSmsCount
                        }

                    if (deletedCount > 0) {

                        Log.d(
                            TAG,
                            "Cleaned up $deletedCount old SMS records"
                        )
                    }

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Error during cleanup task",
                        e
                    )
                }

                delay(
                    24L *
                        60L *
                        60L *
                        1000L
                )
            }
        }
    }

    // ----------------------------------------------------
    // NOTIFICATIONS
    // ----------------------------------------------------

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "SMS Gateway Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {

                    description =
                        "SMS Gateway background service"

                    setShowBadge(false)
                }

            val notificationManager =
                getSystemService(
                    Context.NOTIFICATION_SERVICE
                ) as NotificationManager

            notificationManager
                .createNotificationChannel(
                    channel
                )
        }
    }

    private fun createNotification(
        message: String =
            "SMS Gateway Service running"
    ): Notification {

        val intent =
            Intent(
                this,
                MainActivity::class.java
            )

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        return NotificationCompat
            .Builder(
                this,
                CHANNEL_ID
            )
            .setContentTitle(
                "SMS Gateway"
            )
            .setContentText(
                message
            )
            .setSmallIcon(
                R.drawable.ic_launcher_foreground
            )
            .setContentIntent(
                pendingIntent
            )
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }

    private fun updateNotification(
        message: String
    ) {

        if (!configManager.isNotificationEnabled) {
            return
        }

        val notification =
            createNotification(message)

        val notificationManager =
            getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as NotificationManager

        notificationManager.notify(
            NOTIFICATION_ID,
            notification
        )
    }

    private fun updateServiceNotification() {

        val localServerRunning =
            apiServer?.isAlive == true

        val directGatewayEnabled =
            configManager.isWebSocketEnabled &&
                configManager.hasValidWebSocketConfig()

        val message =
            when {
                directGatewayEnabled &&
                    localServerRunning ->
                    "Direct backend + local API active"

                directGatewayEnabled ->
                    "Direct OTP gateway active"

                localServerRunning ->
                    "API Server running on port ${configManager.serverPort}"

                else ->
                    "SMS Gateway Service running"
            }

        updateNotification(message)
    }

    // ----------------------------------------------------
    // STATUS
    // ----------------------------------------------------

    fun getServerStatus(): Map<String, Any> {

        return mapOf(
            "server_running" to
                (apiServer?.isAlive == true),

            "server_port" to
                configManager.serverPort,

            "server_enabled" to
                configManager.isServerEnabled,

            "websocket_enabled" to
                configManager.isWebSocketEnabled,

            "websocket_configured" to
                configManager.hasValidWebSocketConfig()
        )
    }
}
