package com.multi.encription.sms.service

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.multi.encription.sms.core.SmsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
        private const val TAG = "GatewayWebSocket"
        private const val RECONNECT_DELAY = 5000L
    }

    private val appContext = context.applicationContext
    private val smsManager = SmsManager(appContext)
    private val gson = Gson()

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private var shouldReconnect = true
    private var reconnecting = false

    fun connect() {
        if (webSocket != null) {
            return
        }

        try {
            Log.i(TAG, "Connecting to gateway backend...")

            val request = Request.Builder()
                .url(serverUrl)
                .addHeader(
                    "Authorization",
                    "Bearer $gatewayToken"
                )
                .build()

            webSocket = client.newWebSocket(
                request,
                socketListener
            )

        } catch (e: Exception) {
            Log.e(
                TAG,
                "Unable to create WebSocket connection",
                e
            )

            scheduleReconnect()
        }
    }

    fun disconnect() {
        shouldReconnect = false

        try {
            webSocket?.close(
                1000,
                "Gateway service stopped"
            )
        } catch (_: Exception) {
        }

        webSocket = null
        client.dispatcher.executorService.shutdown()
        scope.cancel()
    }

    private val socketListener =
        object : WebSocketListener() {

            override fun onOpen(
                webSocket: WebSocket,
                response: Response
            ) {
                Log.i(
                    TAG,
                    "Connected to OTP backend"
                )

                reconnecting = false

                val readyMessage = JsonObject().apply {
                    addProperty(
                        "type",
                        "gateway_ready"
                    )
                }

                webSocket.send(
                    gson.toJson(readyMessage)
                )
            }

            override fun onMessage(
                webSocket: WebSocket,
                text: String
            ) {
                Log.d(
                    TAG,
                    "Command received from backend"
                )

                handleMessage(
                    webSocket,
                    text
                )
            }

            override fun onMessage(
                webSocket: WebSocket,
                bytes: ByteString
            ) {
                onMessage(
                    webSocket,
                    bytes.utf8()
                )
            }

            override fun onClosing(
                webSocket: WebSocket,
                code: Int,
                reason: String
            ) {
                Log.w(
                    TAG,
                    "WebSocket closing: $code $reason"
                )

                webSocket.close(
                    code,
                    reason
                )
            }

            override fun onClosed(
                webSocket: WebSocket,
                code: Int,
                reason: String
            ) {
                Log.w(
                    TAG,
                    "WebSocket closed: $code $reason"
                )

                this@GatewayWebSocketClient.webSocket =
                    null

                scheduleReconnect()
            }

            override fun onFailure(
                webSocket: WebSocket,
                t: Throwable,
                response: Response?
            ) {
                Log.e(
                    TAG,
                    "WebSocket connection failed: ${t.message}"
                )

                this@GatewayWebSocketClient.webSocket =
                    null

                scheduleReconnect()
            }
        }

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
                    json.get("type")
                        ?.asString
                        ?: return@launch

                if (type != "send_sms") {
                    return@launch
                }

                val requestId =
                    json.get("request_id")
                        ?.asString

                val phoneNumber =
                    json.get("phone_number")
                        ?.asString

                val smsMessage =
                    json.get("message")
                        ?.asString

                if (
                    requestId.isNullOrBlank() ||
                    phoneNumber.isNullOrBlank() ||
                    smsMessage.isNullOrBlank()
                ) {

                    sendResult(
                        socket = socket,
                        requestId =
                            requestId ?: "",
                        success = false,
                        smsId = null,
                        error =
                            "Invalid SMS command"
                    )

                    return@launch
                }

                Log.i(
                    TAG,
                    "Processing SMS request: $requestId"
                )

                val result =
                    smsManager.sendSms(
                        phoneNumber = phoneNumber,
                        message = smsMessage,
                        requestId = requestId
                    )

                if (result.isSuccess) {

                    sendResult(
                        socket = socket,
                        requestId = requestId,
                        success = true,
                        smsId =
                            result.getOrNull(),
                        error = null
                    )

                } else {

                    sendResult(
                        socket = socket,
                        requestId = requestId,
                        success = false,
                        smsId = null,
                        error =
                            result.exceptionOrNull()
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

    private fun sendResult(
        socket: WebSocket,
        requestId: String,
        success: Boolean,
        smsId: Long?,
        error: String?
    ) {

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

                if (smsId != null) {
                    addProperty(
                        "sms_id",
                        smsId
                    )
                }

                if (error != null) {
                    addProperty(
                        "error",
                        error
                    )
                }
            }

        socket.send(
            gson.toJson(response)
        )
    }

    private fun scheduleReconnect() {

        if (
            !shouldReconnect ||
            reconnecting
        ) {
            return
        }

        reconnecting = true

        scope.launch {

            Log.i(
                TAG,
                "Reconnecting in 5 seconds..."
            )

            kotlinx.coroutines.delay(
                RECONNECT_DELAY
            )

            reconnecting = false

            if (shouldReconnect) {
                connect()
            }
        }
    }
}
