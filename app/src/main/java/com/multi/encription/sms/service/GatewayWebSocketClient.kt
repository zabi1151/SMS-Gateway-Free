package com.multi.encription.sms.service

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.multi.encription.sms.core.SmsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

class GatewayWebSocketClient(
    context: Context,
    private val serverUrl: String,
    private val gatewayToken: String
) {

    companion object {

        private const val TAG =
            "GatewayWebSocket"

        private const val INITIAL_RECONNECT_DELAY =
            3000L

        private const val MAX_RECONNECT_DELAY =
            30000L
    }

    private val appContext =
        context.applicationContext

    private val smsManager =
        SmsManager(appContext)

    private val gson =
        Gson()

    private val scope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.IO
        )

    private val client =
        OkHttpClient.Builder()
            .pingInterval(
                20,
                TimeUnit.SECONDS
            )
            .connectTimeout(
                20,
                TimeUnit.SECONDS
            )
            .writeTimeout(
                20,
                TimeUnit.SECONDS
            )
            .readTimeout(
                0,
                TimeUnit.MILLISECONDS
            )
            .retryOnConnectionFailure(
                true
            )
            .build()

    @Volatile
    private var webSocket:
        WebSocket? = null

    @Volatile
    private var connected =
        false

    @Volatile
    private var connecting =
        false

    @Volatile
    private var shouldReconnect =
        true

    private var reconnectJob:
        Job? = null

    private var reconnectDelay =
        INITIAL_RECONNECT_DELAY

    // =====================================================
    // CONNECT
    // =====================================================

    @Synchronized
    fun connect() {

        if (
            !shouldReconnect
        ) {
            return
        }

        if (
            connected ||
            connecting
        ) {

            Log.d(
                TAG,
                "WebSocket already connected/connecting"
            )

            return
        }

        connecting =
            true

        try {

            Log.i(
                TAG,
                "Connecting to OTP backend..."
            )

            val request =
                Request.Builder()
                    .url(
                        serverUrl
                    )
                    .addHeader(
                        "Authorization",
                        "Bearer $gatewayToken"
                    )
                    .build()

            webSocket =
                client.newWebSocket(
                    request,
                    socketListener
                )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Unable to create WebSocket connection",
                e
            )

            connecting =
                false

            connected =
                false

            webSocket =
                null

            scheduleReconnect()
        }
    }

    // =====================================================
    // DISCONNECT
    // =====================================================

    @Synchronized
    fun disconnect() {

        Log.i(
            TAG,
            "Stopping WebSocket client"
        )

        shouldReconnect =
            false

        connected =
            false

        connecting =
            false

        reconnectJob?.cancel()

        reconnectJob =
            null

        try {

            webSocket?.close(
                1000,
                "Gateway service stopped"
            )

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Error while closing WebSocket",
                e
            )
        }

        webSocket =
            null

        try {

            client.dispatcher
                .cancelAll()

        } catch (_: Exception) {
        }

        try {

            client.dispatcher
                .executorService
                .shutdown()

        } catch (_: Exception) {
        }

        try {

            client.connectionPool
                .evictAll()

        } catch (_: Exception) {
        }

        scope.cancel()
    }

    // =====================================================
    // WEBSOCKET LISTENER
    // =====================================================

    private val socketListener =
        object :
            WebSocketListener() {

            override fun onOpen(
                socket: WebSocket,
                response: Response
            ) {

                Log.i(
                    TAG,
                    "Connected to OTP backend"
                )

                webSocket =
                    socket

                connecting =
                    false

                connected =
                    true

                reconnectDelay =
                    INITIAL_RECONNECT_DELAY

                reconnectJob?.cancel()

                reconnectJob =
                    null

                sendGatewayReady(
                    socket
                )
            }

            override fun onMessage(
                socket: WebSocket,
                text: String
            ) {

                Log.d(
                    TAG,
                    "Command received from backend"
                )

                handleMessage(
                    socket,
                    text
                )
            }

            override fun onMessage(
                socket: WebSocket,
                bytes: ByteString
            ) {

                onMessage(
                    socket,
                    bytes.utf8()
                )
            }

            override fun onClosing(
                socket: WebSocket,
                code: Int,
                reason: String
            ) {

                Log.w(
                    TAG,
                    "WebSocket closing: $code $reason"
                )

                connected =
                    false

                socket.close(
                    code,
                    reason
                )
            }

            override fun onClosed(
                socket: WebSocket,
                code: Int,
                reason: String
            ) {

                Log.w(
                    TAG,
                    "WebSocket closed: $code $reason"
                )

                handleDisconnectedSocket(
                    socket
                )
            }

            override fun onFailure(
                socket: WebSocket,
                throwable: Throwable,
                response: Response?
            ) {

                Log.e(
                    TAG,
                    "WebSocket connection failed: ${throwable.message}",
                    throwable
                )

                handleDisconnectedSocket(
                    socket
                )
            }
        }

    // =====================================================
    // DISCONNECTED SOCKET
    // =====================================================

    @Synchronized
    private fun handleDisconnectedSocket(
        socket: WebSocket
    ) {

        /*
         * Ignore callbacks belonging to an older socket
         * after a newer connection has already replaced it.
         */
        if (
            webSocket != null &&
            webSocket !== socket
        ) {

            Log.d(
                TAG,
                "Ignoring callback from stale WebSocket"
            )

            return
        }

        connected =
            false

        connecting =
            false

        webSocket =
            null

        if (
            shouldReconnect
        ) {

            scheduleReconnect()
        }
    }

    // =====================================================
    // READY MESSAGE
    // =====================================================

    private fun sendGatewayReady(
        socket: WebSocket
    ) {

        try {

            val readyMessage =
                JsonObject().apply {

                    addProperty(
                        "type",
                        "gateway_ready"
                    )
                }

            val sent =
                socket.send(
                    gson.toJson(
                        readyMessage
                    )
                )

            if (
                sent
            ) {

                Log.i(
                    TAG,
                    "Gateway ready message sent"
                )

            } else {

                Log.w(
                    TAG,
                    "Unable to queue gateway ready message"
                )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Unable to send gateway ready message",
                e
            )
        }
    }

    // =====================================================
    // COMMAND HANDLING
    // =====================================================

    private fun handleMessage(
        socket: WebSocket,
        message: String
    ) {

        scope.launch {

            try {

                val json =
                    gson.fromJson(
                        message,
                        JsonObject::class.java
                    )

                val type =
                    json.get(
                        "type"
                    )?.asString
                        ?: return@launch

                if (
                    type != "send_sms"
                ) {

                    Log.d(
                        TAG,
                        "Ignoring unsupported command: $type"
                    )

                    return@launch
                }

                val requestId =
                    json.get(
                        "request_id"
                    )?.asString

                val phoneNumber =
                    json.get(
                        "phone_number"
                    )?.asString

                val smsMessage =
                    json.get(
                        "message"
                    )?.asString

                if (
                    requestId.isNullOrBlank() ||
                    phoneNumber.isNullOrBlank() ||
                    smsMessage.isNullOrBlank()
                ) {

                    sendResult(
                        socket =
                            socket,

                        requestId =
                            requestId ?: "",

                        success =
                            false,

                        smsId =
                            null,

                        error =
                            "Invalid SMS command"
                    )

                    return@launch
                }

                Log.i(
                    TAG,
                    "Processing SMS request: $requestId"
                )

                /*
                 * Preserve the existing SmsManager API
                 * which has already worked in our
                 * end-to-end OTP test.
                 */
                val result =
                    smsManager.sendSms(
                        phoneNumber =
                            phoneNumber,

                        message =
                            smsMessage,

                        requestId =
                            requestId
                    )

                if (
                    result.isSuccess
                ) {

                    sendResult(
                        socket =
                            socket,

                        requestId =
                            requestId,

                        success =
                            true,

                        smsId =
                            result.getOrNull(),

                        error =
                            null
                    )

                } else {

                    sendResult(
                        socket =
                            socket,

                        requestId =
                            requestId,

                        success =
                            false,

                        smsId =
                            null,

                        error =
                            result
                                .exceptionOrNull()
                                ?.message
                                ?: "Unable to send SMS"
                    )
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Unable to process backend command",
                    e
                )
            }
        }
    }

    // =====================================================
    // SMS RESULT
    // =====================================================

    private fun sendResult(
        socket: WebSocket,
        requestId: String,
        success: Boolean,
        smsId: Long?,
        error: String?
    ) {

        try {

            val response =
                JsonObject().apply {

                    addProperty(
                        "type",
                        "sms_result"
                    )

                    addProperty(
                        "request_id",
                        requestId
                    )

                    addProperty(
                        "success",
                        success
                    )

                    if (
                        smsId != null
                    ) {

                        addProperty(
                            "sms_id",
                            smsId
                        )
                    }

                    if (
                        error != null
                    ) {

                        addProperty(
                            "error",
                            error
                        )
                    }
                }

            val sent =
                socket.send(
                    gson.toJson(
                        response
                    )
                )

            if (
                !sent
            ) {

                Log.w(
                    TAG,
                    "Unable to queue SMS result for request $requestId"
                )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Unable to send SMS result",
                e
            )
        }
    }

    // =====================================================
    // AUTOMATIC RECONNECT
    // =====================================================

    @Synchronized
    private fun scheduleReconnect() {

        if (
            !shouldReconnect
        ) {
            return
        }

        if (
            reconnectJob?.isActive == true
        ) {

            Log.d(
                TAG,
                "Reconnect already scheduled"
            )

            return
        }

        val delayForThisAttempt =
            reconnectDelay

        Log.i(
            TAG,
            "Reconnecting in ${delayForThisAttempt / 1000} seconds..."
        )

        reconnectJob =
            scope.launch {

                delay(
                    delayForThisAttempt
                )

                if (
                    !shouldReconnect
                ) {
                    return@launch
                }

                connecting =
                    false

                webSocket =
                    null

                connect()

                reconnectDelay =
                    (
                        delayForThisAttempt * 2
                    ).coerceAtMost(
                        MAX_RECONNECT_DELAY
                    )
            }
    }
}
