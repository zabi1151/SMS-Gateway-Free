package com.multi.encription.sms.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.multi.encription.sms.MainActivity
import com.multi.encription.sms.R
import com.multi.encription.sms.api.SmsApiServer
import com.multi.encription.sms.database.SmsDatabase
import com.multi.encription.sms.utils.ConfigManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.NetworkInterface
import java.net.SocketException

class SmsGatewayService : Service() {

    private var apiServer: SmsApiServer? = null
    private var gatewayWebSocketClient: GatewayWebSocketClient? = null

    private lateinit var configManager: ConfigManager
    private lateinit var database: SmsDatabase

    private var wakeLock: PowerManager.WakeLock? = null

    private val serviceScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Main
        )

    private var cleanupJob: Job? = null

    companion object {

        private const val TAG =
            "SmsGatewayService"

        private const val NOTIFICATION_ID =
            1001

        private const val CHANNEL_ID =
            "sms_gateway_channel"

        private const val WAKE_LOCK_TAG =
            "SmsGateway::GatewayWakeLock"

        fun startService(context: Context) {

            val intent =
                Intent(
                    context,
                    SmsGatewayService::class.java
                )

            try {

                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.O
                ) {

                    context.startForegroundService(
                        intent
                    )

                } else {

                    context.startService(
                        intent
                    )
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Unable to start SMS Gateway service",
                    e
                )
            }
        }

        fun stopService(context: Context) {

            try {

                context.stopService(
                    Intent(
                        context,
                        SmsGatewayService::class.java
                    )
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Unable to stop SMS Gateway service",
                    e
                )
            }
        }
    }

    // =====================================================
    // SERVICE LIFECYCLE
    // =====================================================

    override fun onCreate() {

        super.onCreate()

        Log.i(
            TAG,
            "Creating SMS Gateway foreground service"
        )

        configManager =
            ConfigManager(this)

        database =
            SmsDatabase.getDatabase(this)

        createNotificationChannel()

        /*
         * Enter foreground state immediately.
         */
        startForeground(
            NOTIFICATION_ID,
            createNotification(
                "SMS Gateway starting..."
            )
        )

        acquireWakeLock()

        Log.i(
            TAG,
            "SMS Gateway Service created"
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        Log.i(
            TAG,
            "SMS Gateway Service start requested"
        )

        /*
         * Calling this again is safe and helps ensure
         * the service remains a foreground service
         * when Android recreates it.
         */
        startForeground(
            NOTIFICATION_ID,
            createNotification(
                "SMS Gateway active"
            )
        )

        startConfiguredComponents()

        startCleanupTask()

        updateServiceNotification()

        /*
         * Ask Android to recreate this service if its
         * process is killed for system resource reasons.
         */
        return START_STICKY
    }

    override fun onTaskRemoved(
        rootIntent: Intent?
    ) {

        /*
         * User swiping the Activity from Recents should
         * not intentionally stop the gateway.
         *
         * The foreground service remains responsible
         * for the persistent WebSocket connection.
         */
        Log.w(
            TAG,
            "App removed from Recents; gateway service remains active"
        )

        updateServiceNotification()

        super.onTaskRemoved(
            rootIntent
        )
    }

    override fun onDestroy() {

        Log.w(
            TAG,
            "SMS Gateway Service destroying"
        )

        stopGatewayWebSocket()

        stopApiServer()

        cleanupJob?.cancel()
        cleanupJob = null

        releaseWakeLock()

        serviceScope.cancel()

        super.onDestroy()

        Log.w(
            TAG,
            "SMS Gateway Service destroyed"
        )
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null

    // =====================================================
    // START CONFIGURED COMPONENTS
    // =====================================================

    private fun startConfiguredComponents() {

        if (
            configManager.isServerEnabled
        ) {

            startApiServer()

        } else {

            /*
             * If configuration changed while the service
             * was alive, make sure an old local server
             * is not left running.
             */
            stopApiServer()
        }

        if (
            configManager.isWebSocketEnabled &&
            configManager.hasValidWebSocketConfig()
        ) {

            startGatewayWebSocket()

        } else {

            stopGatewayWebSocket()
        }
    }

    // =====================================================
    // WAKE LOCK
    // =====================================================

    private fun acquireWakeLock() {

        try {

            if (
                wakeLock?.isHeld == true
            ) {
                return
            }

            val powerManager =
                getSystemService(
                    Context.POWER_SERVICE
                ) as PowerManager

            wakeLock =
                powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    WAKE_LOCK_TAG
                ).apply {

                    setReferenceCounted(false)

                    acquire()
                }

            Log.i(
                TAG,
                "Gateway wake lock acquired"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Unable to acquire gateway wake lock",
                e
            )
        }
    }

    private fun releaseWakeLock() {

        try {

            if (
                wakeLock?.isHeld == true
            ) {

                wakeLock?.release()
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Unable to release gateway wake lock",
                e
            )

        } finally {

            wakeLock = null
        }
    }

    // =====================================================
    // DIRECT BACKEND WEBSOCKET
    // =====================================================

    private fun startGatewayWebSocket() {

        if (
            !configManager.isWebSocketEnabled
        ) {

            Log.d(
                TAG,
                "Direct gateway disabled"
            )

            return
        }

        if (
            !configManager.hasValidWebSocketConfig()
        ) {

            Log.w(
                TAG,
                "Direct gateway configuration incomplete"
            )

            updateNotification(
                "Direct gateway configuration required"
            )

            return
        }

        if (
            gatewayWebSocketClient != null
        ) {

            Log.d(
                TAG,
                "WebSocket client already initialized"
            )

            return
        }

        try {

            Log.i(
                TAG,
                "Starting direct OTP backend connection"
            )

            gatewayWebSocketClient =
                GatewayWebSocketClient(
                    context =
                        applicationContext,

                    serverUrl =
                        configManager.webSocketUrl,

                    gatewayToken =
                        configManager.gatewayToken
                )

            gatewayWebSocketClient
                ?.connect()

            updateNotification(
                "Connecting to OTP backend..."
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Unable to start direct backend connection",
                e
            )

            gatewayWebSocketClient =
                null

            updateNotification(
                "Backend connection failed"
            )
        }
    }

    private fun stopGatewayWebSocket() {

        try {

            gatewayWebSocketClient
                ?.disconnect()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Unable to stop WebSocket gateway",
                e
            )

        } finally {

            gatewayWebSocketClient =
                null
        }
    }

    fun restartGatewayWebSocket() {

        serviceScope.launch {

            stopGatewayWebSocket()

            delay(
                1000
            )

            if (
                configManager.isWebSocketEnabled &&
                configManager.hasValidWebSocketConfig()
            ) {

                startGatewayWebSocket()
            }

            updateServiceNotification()
        }
    }

    // =====================================================
    // LOCAL HTTP API SERVER
    // =====================================================

    private fun startApiServer() {

        try {

            if (
                apiServer?.isAlive == true
            ) {

                Log.d(
                    TAG,
                    "API Server already running"
                )

                return
            }

            apiServer =
                SmsApiServer(
                    this,
                    configManager.serverPort
                )

            val started =
                apiServer
                    ?.startServer()
                    ?: false

            if (
                started
            ) {

                Log.i(
                    TAG,
                    "API Server started on port ${configManager.serverPort}"
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

            apiServer
                ?.stopServer()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Error stopping API Server",
                e
            )

        } finally {

            apiServer =
                null
        }
    }

    fun restartApiServer() {

        serviceScope.launch {

            stopApiServer()

            delay(
                1000
            )

            if (
                configManager.isServerEnabled
            ) {

                startApiServer()
            }

            updateServiceNotification()
        }
    }

    // =====================================================
    // SERVER URL LOGGING
    // =====================================================

    private fun logServerUrls() {

        try {

            val port =
                configManager.serverPort

            val interfaces =
                NetworkInterface
                    .getNetworkInterfaces()

            Log.i(
                TAG,
                "SMS Gateway API Server URLs:"
            )

            Log.i(
                TAG,
                "- Local: http://localhost:$port/api/info"
            )

            Log.i(
                TAG,
                "- Local: http://127.0.0.1:$port/api/info"
            )

            while (
                interfaces.hasMoreElements()
            ) {

                val networkInterface =
                    interfaces.nextElement()

                if (
                    !networkInterface.isLoopback &&
                    networkInterface.isUp
                ) {

                    val addresses =
                        networkInterface
                            .inetAddresses

                    while (
                        addresses.hasMoreElements()
                    ) {

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

    // =====================================================
    // DATABASE CLEANUP
    // =====================================================

    private fun startCleanupTask() {

        if (
            !configManager.isAutoDeleteOldSmsEnabled
        ) {
            return
        }

        /*
         * Avoid starting another 24-hour loop every time
         * Android calls onStartCommand().
         */
        if (
            cleanupJob?.isActive == true
        ) {
            return
        }

        cleanupJob =
            serviceScope.launch {

                while (
                    isActive
                ) {

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
                            database
                                .smsDao()
                                .run {

                                    val oldSmsCount =
                                        getSmsCountSince(
                                            cutoffTime
                                        )

                                    deleteOldSms(
                                        cutoffTime
                                    )

                                    oldSmsCount
                                }

                        if (
                            deletedCount > 0
                        ) {

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

    // =====================================================
    // NOTIFICATION
    // =====================================================

    private fun createNotificationChannel() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "SMS Gateway Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {

                    description =
                        "Keeps the SMS OTP gateway connected to the backend"

                    setShowBadge(
                        false
                    )
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
            "SMS Gateway running"
    ): Notification {

        val openAppIntent =
            Intent(
                this,
                MainActivity::class.java
            ).apply {

                flags =
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                openAppIntent,
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
            .setOngoing(
                true
            )
            .setAutoCancel(
                false
            )
            .setOnlyAlertOnce(
                true
            )
            .setCategory(
                NotificationCompat.CATEGORY_SERVICE
            )
            .setPriority(
                NotificationCompat.PRIORITY_LOW
            )
            .build()
    }

    private fun updateNotification(
        message: String
    ) {

        /*
         * The foreground service itself must keep its
         * notification available. We therefore update
         * the existing foreground notification rather
         * than stopping foreground mode.
         */
        val notificationManager =
            getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as NotificationManager

        notificationManager.notify(
            NOTIFICATION_ID,
            createNotification(
                message
            )
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

                    "Local API running on port ${configManager.serverPort}"

                else ->

                    "SMS Gateway service active"
            }

        updateNotification(
            message
        )
    }

    // =====================================================
    // STATUS
    // =====================================================

    fun getServerStatus():
        Map<String, Any> {

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
