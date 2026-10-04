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

    override fun onReceive(context: Context, intent: Intent) {

        val action = intent.action

        Log.i(TAG, "Received system event: $action")

        if (
            action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {

            val configManager = ConfigManager(context)

            val shouldStartService =
                configManager.isServerEnabled ||
                (
                    configManager.isWebSocketEnabled &&
                    configManager.hasValidWebSocketConfig()
                )

            if (shouldStartService) {

                try {

                    Log.i(
                        TAG,
                        "Starting SMS Gateway service"
                    )

                    SmsGatewayService.startService(context)

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Unable to start SMS Gateway service",
                        e
                    )
                }

            } else {

                Log.i(
                    TAG,
                    "Gateway is disabled, service will not start"
                )
            }
        }
    }
}
