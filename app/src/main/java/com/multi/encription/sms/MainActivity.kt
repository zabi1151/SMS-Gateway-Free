package com.multi.encription.sms

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.multi.encription.sms.core.SmsManager
import com.multi.encription.sms.database.SmsDatabase
import com.multi.encription.sms.service.SmsGatewayService
import com.multi.encription.sms.ui.SmsHistoryAdapter
import com.multi.encription.sms.utils.ConfigManager
import com.multi.encription.sms.utils.PermissionHelper
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var configManager: ConfigManager
    private lateinit var smsManager: SmsManager
    private lateinit var database: SmsDatabase
    private lateinit var smsHistoryAdapter: SmsHistoryAdapter

    // Existing UI components
    private lateinit var serverStatusText: TextView
    private lateinit var serverToggle: SwitchMaterial
    private lateinit var apiKeyText: TextView
    private lateinit var portText: TextView
    private lateinit var apiUrlsText: TextView
    private lateinit var testApiButton: Button
    private lateinit var externalDomainButton: Button
    private lateinit var smsHistoryRecyclerView: RecyclerView
    private lateinit var sendSmsFab: FloatingActionButton

    companion object {
        private const val DEFAULT_WEBSOCKET_URL =
            "wss://sms-otp-backend.de.deplexo.com/gateway"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(
            findViewById(R.id.main)
        ) { view, insets ->

            val systemBars =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                )

            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            )

            insets
        }

        initializeComponents()

        setupUI()

        checkPermissions()

        /*
         * If Direct Backend was previously enabled,
         * make sure foreground gateway service starts
         * when the user opens the app.
         */
        if (
            configManager.isWebSocketEnabled &&
            configManager.hasValidWebSocketConfig()
        ) {
            SmsGatewayService.startService(this)
        }
    }

    // =====================================================
    // INITIALIZATION
    // =====================================================

    private fun initializeComponents() {

        configManager =
            ConfigManager(this)

        smsManager =
            SmsManager(this)

        database =
            SmsDatabase.getDatabase(this)

        serverStatusText =
            findViewById(
                R.id.serverStatusText
            )

        serverToggle =
            findViewById(
                R.id.serverToggle
            )

        apiKeyText =
            findViewById(
                R.id.apiKeyText
            )

        portText =
            findViewById(
                R.id.portText
            )

        apiUrlsText =
            findViewById(
                R.id.apiUrlsText
            )

        testApiButton =
            findViewById(
                R.id.testApiButton
            )

        externalDomainButton =
            findViewById(
                R.id.externalDomainButton
            )

        smsHistoryRecyclerView =
            findViewById(
                R.id.smsHistoryRecyclerView
            )

        sendSmsFab =
            findViewById(
                R.id.sendSmsFab
            )
    }

    // =====================================================
    // UI SETUP
    // =====================================================

    private fun setupUI() {

        setupSmsHistory()

        setupServerToggle()

        sendSmsFab.setOnClickListener {
            showSendSmsDialog()
        }

        updateApiKeyDisplay()
        updatePortDisplay()

        apiKeyText.setOnClickListener {
            showApiKeyDialog()
        }

        portText.setOnClickListener {
            showPortDialog()
        }

        apiUrlsText.setOnClickListener {
            showApiUrlsDialog()
        }

        testApiButton.setOnClickListener {
            showTestApiDialog()
        }

        /*
         * We reuse the existing External Domain button
         * for the new Termux-free Direct Backend setup.
         */
        externalDomainButton.setOnClickListener {
            showDirectBackendDialog()
        }

        updateServerStatus()
        updateApiUrls()
        updateDirectBackendButton()
    }

    private fun setupSmsHistory() {

        smsHistoryAdapter =
            SmsHistoryAdapter { sms ->

                showSmsDetails(sms)
            }

        smsHistoryRecyclerView.apply {

            layoutManager =
                LinearLayoutManager(
                    this@MainActivity
                )

            adapter =
                smsHistoryAdapter
        }

        database
            .smsDao()
            .getAllSmsLiveData()
            .observe(this) { smsList ->

                smsHistoryAdapter
                    .submitList(
                        smsList
                    )
            }
    }

    // =====================================================
    // LOCAL API SERVER TOGGLE
    // =====================================================

    private fun setupServerToggle() {

        serverToggle.isChecked =
            configManager.isServerEnabled

        serverToggle
            .setOnCheckedChangeListener { _, isChecked ->

                configManager.isServerEnabled =
                    isChecked

                /*
                 * Important:
                 *
                 * Local HTTP API and Direct Backend
                 * connection are independent.
                 *
                 * Turning Local API OFF must NOT stop
                 * the foreground service when WebSocket
                 * mode is enabled.
                 */
                if (
                    isChecked ||
                    configManager.isWebSocketEnabled
                ) {

                    restartGatewayService()

                } else {

                    SmsGatewayService
                        .stopService(this)
                }

                updateServerStatus()
                updateApiUrls()
            }
    }

    // =====================================================
    // PERMISSIONS
    // =====================================================

    private fun checkPermissions() {

        if (
            !PermissionHelper
                .hasSmsPermissions(this)
        ) {

            if (
                PermissionHelper
                    .shouldShowSmsPermissionRationale(
                        this
                    )
            ) {

                showPermissionRationaleDialog()

            } else {

                PermissionHelper
                    .requestSmsPermissions(
                        this
                    )
            }
        }
    }

    private fun showPermissionRationaleDialog() {

        AlertDialog
            .Builder(this)
            .setTitle(
                "SMS Permissions Required"
            )
            .setMessage(
                "This app needs SMS permissions to send messages through the device. Please grant the permissions to continue."
            )
            .setPositiveButton(
                "Grant Permissions"
            ) { _, _ ->

                PermissionHelper
                    .requestSmsPermissions(
                        this
                    )
            }
            .setNegativeButton(
                "Cancel"
            ) { dialog, _ ->

                dialog.dismiss()

                Toast.makeText(
                    this,
                    "SMS permissions are required for the app to function",
                    Toast.LENGTH_LONG
                ).show()
            }
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {

        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        PermissionHelper
            .handlePermissionResult(

                requestCode =
                    requestCode,

                permissions =
                    permissions,

                grantResults =
                    grantResults,

                onSmsPermissionGranted = {

                    Toast.makeText(
                        this,
                        "SMS permissions granted",
                        Toast.LENGTH_SHORT
                    ).show()

                    startGatewayIfNeeded()
                },

                onSmsPermissionDenied = {

                    Toast.makeText(
                        this,
                        "SMS permissions denied. App functionality will be limited.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
    }

    // =====================================================
    // SERVER STATUS
    // =====================================================

    private fun updateServerStatus() {

        val localServerEnabled =
            configManager.isServerEnabled

        val directBackendEnabled =
            configManager.isWebSocketEnabled &&
            configManager.hasValidWebSocketConfig()

        serverStatusText.text =
            when {

                localServerEnabled &&
                    directBackendEnabled ->

                    "Server Status: Local API + Direct Backend enabled"

                directBackendEnabled ->

                    "Server Status: Direct Backend enabled"

                localServerEnabled ->

                    "Server Status: Running on port ${configManager.serverPort}"

                else ->

                    "Server Status: Stopped"
            }
    }

    // =====================================================
    // API KEY
    // =====================================================

    private fun updateApiKeyDisplay() {

        val apiKey =
            configManager.apiKey

        apiKeyText.text =
            "API Key: ${apiKey.take(10)}... (tap to view/change)"
    }

    private fun showApiKeyDialog() {

        val currentApiKey =
            configManager.apiKey

        val editText =
            EditText(this).apply {

                setText(
                    currentApiKey
                )

                selectAll()
            }

        AlertDialog
            .Builder(this)
            .setTitle(
                "API Key"
            )
            .setMessage(
                "Current API Key (copy this for API access):"
            )
            .setView(
                editText
            )
            .setPositiveButton(
                "Generate New"
            ) { _, _ ->

                configManager
                    .regenerateApiKey()

                updateApiKeyDisplay()

                Toast.makeText(
                    this,
                    "New API key generated",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(
                "Close",
                null
            )
            .show()
    }

    // =====================================================
    // PORT
    // =====================================================

    private fun updatePortDisplay() {

        portText.text =
            "Port: ${configManager.serverPort} (tap to change)"
    }

    private fun showPortDialog() {

        val editText =
            EditText(this).apply {

                setText(
                    configManager
                        .serverPort
                        .toString()
                )

                inputType =
                    InputType.TYPE_CLASS_NUMBER
            }

        AlertDialog
            .Builder(this)
            .setTitle(
                "Server Port"
            )
            .setMessage(
                "Enter the port number for the local API server:"
            )
            .setView(
                editText
            )
            .setPositiveButton(
                "Save"
            ) { _, _ ->

                val newPort =
                    editText
                        .text
                        .toString()
                        .toIntOrNull()

                if (
                    newPort != null &&
                    newPort in 1024..65535
                ) {

                    configManager.serverPort =
                        newPort

                    updatePortDisplay()
                    updateApiUrls()

                    if (
                        configManager.isServerEnabled
                    ) {

                        restartGatewayService()
                    }

                    Toast.makeText(
                        this,
                        "Port updated to $newPort",
                        Toast.LENGTH_SHORT
                    ).show()

                } else {

                    Toast.makeText(
                        this,
                        "Please enter a valid port number (1024-65535)",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton(
                "Cancel",
                null
            )
            .show()
    }

    // =====================================================
    // API URL DISPLAY
    // =====================================================

    private fun updateApiUrls() {

        val deviceIp =
            getDeviceIpAddress()

        val baseUrl =
            configManager
                .getApiBaseUrl(
                    deviceIp
                )

        val accessType =
            if (
                configManager.useExternalDomain
            ) {
                "External Domain"
            } else {
                "Local Network"
            }

        val directBackendStatus =
            if (
                configManager.isWebSocketEnabled &&
                configManager.hasValidWebSocketConfig()
            ) {
                "Enabled"
            } else {
                "Disabled"
            }

        val urlsText =
            """
            API Base URL ($accessType): $baseUrl

            Main Endpoints:
            • Send SMS: POST $baseUrl/api/send
            • Check Status: GET $baseUrl/api/status
            • SMS History: GET $baseUrl/api/history
            • Server Info: GET $baseUrl/api/info

            Direct OTP Backend: $directBackendStatus

            (Tap to view full details)
            """.trimIndent()

        apiUrlsText.text =
            urlsText
    }

    private fun getDeviceIpAddress(): String {

        try {

            val interfaces =
                java.net.NetworkInterface
                    .getNetworkInterfaces()

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

                            return address.hostAddress
                                ?: "192.168.1.100"
                        }
                    }
                }
            }

        } catch (_: Exception) {
        }

        return "192.168.1.100"
    }

    // =====================================================
    // MANUAL SMS
    // =====================================================

    private fun showSendSmsDialog() {

        if (
            !PermissionHelper
                .hasSmsPermissions(this)
        ) {

            Toast.makeText(
                this,
                "SMS permissions required",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        val dialogView =
            layoutInflater.inflate(
                R.layout.dialog_send_sms,
                null
            )

        val phoneEditText =
            dialogView
                .findViewById<EditText>(
                    R.id.phoneEditText
                )

        val messageEditText =
            dialogView
                .findViewById<EditText>(
                    R.id.messageEditText
                )

        AlertDialog
            .Builder(this)
            .setTitle(
                "Send SMS"
            )
            .setView(
                dialogView
            )
            .setPositiveButton(
                "Send"
            ) { _, _ ->

                val phone =
                    phoneEditText
                        .text
                        .toString()
                        .trim()

                val message =
                    messageEditText
                        .text
                        .toString()
                        .trim()

                if (
                    phone.isBlank() ||
                    message.isBlank()
                ) {

                    Toast.makeText(
                        this,
                        "Please fill in all fields",
                        Toast.LENGTH_SHORT
                    ).show()

                    return@setPositiveButton
                }

                sendSms(
                    phone,
                    message
                )
            }
            .setNegativeButton(
                "Cancel",
                null
            )
            .show()
    }

    private fun sendSms(
        phoneNumber: String,
        message: String
    ) {

        lifecycleScope.launch {

            try {

                val result =
                    smsManager.sendSms(
                        phoneNumber,
                        message
                    )

                if (
                    result.isSuccess
                ) {

                    Toast.makeText(
                        this@MainActivity,
                        "SMS queued for sending",
                        Toast.LENGTH_SHORT
                    ).show()

                } else {

                    val error =
                        result.exceptionOrNull()

                    Toast.makeText(
                        this@MainActivity,
                        "Failed to send SMS: ${error?.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {

                Toast.makeText(
                    this@MainActivity,
                    "Error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // =====================================================
    // SMS DETAILS
    // =====================================================

    private fun showSmsDetails(
        sms: com.multi.encription.sms.database.SmsEntity
    ) {

        val dateFormat =
            java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                java.util.Locale.getDefault()
            )

        val message =
            """
            Phone: ${sms.phoneNumber}
            Message: ${sms.message}
            Status: ${sms.status}
            Timestamp: ${dateFormat.format(java.util.Date(sms.timestamp))}
            ${
                if (
                    sms.deliveryTimestamp != null
                ) {
                    "Delivered: ${dateFormat.format(java.util.Date(sms.deliveryTimestamp))}"
                } else {
                    ""
                }
            }
            ${
                if (
                    sms.errorMessage != null
                ) {
                    "Error: ${sms.errorMessage}"
                } else {
                    ""
                }
            }
            ${
                if (
                    sms.requestId != null
                ) {
                    "Request ID: ${sms.requestId}"
                } else {
                    ""
                }
            }
            """.trimIndent()

        AlertDialog
            .Builder(this)
            .setTitle(
                "SMS Details"
            )
            .setMessage(
                message
            )
            .setPositiveButton(
                "OK",
                null
            )
            .show()
    }

    // =====================================================
    // API ENDPOINT DETAILS
    // =====================================================

    private fun showApiUrlsDialog() {

        val deviceIp =
            getDeviceIpAddress()

        val port =
            configManager.serverPort

        val apiKey =
            configManager.apiKey

        val baseUrl =
            "http://$deviceIp:$port"

        val directBackend =
            if (
                configManager.isWebSocketEnabled
            ) {
                "Enabled"
            } else {
                "Disabled"
            }

        val message =
            """
            SMS Gateway API Endpoints

            Base URL: $baseUrl
            API Key: $apiKey

            Available Endpoints:

            1. Send SMS:
            POST $baseUrl/api/send
            Headers: X-API-Key: $apiKey
            Body: {"phone_number":"+1234567890","message":"Hello!"}

            2. Check SMS Status:
            GET $baseUrl/api/status?sms_id=123
            Headers: X-API-Key: $apiKey

            3. Get SMS History:
            GET $baseUrl/api/history?limit=10
            Headers: X-API-Key: $apiKey

            4. Server Info:
            GET $baseUrl/api/info

            5. Send Bulk SMS:
            POST $baseUrl/api/send-bulk
            Headers: X-API-Key: $apiKey

            Direct OTP Backend: $directBackend
            """.trimIndent()

        AlertDialog
            .Builder(this)
            .setTitle(
                "API Endpoints"
            )
            .setMessage(
                message
            )
            .setPositiveButton(
                "Copy Base URL"
            ) { _, _ ->

                copyToClipboard(
                    "Base URL",
                    baseUrl
                )
            }
            .setNeutralButton(
                "Copy API Key"
            ) { _, _ ->

                copyToClipboard(
                    "API Key",
                    apiKey
                )
            }
            .setNegativeButton(
                "Close",
                null
            )
            .show()
    }

    // =====================================================
    // TEST API
    // =====================================================

    private fun showTestApiDialog() {

        if (
            !configManager.isServerEnabled
        ) {

            Toast.makeText(
                this,
                "Please enable the local API server first",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        val dialogView =
            layoutInflater.inflate(
                R.layout.dialog_test_api,
                null
            )

        val phoneEditText =
            dialogView
                .findViewById<EditText>(
                    R.id.testPhoneEditText
                )

        val messageEditText =
            dialogView
                .findViewById<EditText>(
                    R.id.testMessageEditText
                )

        phoneEditText.setText(
            "+1234567890"
        )

        messageEditText.setText(
            "Test message from SMS Gateway API"
        )

        AlertDialog
            .Builder(this)
            .setTitle(
                "Test API - Send SMS"
            )
            .setView(
                dialogView
            )
            .setPositiveButton(
                "Send Test SMS"
            ) { _, _ ->

                val phone =
                    phoneEditText
                        .text
                        .toString()
                        .trim()

                val message =
                    messageEditText
                        .text
                        .toString()
                        .trim()

                if (
                    phone.isBlank() ||
                    message.isBlank()
                ) {

                    Toast.makeText(
                        this,
                        "Please fill in all fields",
                        Toast.LENGTH_SHORT
                    ).show()

                    return@setPositiveButton
                }

                testApiSendSms(
                    phone,
                    message
                )
            }
            .setNeutralButton(
                "Test Server Info"
            ) { _, _ ->

                testApiServerInfo()
            }
            .setNegativeButton(
                "Close",
                null
            )
            .show()
    }

    private fun testApiSendSms(
        phoneNumber: String,
        message: String
    ) {

        lifecycleScope.launch {

            try {

                val result =
                    smsManager.sendSms(
                        phoneNumber,
                        message,
                        configManager.apiKey,
                        "test-${System.currentTimeMillis()}"
                    )

                if (
                    result.isSuccess
                ) {

                    val smsId =
                        result.getOrThrow()

                    showTestResult(
                        "SMS Test Successful",
                        """
                        SMS queued for sending!

                        SMS ID: $smsId
                        Phone: $phoneNumber
                        Message: $message

                        Check SMS History for delivery status.
                        """.trimIndent()
                    )

                } else {

                    val error =
                        result.exceptionOrNull()

                    showTestResult(
                        "SMS Test Failed",
                        "Error: ${error?.message}"
                    )
                }

            } catch (e: Exception) {

                showTestResult(
                    "SMS Test Failed",
                    "Exception: ${e.message}"
                )
            }
        }
    }

    private fun testApiServerInfo() {

        val deviceIp =
            getDeviceIpAddress()

        val port =
            configManager.serverPort

        val infoUrl =
            "http://$deviceIp:$port/api/info"

        showTestResult(
            "Server Info Test",
            """
            Test this URL in your browser or API client:

            $infoUrl

            This endpoint does not require authentication.

            If the URL returns server information,
            the local API server is working.
            """.trimIndent()
        )
    }

    private fun showTestResult(
        title: String,
        message: String
    ) {

        AlertDialog
            .Builder(this)
            .setTitle(
                title
            )
            .setMessage(
                message
            )
            .setPositiveButton(
                "OK",
                null
            )
            .show()
    }

    // =====================================================
    // DIRECT BACKEND / WEBSOCKET
    // =====================================================

    private fun updateDirectBackendButton() {

        externalDomainButton.text =
            if (
                configManager.isWebSocketEnabled &&
                configManager.hasValidWebSocketConfig()
            ) {

                "Direct Backend: Enabled"

            } else {

                "Setup Direct Backend"
            }

        updateServerStatus()
        updateApiUrls()
    }

    private fun showDirectBackendDialog() {

        val density =
            resources.displayMetrics.density

        val padding =
            (20 * density)
                .toInt()

        val smallPadding =
            (8 * density)
                .toInt()

        val container =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    padding,
                    padding,
                    padding,
                    smallPadding
                )
            }

        val enableSwitch =
            SwitchMaterial(this).apply {

                text =
                    "Enable Direct Backend Connection"

                isChecked =
                    configManager
                        .isWebSocketEnabled
            }

        val urlLabel =
            TextView(this).apply {

                text =
                    "WebSocket Backend URL"

                setPadding(
                    0,
                    padding,
                    0,
                    smallPadding
                )
            }

        val urlInput =
            EditText(this).apply {

                hint =
                    DEFAULT_WEBSOCKET_URL

                setSingleLine(
                    true
                )

                val savedUrl =
                    configManager
                        .webSocketUrl

                setText(
                    if (
                        savedUrl.isNotBlank()
                    ) {
                        savedUrl
                    } else {
                        DEFAULT_WEBSOCKET_URL
                    }
                )
            }

        val tokenLabel =
            TextView(this).apply {

                text =
                    "Gateway Token"

                setPadding(
                    0,
                    padding,
                    0,
                    smallPadding
                )
            }

        val tokenInput =
            EditText(this).apply {

                hint =
                    "Enter the GATEWAY_TOKEN from Deplexo"

                setSingleLine(
                    true
                )

                inputType =
                    InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_PASSWORD

                setText(
                    configManager
                        .gatewayToken
                )
            }

        val infoText =
            TextView(this).apply {

                text =
                    """
                    Direct Backend connects this Android phone directly to the OTP server.

                    When enabled, Termux and localhost.run are not required.

                    Keep this phone connected to the internet and allow SMS Gateway to run in the background.
                    """.trimIndent()

                setPadding(
                    0,
                    padding,
                    0,
                    0
                )
            }

        container.addView(
            enableSwitch
        )

        container.addView(
            urlLabel
        )

        container.addView(
            urlInput
        )

        container.addView(
            tokenLabel
        )

        container.addView(
            tokenInput
        )

        container.addView(
            infoText
        )

        val dialog =
            AlertDialog
                .Builder(this)
                .setTitle(
                    "Direct Backend Connection"
                )
                .setView(
                    container
                )
                .setPositiveButton(
                    "Save",
                    null
                )
                .setNegativeButton(
                    "Cancel",
                    null
                )
                .create()

        dialog.setOnShowListener {

            dialog
                .getButton(
                    AlertDialog.BUTTON_POSITIVE
                )
                .setOnClickListener {

                    val enabled =
                        enableSwitch
                            .isChecked

                    val url =
                        urlInput
                            .text
                            .toString()
                            .trim()

                    val token =
                        tokenInput
                            .text
                            .toString()
                            .trim()

                    if (
                        enabled
                    ) {

                        if (
                            !url.startsWith(
                                "wss://"
                            ) &&
                            !url.startsWith(
                                "ws://"
                            )
                        ) {

                            Toast.makeText(
                                this,
                                "WebSocket URL must start with wss://",
                                Toast.LENGTH_LONG
                            ).show()

                            return@setOnClickListener
                        }

                        if (
                            token.isBlank()
                        ) {

                            Toast.makeText(
                                this,
                                "Gateway Token is required",
                                Toast.LENGTH_LONG
                            ).show()

                            return@setOnClickListener
                        }

                        if (
                            !PermissionHelper
                                .hasSmsPermissions(
                                    this
                                )
                        ) {

                            Toast.makeText(
                                this,
                                "SMS permission must be granted before enabling Direct Backend",
                                Toast.LENGTH_LONG
                            ).show()

                            PermissionHelper
                                .requestSmsPermissions(
                                    this
                                )

                            return@setOnClickListener
                        }
                    }

                    configManager.webSocketUrl =
                        url

                    configManager.gatewayToken =
                        token

                    configManager.isWebSocketEnabled =
                        enabled

                    restartGatewayService()

                    updateDirectBackendButton()

                    val toastMessage =
                        if (
                            enabled
                        ) {

                            "Direct backend enabled. Connecting..."

                        } else {

                            "Direct backend disabled"
                        }

                    Toast.makeText(
                        this,
                        toastMessage,
                        Toast.LENGTH_SHORT
                    ).show()

                    dialog.dismiss()
                }
        }

        dialog.show()
    }

    // =====================================================
    // SERVICE CONTROL
    // =====================================================

    private fun startGatewayIfNeeded() {

        if (
            configManager.isServerEnabled ||
            (
                configManager.isWebSocketEnabled &&
                configManager.hasValidWebSocketConfig()
            )
        ) {

            SmsGatewayService
                .startService(this)
        }
    }

    private fun restartGatewayService() {

        /*
         * Stop the existing instance first so new
         * SharedPreferences/WebSocket configuration
         * is loaded by the service.
         */
        SmsGatewayService
            .stopService(this)

        if (
            configManager.isServerEnabled ||
            (
                configManager.isWebSocketEnabled &&
                configManager.hasValidWebSocketConfig()
            )
        ) {

            SmsGatewayService
                .startService(this)
        }
    }

    // =====================================================
    // CLIPBOARD
    // =====================================================

    private fun copyToClipboard(
        label: String,
        text: String
    ) {

        val clipboard =
            getSystemService(
                Context.CLIPBOARD_SERVICE
            ) as android.content.ClipboardManager

        val clip =
            android.content.ClipData
                .newPlainText(
                    label,
                    text
                )

        clipboard.setPrimaryClip(
            clip
        )

        Toast.makeText(
            this,
            "$label copied to clipboard",
            Toast.LENGTH_SHORT
        ).show()
    }

    // =====================================================
    // LEGACY EXTERNAL DOMAIN GUIDE
    // =====================================================

    /*
     * Kept only for compatibility/reference.
     * Direct Backend is now the recommended method.
     */
    private fun showExternalDomainSetupGuide() {

        val deviceIp =
            getDeviceIpAddress()

        val port =
            configManager.serverPort

        val guide =
            """
            Legacy External Domain Setup

            Android device:
            $deviceIp:$port

            The app now supports Direct Backend Connection.

            Recommended:
            Use Direct Backend instead of exposing port 8080 publicly.

            Direct Backend:
            Deplexo OTP Backend
                    ↕
            Secure WebSocket
                    ↕
            Android SMS Gateway
                    ↓
                  SIM/SMS

            This removes the need for Termux and localhost.run.
            """.trimIndent()

        AlertDialog
            .Builder(this)
            .setTitle(
                "Connection Guide"
            )
            .setMessage(
                guide
            )
            .setPositiveButton(
                "OK",
                null
            )
            .show()
    }
}
