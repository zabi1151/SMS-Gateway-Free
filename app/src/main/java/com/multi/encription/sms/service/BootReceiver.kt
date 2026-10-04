package com.multi.encription.sms.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.multi.encription.sms.utils.ConfigManager

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {

        val action = intent.action

        Log.i(
            TAG,
            "System event received: $action"
        )

        if (
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        try {

            val configManager =
                ConfigManager(context.applicationContext)

            val directGatewayReady =
                configManager.isWebSocketEnabled &&
                    configManager.hasValidWebSocketConfig()

            val localServerEnabled =
                configManager.isServerEnabled

            if (
                !directGatewayReady &&
                !localServerEnabled
            ) {

                Log.i(
                    TAG,
                    "Gateway disabled or not configured; service not started"
                )

                return
            }

            Log.i(
                TAG,
                "Starting SMS Gateway after boot/package update"
            )

            SmsGatewayService.startService(
                context.applicationContext
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to start SMS Gateway after boot",
                e
            )
        }
    }
}
