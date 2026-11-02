package com.example.hoochwindgrassodb2

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.window.OnBackInvokedDispatcher
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.io.IOException
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * HoochWindgrass ODB2 - a simple ELM327-style OBD-II reader.
 *
 * Bluetooth Classic SPP only (most inexpensive ELM327 adapters). The dashboard shows two
 * separate health indicators: the Bluetooth link to the adapter, and whether the vehicle
 * is actually answering on the selected protocol.
 */
@SuppressLint("MissingPermission")
class MainActivity : Activity() {
    private enum class Screen { STARTUP, PICKER, DASHBOARD, PROTOCOL, SETTINGS, DISPLAY, GAUGES, FAULTS }
    private enum class AdapterState { DISCONNECTED, CONNECTING, CONNECTED, LOST }
    private enum class VehicleState { UNKNOWN, RESPONDING, NO_RESPONSE }
    private data class TestResult(val ok: Boolean, val message: String)

    private val bluetoothAdapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }
    private val preferences by lazy { getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private val link = ObdLink { direction, message -> log(direction, message) }

    private var socket: BluetoothSocket? = null
    private var selectedDevice: BluetoothDevice? = null
    private var selectedProtocol: ObdProtocol = ObdProtocols.AUTOMATIC
    private var customProtocol: ObdProtocol? = null
    private var pendingPermissionAction: (() -> Unit)? = null
    private val discoveredDevices = mutableListOf<BluetoothDevice>()

    private var screen = Screen.STARTUP
    private var adapterState = AdapterState.DISCONNECTED
    private var vehicleState = VehicleState.UNKNOWN
    private var adapterName: String? = null
    private var adapterReports: String? = null
    private var vin: String? = null
    private var vinAttempts = 0
    private var lastError: String? = null
    private var lastVehicleOkNanos: Long? = null
    private var lastBackAt = 0L
    private var feedVisible = false
    private var darkMode = false
    @Volatile private var selectedMetrics: List<ObdMetric> = ObdMetrics.parseIds(null)
    private var settingsReturnsToDashboard = false
    private var metricsList: LinearLayout? = null
    private var metricsCounter: TextView? = null

    private val COLOR_BACKGROUND get() = if (darkMode) Color.rgb(10, 15, 22) else Color.rgb(243, 246, 249)
    private val COLOR_PANEL get() = if (darkMode) Color.rgb(21, 30, 41) else Color.WHITE
    private val COLOR_BORDER get() = if (darkMode) Color.rgb(40, 53, 68) else Color.rgb(224, 231, 239)
    private val COLOR_ACCENT get() = if (darkMode) Color.rgb(45, 200, 185) else Color.rgb(8, 127, 120)
    private val COLOR_TEXT get() = if (darkMode) Color.rgb(232, 238, 245) else Color.rgb(20, 32, 51)
    private val COLOR_MUTED get() = if (darkMode) Color.rgb(143, 158, 178) else Color.rgb(99, 117, 139)
    private val COLOR_SUCCESS get() = if (darkMode) Color.rgb(52, 199, 130) else Color.rgb(22, 131, 93)
    private val COLOR_WARNING get() = if (darkMode) Color.rgb(245, 158, 11) else Color.rgb(217, 119, 6)
    private val COLOR_ERROR get() = if (darkMode) Color.rgb(239, 83, 80) else Color.rgb(200, 50, 50)
    private val pollGeneration = AtomicInteger(0)
    private val connecting = AtomicBoolean(false)
    private val protocolBusy = AtomicBoolean(false)
    private val scanCancelled = AtomicBoolean(false)

    private val feedLines = ArrayDeque<String>()
    private var feedPending = false
    private var feedView: TextView? = null

    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var deviceList: ListView
    private lateinit var deviceAdapter: ArrayAdapter<String>
    private val metricValues = mutableMapOf<String, TextView>()
    private val gaugeViews = mutableMapOf<String, GaugeView>()
    private val peakViews = mutableMapOf<String, TextView>()
    private val peaks = mutableMapOf<String, Pair<Double, String>>()
    private var previewPeak = 0.0

    private fun peakOn(style: GaugeStyle) = preferences.getBoolean(PEAK_PREFIX + style.name, false)
    private var gaugeStyle = GaugeStyle.PLAIN
    private var startInDisplay = false
    private var launchPending = true
    private var previewStep = 0
    private val previewTicker = object : Runnable {
        override fun run() {
            if (screen != Screen.GAUGES) return
            val value = PREVIEW_RPM[previewStep++ % PREVIEW_RPM.size]
            previewPeak = maxOf(previewPeak, value)
            gaugeViews.forEach { (key, gauge) ->
                gauge.setReading(value, value.toLong().toString())
                gauge.setPeak(if (peakOn(GaugeStyle.fromName(key))) previewPeak else null)
            }
            peakViews.values.forEach { it.text = "Peak ${previewPeak.toLong()}" }
            handler.postDelayed(this, 1600L)
        }
    }
    private var adapterLine: TextView? = null
    private var vehicleLine: TextView? = null
    private var protocolLine: TextView? = null
    private var resultTitle: TextView? = null
    private var resultBody: TextView? = null
    private var resultProgress: ProgressBar? = null
    private var scanButton: MaterialButton? = null
    private var testButton: MaterialButton? = null
    private var protocolCards: LinearLayout? = null

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_FOUND) return
            val device = intent.getParcelableExtraCompat<BluetoothDevice>(
                BluetoothDevice.EXTRA_DEVICE
            ) ?: return
            if (discoveredDevices.none { it.address == device.address }) {
                discoveredDevices += device
                deviceAdapter.add(device.displayName())
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (screen != Screen.DASHBOARD && screen != Screen.DISPLAY) return
            refreshStatus()
            val device = selectedDevice
            if (adapterState == AdapterState.LOST && device != null && hasBluetoothPermission()) {
                connectToAdapter(device)
            }
            handler.postDelayed(this, STATUS_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = APP_NAME
        darkMode = preferences.getBoolean(DARK_MODE, false)
        gaugeStyle = GaugeStyle.fromName(preferences.getString(GAUGE_STYLE, null))
        startInDisplay = preferences.getBoolean(START_IN_DISPLAY, false)
        selectedMetrics = ObdMetrics.parseIds(preferences.getString(SELECTED_METRICS, null))
        applySystemBars()
        loadProtocolPreferences()
        registerReceiverCompat(discoveryReceiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { handleBack() }
        }
        showStartupScreen(autoConnect = true)
    }

    // Only reached below API 33; newer versions use the OnBackInvokedCallback registered in onCreate.
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Replaced by OnBackInvokedCallback on Android 13+")
    override fun onBackPressed() {
        handleBack()
    }

    private fun handleBack() {
        when (screen) {
            Screen.PICKER -> showStartupScreen(autoConnect = false)
            Screen.PROTOCOL -> leaveProtocolScreen()
            Screen.FAULTS -> showSettingsScreen(settingsReturnsToDashboard)
            Screen.SETTINGS -> leaveSettings()
            Screen.GAUGES -> {
                handler.removeCallbacks(previewTicker)
                showSettingsScreen(settingsReturnsToDashboard)
            }
            Screen.DISPLAY -> showDashboard()
            else -> {
                val now = SystemClock.elapsedRealtime()
                if (now - lastBackAt <= BACK_EXIT_WINDOW_MS) {
                    exitApp()
                } else {
                    lastBackAt = now
                    Toast.makeText(this, "Press back again to exit", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun exitApp() {
        pollGeneration.incrementAndGet()
        scanCancelled.set(true)
        link.requestAbort()
        handler.removeCallbacksAndMessages(null)
        link.close()
        finish()
    }

    // ---------------------------------------------------------------- startup / picker

    private fun addHeader(
        kicker: String,
        title: String,
        titleSize: Float,
        titleTop: Int,
        titleBottom: Int,
        fromDashboard: Boolean
    ) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        addText(texts, kicker, 13f, COLOR_ACCENT, bold = true)
        addText(texts, title, titleSize, COLOR_TEXT, bold = true, top = titleTop)
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings)
            imageTintList = ColorStateList.valueOf(COLOR_TEXT)
            background = roundedBackground(COLOR_PANEL, COLOR_BORDER, 22)
            contentDescription = "Settings"
            setOnClickListener { showSettingsScreen(fromDashboard) }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(row, marginParams(bottom = titleBottom))
    }

    private fun showStartupScreen(autoConnect: Boolean) {
        screen = Screen.STARTUP
        pollGeneration.incrementAndGet()
        feedView = null
        root = baseLayout()
        val savedAddress = preferences.getString(SAVED_DEVICE_ADDRESS, null)
        addHeader("HOOCH'S DASH  /  OBD-II", "Your car, at a glance.", 30f, 8, 8, fromDashboard = false)
        addText(
            root,
            "A simple live dashboard for your Bluetooth OBD-II adapter.",
            15f,
            COLOR_MUTED,
            bottom = 24
        )
        val connectionCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        addText(connectionCard, "ADAPTER", 12f, COLOR_MUTED, bold = true)
        status = statusView()
        connectionCard.addView(status, marginParams(bottom = 0, top = 8))
        addCard(root, connectionCard)

        if (savedAddress == null) {
            status.text = "No adapter connected"
            addText(
                root,
                "Pair an ELM327-compatible adapter to start reading live vehicle data.",
                15f,
                COLOR_MUTED,
                bottom = 20
            )
            addActionButton(root, "Add an OBD-II adapter", primary = true) { showAdapterPicker() }
        } else {
            val savedName = preferences.getString(SAVED_DEVICE_NAME, null) ?: "Saved Bluetooth adapter"
            status.text = savedName.substringBefore('\n')
            addActionButton(root, "Connect to saved adapter", primary = true) {
                connectSavedAdapter(savedAddress)
            }
            addActionButton(root, "Choose a different adapter", primary = false) { showAdapterPicker() }
            addActionButton(root, "Forget saved adapter", primary = false) {
                preferences.edit().remove(SAVED_DEVICE_ADDRESS).remove(SAVED_DEVICE_NAME).apply()
                showStartupScreen(autoConnect = false)
            }
        }
        addActionButton(root, "Exit", primary = false) { exitApp() }
        showScreen()
        if (savedAddress != null && autoConnect) connectSavedAdapter(savedAddress)
    }

    private fun showAdapterPicker() {
        screen = Screen.PICKER
        root = baseLayout()
        addText(root, "SETUP  /  BLUETOOTH", 13f, COLOR_ACCENT, bold = true)
        addText(root, "Choose your adapter", 28f, COLOR_TEXT, bold = true, top = 8, bottom = 8)
        addText(
            root,
            "Select the Bluetooth dongle plugged into your car's OBD-II port.",
            15f,
            COLOR_MUTED,
            bottom = 18
        )
        status = statusView()
        addCard(root, LinearLayout(this).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(status)
        })
        deviceList = ListView(this)
        deviceAdapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_list_item_1,
            mutableListOf()
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (convertView as? TextView ?: TextView(this@MainActivity)).apply {
                    text = getItem(position)?.replace("\n", "   •   ")
                    textSize = 15f
                    setTextColor(COLOR_TEXT)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                    background = roundedBackground(COLOR_PANEL, COLOR_BORDER, 16)
                }
        }
        deviceList.adapter = deviceAdapter
        deviceList.setBackgroundColor(Color.TRANSPARENT)
        deviceList.divider = ColorDrawable(COLOR_BACKGROUND)
        deviceList.dividerHeight = dp(8)
        root.addView(deviceList, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            bottomMargin = dp(12)
        })
        addActionButton(root, "Search for Bluetooth dongles", primary = true) {
            discoverBluetoothDevices()
        }
        addActionButton(root, "Back", primary = false) { showStartupScreen(autoConnect = false) }
        deviceList.setOnItemClickListener { _, _, position, _ ->
            selectedDevice = discoveredDevices[position]
            saveSelectedAdapter(selectedDevice!!)
        }
        showScreen()
        discoverBluetoothDevices()
    }

    private fun connectSavedAdapter(address: String) {
        if (!hasBluetoothPermission()) {
            requestBluetoothPermissionsIfNeeded { connectSavedAdapter(address) }
            return
        }
        val adapter = bluetoothAdapter
        if (adapter == null) {
            status.text = "This Android device does not support Bluetooth."
            return
        }
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            preferences.edit().remove(SAVED_DEVICE_ADDRESS).apply()
            status.text = "The saved Bluetooth address is invalid."
            return
        }
        selectedDevice = device
        connectToAdapter(device)
    }

    private fun saveSelectedAdapter(device: BluetoothDevice) {
        preferences.edit()
            .putString(SAVED_DEVICE_ADDRESS, device.address)
            .putString(SAVED_DEVICE_NAME, device.displayName())
            .apply()
        connectToAdapter(device)
    }

    private fun discoverBluetoothDevices() {
        if (!hasBluetoothPermission()) {
            requestBluetoothPermissionsIfNeeded { discoverBluetoothDevices() }
            return
        }
        val adapter = bluetoothAdapter
        if (adapter == null) {
            status.text = "This Android device does not support Bluetooth."
            return
        }
        try {
            if (!adapter.isEnabled) {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                return
            }
            discoveredDevices.clear()
            deviceAdapter.clear()
            status.text = "Searching for Bluetooth devices..."
            adapter.cancelDiscovery()
            if (!adapter.startDiscovery()) {
                status.text = "Bluetooth discovery could not be started."
            }
        } catch (_: SecurityException) {
            status.text = "Bluetooth access was denied. Check app permissions in Android Settings."
        }
    }

    // ---------------------------------------------------------------- connection

    private fun connectToAdapter(device: BluetoothDevice) {
        if (!hasBluetoothPermission()) {
            requestBluetoothPermissionsIfNeeded { connectToAdapter(device) }
            return
        }
        if (!connecting.compareAndSet(false, true)) return
        pollGeneration.incrementAndGet()
        adapterState = AdapterState.CONNECTING
        vehicleState = VehicleState.UNKNOWN
        vin = null
        vinAttempts = 0
        if (screen == Screen.STARTUP || screen == Screen.PICKER) {
            status.text = "Connecting to ${device.displayName().substringBefore('\n')}..."
        }
        refreshStatus()
        val protocol = selectedProtocol
        thread(name = "obd-connect") {
            var newSocket: BluetoothSocket? = null
            try {
                bluetoothAdapter?.cancelDiscovery()
                link.close()
                newSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                newSocket.connect()
                socket = newSocket
                link.attach(newSocket, newSocket.inputStream, newSocket.outputStream)
                val reports = link.exclusive {
                    link.clearAbort()
                    initAdapter(protocol)
                    try {
                        link.send("ATDP", ADAPTER_TIMEOUT_MS)
                    } catch (_: IOException) {
                        ""
                    }
                }
                val name = device.name
                runOnUiThread {
                    connecting.set(false)
                    if (isFinishing || isDestroyed) {
                        link.close()
                        return@runOnUiThread
                    }
                    adapterState = AdapterState.CONNECTED
                    adapterName = name
                    adapterReports = reports.ifBlank { null }
                    lastError = null
                    if (screen == Screen.DASHBOARD || screen == Screen.DISPLAY) {
                        startPolling()
                        refreshStatus()
                    } else if (startInDisplay && launchPending) {
                        launchPending = false
                        showDashboard()
                        showDisplayScreen()
                    } else {
                        showDashboard()
                    }
                }
            } catch (error: IOException) {
                failConnection("Connection failed: ${error.message ?: "unknown error"}", newSocket)
            } catch (_: SecurityException) {
                failConnection(
                    "Bluetooth access was denied. Check app permissions in Android Settings.",
                    newSocket
                )
            }
        }
    }

    private fun failConnection(message: String, newSocket: BluetoothSocket?) {
        link.close()
        try {
            newSocket?.close()
        } catch (_: IOException) {
            // Nothing further to release.
        }
        runOnUiThread {
            connecting.set(false)
            adapterState =
                if (screen == Screen.DASHBOARD || screen == Screen.DISPLAY) {
                    AdapterState.LOST
                } else {
                    AdapterState.DISCONNECTED
                }
            lastError = message
            if (screen == Screen.STARTUP || screen == Screen.PICKER) status.text = message
            refreshStatus()
        }
    }

    // Must be called while holding link.exclusive. Returns commands the adapter did not accept.
    private fun initAdapter(protocol: ObdProtocol): List<String> {
        link.send("ATZ", RESET_TIMEOUT_MS)
        link.send("ATE0", ADAPTER_TIMEOUT_MS)
        link.send("ATL0", ADAPTER_TIMEOUT_MS)
        val rejected = mutableListOf<String>()
        for (command in protocol.commands) {
            val reply = link.send(command, ADAPTER_TIMEOUT_MS)
            if (reply.trim() == "?") {
                // Clone adapters often lack the rarer commands; only the protocol selection is essential.
                if (command.startsWith("ATSP")) throw IOException("Adapter rejected $command")
                rejected += command
            }
        }
        return rejected
    }

    private fun disconnect() {
        pollGeneration.incrementAndGet()
        link.close()
        socket = null
        adapterState = AdapterState.DISCONNECTED
        vehicleState = VehicleState.UNKNOWN
        showStartupScreen(autoConnect = false)
    }

    private fun markAdapterLost(message: String) {
        link.close()
        adapterState = AdapterState.LOST
        vehicleState = VehicleState.UNKNOWN
        lastError = message
        (metricValues.keys + gaugeViews.keys).toList().forEach { updateMetric(it, null, "") }
        refreshStatus()
    }

    private fun showDashboard() {
        screen = Screen.DASHBOARD
        root = baseLayout()
        metricValues.clear()
        gaugeViews.clear()
        peakViews.clear()
        addHeader("LIVE VEHICLE DATA", "Vehicle dashboard", 28f, 8, 16, fromDashboard = true)
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        addText(details, "ADAPTER (BLUETOOTH)", 11f, COLOR_MUTED, bold = true)
        adapterLine = addText(details, "", 15f, COLOR_TEXT, bold = true, top = 4, bottom = 12)
        addText(details, "VEHICLE (PROTOCOL)", 11f, COLOR_MUTED, bold = true)
        vehicleLine = addText(details, "", 15f, COLOR_TEXT, bold = true, top = 4, bottom = 12)
        protocolLine = addText(details, "", 13f, COLOR_MUTED)
        addCard(root, details)

        addActionButton(root, "Full-screen dashboard", primary = true) { showDisplayScreen() }
        addText(root, "LIVE READINGS", 12f, COLOR_MUTED, bold = true, top = 8, bottom = 10)
        val metrics = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        selectedMetrics.chunked(2).forEach { addMetricRow(metrics, it) }
        root.addView(metrics)

        val feedToggle = addActionButton(root, "", primary = false) {}
        val feedCard = createFeedCard()
        feedCard.visibility = if (feedVisible) View.VISIBLE else View.GONE
        fun updateToggleText() {
            feedToggle.text = if (feedVisible) "Hide live data feed" else "Show live data feed"
        }
        updateToggleText()
        feedToggle.setOnClickListener {
            feedVisible = !feedVisible
            feedCard.visibility = if (feedVisible) View.VISIBLE else View.GONE
            updateToggleText()
            renderFeed()
        }
        root.addView(feedCard, marginParams(bottom = 12))

        addActionButton(root, "Disconnect", primary = false) { disconnect() }
        addActionButton(root, "Exit app", primary = false) { exitApp() }
        showScreen()
        refreshStatus()
        if (adapterState == AdapterState.CONNECTED) startPolling()
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, STATUS_REFRESH_MS)
    }

    private fun startPolling() {
        val generation = pollGeneration.incrementAndGet()
        thread(name = "obd-poll") {
            while (generation == pollGeneration.get()) {
                try {
                    link.exclusive {
                        link.clearAbort()
                        if (generation == pollGeneration.get()) pollOnce(generation)
                    }
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (_: ObdLink.AbortedException) {
                    // Cancelled by a screen change; the generation check ends the loop.
                } catch (error: IOException) {
                    runOnUiThread {
                        if (generation == pollGeneration.get()) {
                            markAdapterLost("Bluetooth link lost: ${error.message ?: "unknown error"}")
                        }
                    }
                    return@thread
                } catch (_: InterruptedException) {
                    return@thread
                }
            }
        }
    }

    // Called with the command lock held. Throws IOException if the adapter itself stops answering.
    private fun pollOnce(generation: Int) {
        val metrics = selectedMetrics
        val volts = if (metrics.any { it.isAdapterVoltage }) {
            ObdParser.voltage(link.send("ATRV", ADAPTER_TIMEOUT_MS))
        } else null
        var vehicleFailed = false
        var responding = false
        val values = HashMap<String, String?>()
        val numbers = HashMap<String, Double?>()
        for (metric in metrics) {
            if (metric.isAdapterVoltage) {
                values[metric.label] = metric.format(volts)
                numbers[metric.label] = volts
                continue
            }
            if (vehicleFailed) {
                values[metric.label] = null
                continue
            }
            val reply = try {
                link.send(metric.id, PID_TIMEOUT_MS).also {
                    if (ObdParser.isLinkFailure(it)) vehicleFailed = true
                }
            } catch (_: ObdLink.TimeoutException) {
                vehicleFailed = true
                ""
            }
            val number = metric.decode(reply)
            val text = metric.format(number)
            values[metric.label] = text
            numbers[metric.label] = number
            if (text != null) responding = true
        }
        if (!responding && !vehicleFailed) {
            // The selected values may simply be unsupported by this car, so ask for the supported-PID list.
            responding = try {
                ObdParser.hasSupportedPids(link.send("0100", PID_TIMEOUT_MS))
            } catch (_: ObdLink.TimeoutException) {
                false
            }
        }
        var newVin: String? = null
        if (responding && vin == null && vinAttempts < MAX_VIN_ATTEMPTS) {
            vinAttempts++
            newVin = try {
                ObdDiagnostics.parseVin(link.send("0902", VIN_TIMEOUT_MS))
            } catch (_: ObdLink.TimeoutException) {
                null
            }
        }
        runOnUiThread {
            if (generation != pollGeneration.get() ||
                (screen != Screen.DASHBOARD && screen != Screen.DISPLAY)
            ) {
                return@runOnUiThread
            }
            if (responding) {
                vehicleState = VehicleState.RESPONDING
                lastVehicleOkNanos = System.nanoTime()
            } else {
                vehicleState = VehicleState.NO_RESPONSE
            }
            for (metric in metrics) {
                updateMetric(metric.label, values[metric.label], metric.unit, numbers[metric.label])
            }
            if (newVin != null) vin = newVin
            refreshStatus()
        }
    }

    private fun refreshStatus() {
        if (screen != Screen.DASHBOARD) return
        val adapterLabel = adapterName ?: "Bluetooth OBD-II adapter"
        val silentMs = link.millisSinceLastReply()
        adapterLine?.text = when (adapterState) {
            AdapterState.CONNECTED ->
                if (silentMs != null && silentMs > STALE_MS) {
                    dotText(COLOR_WARNING, "$adapterLabel - not answering (${silentMs / 1000}s)")
                } else {
                    dotText(COLOR_SUCCESS, "$adapterLabel - connected")
                }
            AdapterState.CONNECTING -> dotText(COLOR_WARNING, "Connecting to $adapterLabel...")
            AdapterState.LOST ->
                dotText(COLOR_ERROR, "Disconnected - reconnecting... ${lastError.orEmpty()}".trim())
            AdapterState.DISCONNECTED -> dotText(COLOR_ERROR, "Not connected")
        }
        val sinceVehicle = lastVehicleOkNanos?.let { (System.nanoTime() - it) / 1_000_000L }
        vehicleLine?.text = when {
            adapterState != AdapterState.CONNECTED -> dotText(COLOR_MUTED, "Unknown (no adapter link)")
            vehicleState == VehicleState.UNKNOWN -> dotText(COLOR_WARNING, "Checking vehicle...")
            vehicleState == VehicleState.NO_RESPONSE -> dotText(
                COLOR_ERROR,
                "No response - ignition off, or wrong protocol"
            )
            sinceVehicle != null && sinceVehicle > STALE_MS ->
                dotText(COLOR_WARNING, "Slow - last reply ${sinceVehicle / 1000}s ago")
            else -> dotText(COLOR_SUCCESS, "Responding")
        }
        protocolLine?.text = buildString {
            append("Protocol  ·  ").append(selectedProtocol.name)
            adapterReports?.let { append("\nAdapter reports  ·  ").append(it) }
            vin?.let { append("\nVIN  ·  ").append(it) }
            if (sinceVehicle != null) append("\nLast vehicle reply  ·  ${sinceVehicle / 1000}s ago")
        }
    }

    // ---------------------------------------------------------------- protocol screen

    private fun showProtocolScreen() {
        screen = Screen.PROTOCOL
        pollGeneration.incrementAndGet()
        handler.removeCallbacks(ticker)
        root = baseLayout()
        addText(root, "SETTINGS  /  PROTOCOL", 13f, COLOR_ACCENT, bold = true)
        addText(root, "Vehicle protocol", 28f, COLOR_TEXT, bold = true, top = 8, bottom = 8)
        addText(
            root,
            "Tap a protocol to select and test it, or scan to find the one your car answers on.",
            15f,
            COLOR_MUTED,
            bottom = 16
        )
        val result = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        resultTitle = addText(result, "Selected: ${selectedProtocol.name}", 15f, COLOR_TEXT, bold = true)
        resultBody = addText(result, "Not tested yet.", 14f, COLOR_MUTED, top = 6)
        resultProgress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        result.addView(resultProgress, marginParams(top = 8))
        addCard(root, result)

        testButton = addActionButton(root, "Test selected protocol", primary = true) { startTest() }
        scanButton = addActionButton(root, "Scan all protocols", primary = false) {
            if (protocolBusy.get()) cancelProtocolJob() else startScan()
        }
        addText(root, "LIVE DATA FEED", 12f, COLOR_MUTED, bold = true, top = 8, bottom = 8)
        root.addView(createFeedCard(), marginParams(bottom = 16))
        addText(root, "PROTOCOLS", 12f, COLOR_MUTED, bold = true, bottom = 10)
        protocolCards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(protocolCards)
        buildProtocolCards()
        addActionButton(root, "Back to settings", primary = false) { leaveProtocolScreen() }
        showScreen()
        setBusyUi(protocolBusy.get())
        renderFeed()
    }

    private fun leaveProtocolScreen() {
        if (protocolBusy.get()) cancelProtocolJob()
        showSettingsScreen(settingsReturnsToDashboard)
    }

    private fun buildProtocolCards() {
        val container = protocolCards ?: return
        container.removeAllViews()
        val entries = ObdProtocols.ALL + (customProtocol ?: ObdProtocols.custom("Custom protocol...", emptyList()))
        for (protocol in entries) {
            val selected = protocol.id == selectedProtocol.id
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            addText(
                content,
                if (selected) "✓  ${protocol.name}" else protocol.name,
                15f,
                if (selected) COLOR_ACCENT else COLOR_TEXT,
                bold = true
            )
            val description = if (protocol.id == ObdProtocols.CUSTOM_ID && protocol.commands.isEmpty()) {
                "Enter your own ELM327 commands (tap to define)"
            } else {
                protocol.description
            }
            addText(content, description, 13f, COLOR_MUTED, top = 4)
            val card = MaterialCardView(this).apply {
                radius = dp(18).toFloat()
                cardElevation = dp(1).toFloat()
                setCardBackgroundColor(ColorStateList.valueOf(COLOR_PANEL))
                setStrokeColor(ColorStateList.valueOf(if (selected) COLOR_ACCENT else COLOR_BORDER))
                strokeWidth = if (selected) dp(2) else dp(1)
                isClickable = true
                isFocusable = true
                addView(content, ViewGroup.LayoutParams(-1, -2))
                setOnClickListener {
                    if (protocol.id == ObdProtocols.CUSTOM_ID) {
                        showCustomProtocolDialog()
                    } else {
                        selectProtocol(protocol)
                    }
                }
            }
            container.addView(card, marginParams(bottom = 10))
        }
    }

    private fun selectProtocol(protocol: ObdProtocol) {
        if (protocolBusy.get()) {
            Toast.makeText(this, "Wait for the current test or scan, or cancel it.", Toast.LENGTH_SHORT).show()
            return
        }
        selectedProtocol = protocol
        preferences.edit().putString(SELECTED_PROTOCOL_ID, protocol.id).apply()
        resultTitle?.text = "Selected: ${protocol.name}"
        buildProtocolCards()
        if (adapterState == AdapterState.CONNECTED) {
            startTest()
        } else {
            showResult("Selected: ${protocol.name}", "Saved. It will be used when the adapter connects.", COLOR_MUTED)
        }
    }

    private fun showCustomProtocolDialog() {
        val name = EditText(this).apply {
            hint = "Protocol name"
            setText(customProtocol?.name.orEmpty())
        }
        val commands = EditText(this).apply {
            hint = "Commands, separated by ; e.g. ATSP4; ATIB 96"
            setText(customProtocol?.commands?.joinToString("; ").orEmpty())
        }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(name)
            addView(commands)
        }
        AlertDialog.Builder(this)
            .setTitle("Custom protocol")
            .setView(fields)
            .setPositiveButton("Use protocol") { _, _ ->
                val protocolName = name.text.toString().trim()
                val parsed = ObdProtocols.parseCommands(commands.text.toString())
                if (protocolName.isEmpty() || parsed.isEmpty()) {
                    Toast.makeText(this, "A name and at least one command are required.", Toast.LENGTH_SHORT).show()
                } else {
                    val protocol = ObdProtocols.custom(protocolName, parsed)
                    customProtocol = protocol
                    preferences.edit()
                        .putString(CUSTOM_NAME, protocolName)
                        .putString(CUSTOM_COMMANDS, parsed.joinToString(";"))
                        .apply()
                    selectProtocol(protocol)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadProtocolPreferences() {
        val customName = preferences.getString(CUSTOM_NAME, null)
        val customCommands = preferences.getString(CUSTOM_COMMANDS, null)
        if (customName != null && customCommands != null) {
            val parsed = ObdProtocols.parseCommands(customCommands)
            if (parsed.isNotEmpty()) customProtocol = ObdProtocols.custom(customName, parsed)
        }
        val id = preferences.getString(SELECTED_PROTOCOL_ID, null)
        selectedProtocol = if (id == ObdProtocols.CUSTOM_ID) {
            customProtocol ?: ObdProtocols.AUTOMATIC
        } else {
            ObdProtocols.find(id) ?: ObdProtocols.AUTOMATIC
        }
    }

    private fun startTest() {
        val protocol = selectedProtocol
        runProtocolJob {
            showResult("Testing ${protocol.name}", "Initialising and asking the vehicle for data...", COLOR_WARNING)
            val result = testProtocol(protocol, TEST_TIMEOUT_MS)
            recordVehicleResult(result.ok)
            showResult(
                if (result.ok) "Protocol OK" else "No response on ${protocol.name}",
                result.message,
                if (result.ok) COLOR_SUCCESS else COLOR_ERROR
            )
        }
    }

    private fun startScan() {
        val candidates = listOf(ObdProtocols.AUTOMATIC) + ObdProtocols.ALL.filter { it.scannable }
        runProtocolJob {
            var found: ObdProtocol? = null
            var foundMessage = ""
            for ((index, protocol) in candidates.withIndex()) {
                if (scanCancelled.get()) throw ObdLink.AbortedException()
                showResult(
                    "Scanning ${index + 1} of ${candidates.size}",
                    "Trying ${protocol.name}...",
                    COLOR_WARNING
                )
                val timeout = if (protocol.id == ObdProtocols.AUTOMATIC.id) AUTO_SCAN_TIMEOUT_MS else SCAN_TIMEOUT_MS
                val result = testProtocol(protocol, timeout)
                if (result.ok) {
                    found = protocol
                    foundMessage = result.message
                    break
                }
            }
            if (found != null) {
                val chosen = found
                runOnUiThread {
                    selectedProtocol = chosen
                    preferences.edit().putString(SELECTED_PROTOCOL_ID, chosen.id).apply()
                    buildProtocolCards()
                }
                recordVehicleResult(true)
                showResult("Found: ${chosen.name}", foundMessage, COLOR_SUCCESS)
            } else {
                recordVehicleResult(false)
                showResult(
                    "No protocol responded",
                    "Make sure the ignition is ON and the adapter is firmly plugged in, then scan again.",
                    COLOR_ERROR
                )
                restoreSelectedProtocol()
            }
        }
    }

    private fun cancelProtocolJob() {
        scanCancelled.set(true)
        link.requestAbort()
        showResult("Cancelling...", "Stopping after the current step.", COLOR_WARNING)
    }

    private fun runProtocolJob(job: () -> Unit) {
        if (adapterState != AdapterState.CONNECTED) {
            showResult("Adapter not connected", "Reconnect the adapter first.", COLOR_ERROR)
            return
        }
        if (!protocolBusy.compareAndSet(false, true)) return
        scanCancelled.set(false)
        setBusyUi(true)
        thread(name = "obd-protocol") {
            try {
                link.exclusive {
                    link.clearAbort()
                    job()
                }
            } catch (_: ObdLink.AbortedException) {
                showResult("Cancelled", "Restored ${selectedProtocol.name}.", COLOR_MUTED)
                restoreSelectedProtocol()
            } catch (error: IOException) {
                runOnUiThread {
                    markAdapterLost("Bluetooth link lost: ${error.message ?: "unknown error"}")
                }
                showResult("Adapter link failed", error.message ?: "unknown error", COLOR_ERROR)
            } finally {
                link.clearAbort()
                protocolBusy.set(false)
                runOnUiThread { setBusyUi(false) }
            }
        }
    }

    private fun restoreSelectedProtocol() {
        try {
            link.exclusive {
                link.clearAbort()
                initAdapter(selectedProtocol)
            }
        } catch (_: IOException) {
            // A failed restore surfaces on the next test or poll.
        }
    }

    // Called with the command lock held.
    private fun testProtocol(protocol: ObdProtocol, timeoutMs: Long): TestResult {
        val rejected = initAdapter(protocol)
        val note = if (rejected.isEmpty()) "" else "\nAdapter ignored: ${rejected.joinToString(", ")}"
        var ok = false
        var failure = "No reply from the vehicle"
        try {
            val reply = link.send("0100", timeoutMs)
            ok = ObdParser.hasSupportedPids(reply)
            failure = ObdParser.describeFailure(reply)
            if (!ok && !ObdParser.isLinkFailure(reply)) {
                for (command in LIVENESS_PIDS) {
                    val pidReply = link.send(command, PID_TIMEOUT_MS)
                    if (ObdParser.hexBytesAfter(pidReply, "41" + command.drop(2)) != null) {
                        ok = true
                        break
                    }
                }
            }
        } catch (_: ObdLink.TimeoutException) {
            failure = "No reply within ${timeoutMs / 1000}s"
        }
        if (!ok) return TestResult(false, failure + note)
        val reports = try {
            link.send("ATDP", ADAPTER_TIMEOUT_MS)
        } catch (_: ObdLink.TimeoutException) {
            ""
        }
        runOnUiThread { adapterReports = reports.ifBlank { null } }
        val detail = if (reports.isBlank()) "" else "\nAdapter reports: $reports"
        return TestResult(true, "The vehicle answered on ${protocol.name}.$detail$note")
    }

    private fun recordVehicleResult(ok: Boolean) {
        runOnUiThread {
            vehicleState = if (ok) VehicleState.RESPONDING else VehicleState.NO_RESPONSE
            if (ok) lastVehicleOkNanos = System.nanoTime()
        }
    }

    private fun showResult(title: String, body: String, color: Int) {
        runOnUiThread {
            resultTitle?.text = title
            resultTitle?.setTextColor(color)
            resultBody?.text = body
        }
    }

    private fun setBusyUi(busy: Boolean) {
        resultProgress?.visibility = if (busy) View.VISIBLE else View.GONE
        scanButton?.text = if (busy) "Cancel" else "Scan all protocols"
        testButton?.isEnabled = !busy
    }

    // ---------------------------------------------------------------- live feed

    private fun log(direction: String, message: String) {
        val time = LocalTime.now().format(TIME_FORMAT)
        synchronized(feedLines) {
            feedLines.addLast("$time $direction $message")
            while (feedLines.size > FEED_CAPACITY) feedLines.removeFirst()
        }
        handler.post { scheduleFeedRefresh() }
    }

    private fun scheduleFeedRefresh() {
        if (feedPending || feedView == null) return
        feedPending = true
        handler.postDelayed({
            feedPending = false
            renderFeed()
        }, FEED_REFRESH_MS)
    }

    private fun renderFeed() {
        val view = feedView ?: return
        val lines = synchronized(feedLines) { feedLines.takeLast(FEED_VISIBLE_LINES) }
        view.text = if (lines.isEmpty()) "Waiting for traffic..." else lines.joinToString("\n")
    }

    private fun createFeedCard(): MaterialCardView {
        val text = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Color.rgb(205, 224, 220))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            minLines = 4
        }
        feedView = text
        renderFeed()
        return MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(ColorStateList.valueOf(Color.rgb(17, 28, 38)))
            strokeWidth = 0
            addView(text, ViewGroup.LayoutParams(-1, -2))
        }
    }

    // ---------------------------------------------------------------- UI helpers

    // ---------------------------------------------------------------- settings & full-screen display

    private fun showSettingsScreen(fromDashboard: Boolean) {
        settingsReturnsToDashboard = fromDashboard
        screen = Screen.SETTINGS
        pollGeneration.incrementAndGet()
        handler.removeCallbacks(ticker)
        root = baseLayout()
        addText(root, "SETTINGS", 13f, COLOR_ACCENT, bold = true)
        addText(root, "Settings", 28f, COLOR_TEXT, bold = true, top = 8, bottom = 16)

        val appearance = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        appearance.addView(Switch(this).apply {
            text = "Dark mode"
            textSize = 16f
            setTextColor(COLOR_TEXT)
            isChecked = darkMode
            setOnCheckedChangeListener { _, checked ->
                darkMode = checked
                preferences.edit().putBoolean(DARK_MODE, checked).apply()
                applySystemBars()
                showSettingsScreen(settingsReturnsToDashboard)
            }
        })
        appearance.addView(Switch(this).apply {
            text = "Start in full-screen dashboard"
            textSize = 16f
            setTextColor(COLOR_TEXT)
            isChecked = startInDisplay
            setOnCheckedChangeListener { _, checked ->
                startInDisplay = checked
                preferences.edit().putBoolean(START_IN_DISPLAY, checked).apply()
            }
        }, marginParams(top = 12))
        addCard(root, appearance)

        addActionButton(root, "Gauge skins  ›", primary = false) { showGaugeScreen() }
        addActionButton(root, "Faults  ›", primary = false) { showFaultsScreen() }
        addActionButton(root, "Protocol, test & scan  ›", primary = false) { showProtocolScreen() }
        addText(root, "DASHBOARD VALUES", 12f, COLOR_MUTED, bold = true, top = 8, bottom = 6)
        metricsCounter = addText(root, "", 14f, COLOR_MUTED, bottom = 12)
        metricsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(metricsList)
        buildMetricCards()
        addActionButton(root, "Done", primary = true) { leaveSettings() }
        showScreen()
    }

    private fun showGaugeScreen() {
        screen = Screen.GAUGES
        pollGeneration.incrementAndGet()
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(previewTicker)
        metricValues.clear()
        gaugeViews.clear()
        peakViews.clear()
        root = baseLayout()
        addText(root, "GAUGE SKINS", 13f, COLOR_ACCENT, bold = true)
        addText(root, "Gauge skins", 28f, COLOR_TEXT, bold = true, top = 8, bottom = 8)
        addText(root, "Applies to the dashboard and the full-screen display.", 14f, COLOR_MUTED, bottom = 16)

        val rpm = ObdMetrics.find("010C")!!
        GaugeStyle.entries.forEach { style ->
            val selected = style == gaugeStyle
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            if (style == GaugeStyle.PLAIN) {
                row.addView(TextView(this).apply {
                    text = "2740"
                    textSize = 28f
                    gravity = Gravity.CENTER
                    setTextColor(COLOR_TEXT)
                    setTypeface(typeface, Typeface.BOLD)
                }, LinearLayout.LayoutParams(dp(150), dp(150)))
            } else {
                val gauge = newGauge(rpm, style)
                gaugeViews[style.name] = gauge
                gauge.setReading(2740.0, "2740")
                row.addView(gauge, LinearLayout.LayoutParams(dp(150), dp(150)))
            }
            val labels = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
            }
            addText(labels, if (selected) "✓ ${style.title}" else style.title, 17f,
                if (selected) COLOR_ACCENT else COLOR_TEXT, bold = true, bottom = 4)
            addText(labels, style.description, 13f, COLOR_MUTED)
            labels.addView(CheckBox(this).apply {
                text = "Show peak marker"
                textSize = 13f
                setTextColor(COLOR_TEXT)
                isChecked = peakOn(style)
                setOnCheckedChangeListener { _, checked ->
                    preferences.edit().putBoolean(PEAK_PREFIX + style.name, checked).apply()
                    if (style == GaugeStyle.PLAIN) {
                        peakViews.values.forEach { it.visibility = if (checked) View.VISIBLE else View.GONE }
                    } else {
                        gaugeViews[style.name]?.setPeak(if (checked) previewPeak else null)
                    }
                }
            }, marginParams(top = 6))
            if (style == GaugeStyle.PLAIN) {
                peakViews[style.name] = addText(labels, "Peak 2740", 12f, COLOR_WARNING, top = 2).also {
                    it.visibility = if (peakOn(style)) View.VISIBLE else View.GONE
                }
            }
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            addCard(root, row)
            (row.parent as View).setOnClickListener {
                gaugeStyle = style
                preferences.edit().putString(GAUGE_STYLE, style.name).apply()
                showGaugeScreen()
            }
        }
        addActionButton(root, "Reset peaks", primary = false) {
            peaks.clear()
            previewPeak = 0.0
            Toast.makeText(this, "Peaks reset", Toast.LENGTH_SHORT).show()
        }
        addActionButton(root, "Done", primary = true) {
            handler.removeCallbacks(previewTicker)
            showSettingsScreen(settingsReturnsToDashboard)
        }
        showScreen()
        previewStep = 0
        handler.postDelayed(previewTicker, 1600L)
    }

    // ---------------------------------------------------------------- faults screen

    private class FaultReport(
        val error: String? = null,
        val stored: List<String> = emptyList(),
        val pending: List<String> = emptyList(),
        val permanent: List<String> = emptyList(),
        val freezeCode: String? = null,
        val freezeValues: List<Pair<String, String>> = emptyList(),
        val sinceClear: MonitorStatus? = null,
        val thisCycle: MonitorStatus? = null
    )

    private val faultsBusy = AtomicBoolean(false)
    private var faultsStatus: TextView? = null
    private var faultsProgress: ProgressBar? = null
    private var faultsResults: LinearLayout? = null
    private var faultsButtons: List<MaterialButton> = emptyList()

    private fun showFaultsScreen() {
        screen = Screen.FAULTS
        pollGeneration.incrementAndGet()
        handler.removeCallbacks(ticker)
        root = baseLayout()
        addText(root, "SETTINGS  /  FAULTS", 13f, COLOR_ACCENT, bold = true)
        addText(root, "Faults", 28f, COLOR_TEXT, bold = true, top = 8, bottom = 8)
        addText(
            root,
            "Reads the fault codes (DTCs), the freeze frame the ECU saved when a fault happened, " +
                "and the emission monitor status.",
            15f, COLOR_MUTED, bottom = 16
        )
        faultsStatus = addText(root, "", 14f, COLOR_MUTED, bottom = 12)
        faultsProgress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(faultsProgress, marginParams(bottom = 12))
        val read = addActionButton(root, "Read faults, freeze frame & monitors", primary = true) { readFaults() }
        val clear = addActionButton(root, "Clear fault codes", primary = false) { confirmClearFaults() }
        faultsButtons = listOf(read, clear)
        faultsResults = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(faultsResults)
        addActionButton(root, "Back to settings", primary = false) {
            showSettingsScreen(settingsReturnsToDashboard)
        }
        showScreen()
        lastFaultReport?.let { renderFaults(it) }
        readFaults()
    }

    private var lastFaultReport: FaultReport? = null

    private fun setFaultsBusy(busy: Boolean, message: String?) {
        faultsProgress?.visibility = if (busy) View.VISIBLE else View.GONE
        faultsButtons.forEach { it.isEnabled = !busy }
        if (message != null) setFaultsStatus(message, COLOR_MUTED)
    }

    private fun setFaultsStatus(message: String, color: Int) {
        faultsStatus?.apply {
            text = message
            setTextColor(color)
        }
    }

    private fun runFaultJob(message: String, job: () -> Unit) {
        if (adapterState != AdapterState.CONNECTED) {
            setFaultsStatus("Adapter not connected. Connect it first, then try again.", COLOR_ERROR)
            return
        }
        if (!faultsBusy.compareAndSet(false, true)) return
        setFaultsBusy(true, message)
        thread(name = "obd-faults") {
            try {
                link.exclusive {
                    link.clearAbort()
                    job()
                }
            } catch (_: ObdLink.AbortedException) {
                // Cancelled by a screen change.
            } catch (error: IOException) {
                val text = "Bluetooth link lost: ${error.message ?: "unknown error"}"
                runOnUiThread {
                    markAdapterLost(text)
                    if (screen == Screen.FAULTS) setFaultsStatus(text, COLOR_ERROR)
                }
            } finally {
                faultsBusy.set(false)
                runOnUiThread { setFaultsBusy(false, null) }
            }
        }
    }

    private fun readFaults() {
        runFaultJob("Reading the ECU...") {
            val report = collectFaults()
            runOnUiThread {
                lastFaultReport = report
                if (screen == Screen.FAULTS) renderFaults(report)
            }
        }
    }

    // Called with the command lock held.
    private fun collectFaults(): FaultReport {
        fun ask(command: String) = try {
            link.send(command, PID_TIMEOUT_MS)
        } catch (_: ObdLink.TimeoutException) {
            ""
        }
        val storedReply = ask("03")
        if (storedReply.isBlank() || ObdParser.isLinkFailure(storedReply)) {
            return FaultReport(error = ObdParser.describeFailure(storedReply) + " (is the ignition on?)")
        }
        val pending = ObdDiagnostics.parseDtcs(ask("07"), ObdDiagnostics.MODE_PENDING)
        val permanent = ObdDiagnostics.parseDtcs(ask("0A"), ObdDiagnostics.MODE_PERMANENT)

        val freezeReply = ask("0202")
        val freezeCode = ObdDiagnostics.freezeFrameDtc(freezeReply)
        val freezeValues = mutableListOf<Pair<String, String>>()
        if (freezeCode != null) {
            for (id in ObdDiagnostics.FREEZE_FRAME_IDS) {
                val metric = ObdMetrics.find(id) ?: continue
                val value = ObdDiagnostics.freezeFrameValue(metric, ask("02" + id.drop(2) + "00")) ?: continue
                freezeValues.add(metric.label to "$value ${metric.unit}".trim())
            }
        }
        return FaultReport(
            stored = ObdDiagnostics.parseDtcs(storedReply, ObdDiagnostics.MODE_STORED),
            pending = pending,
            permanent = permanent,
            freezeCode = freezeCode,
            freezeValues = freezeValues,
            sinceClear = ObdDiagnostics.monitorStatus(ask("0101"), "0101"),
            thisCycle = ObdDiagnostics.monitorStatus(ask("0141"), "0141")
        )
    }

    private fun confirmClearFaults() {
        AlertDialog.Builder(this)
            .setTitle("Clear fault codes?")
            .setMessage(
                "This erases the stored fault codes and freeze frame, turns off the check-engine light " +
                    "and resets the emission monitors. If the fault is still there it will come back.\n\n" +
                    "Best done with the ignition on and the engine off."
            )
            .setPositiveButton("Clear") { _, _ -> clearFaults() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearFaults() {
        runFaultJob("Clearing fault codes...") {
            val reply = try {
                link.send("04", PID_TIMEOUT_MS)
            } catch (_: ObdLink.TimeoutException) {
                ""
            }
            val cleared = ObdDiagnostics.messages(reply).any { it.firstOrNull() == 0x44 }
            val report = if (cleared) collectFaults() else null
            runOnUiThread {
                if (report != null) lastFaultReport = report
                if (screen == Screen.FAULTS) {
                    if (report != null) renderFaults(report)
                    setFaultsStatus(
                        if (cleared) "Fault codes cleared." else "The ECU did not accept the clear request.",
                        if (cleared) COLOR_SUCCESS else COLOR_ERROR
                    )
                }
            }
        }
    }

    private fun renderFaults(report: FaultReport) {
        val results = faultsResults ?: return
        results.removeAllViews()
        if (report.error != null) {
            setFaultsStatus(report.error, COLOR_ERROR)
            return
        }
        setFaultsStatus(
            if (report.stored.isEmpty()) "No stored fault codes." else "${report.stored.size} stored fault code(s).",
            if (report.stored.isEmpty()) COLOR_SUCCESS else COLOR_WARNING
        )
        faultCard(results, "STORED FAULT CODES", report.stored, "No stored fault codes")
        faultCard(results, "PENDING CODES (not confirmed yet)", report.pending, "No pending codes")
        if (report.permanent.isNotEmpty()) faultCard(results, "PERMANENT CODES", report.permanent, "")

        val freeze = sectionCard(results, "FREEZE FRAME")
        if (report.freezeCode == null) {
            addText(freeze, "No freeze frame recorded", 15f, COLOR_SUCCESS)
        } else {
            addText(freeze, "Recorded for ${report.freezeCode}", 15f, COLOR_TEXT, bold = true, bottom = 6)
            if (report.freezeValues.isEmpty()) addText(freeze, "No values reported", 14f, COLOR_MUTED)
            report.freezeValues.forEach { (label, value) -> valueRow(freeze, label, value, COLOR_TEXT) }
        }
        monitorCard(results, "MONITOR STATUS SINCE CODES CLEARED", report.sinceClear)
        monitorCard(results, "CURRENT DRIVE CYCLE", report.thisCycle)
    }

    private fun sectionCard(container: LinearLayout, title: String): LinearLayout {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        addText(content, title, 12f, COLOR_MUTED, bold = true, bottom = 8)
        addCard(container, content)
        return content
    }

    private fun faultCard(container: LinearLayout, title: String, codes: List<String>, emptyText: String) {
        val card = sectionCard(container, title)
        if (codes.isEmpty()) addText(card, emptyText, 15f, COLOR_SUCCESS)
        codes.forEach { code ->
            addText(card, "$code  ·  ${ObdDiagnostics.category(code)}", 17f, COLOR_ERROR, bold = true, top = 4)
            addText(
                card,
                ObdDiagnostics.describe(code) ?: "Look this code up for your vehicle's make and model",
                13f, COLOR_MUTED
            )
        }
    }

    private fun monitorCard(container: LinearLayout, title: String, status: MonitorStatus?) {
        val card = sectionCard(container, title)
        if (status == null) {
            addText(card, "Not supported by this vehicle", 15f, COLOR_MUTED)
            return
        }
        valueRow(card, "Check-engine light (MIL)", if (status.milOn) "ON" else "OFF",
            if (status.milOn) COLOR_ERROR else COLOR_SUCCESS)
        valueRow(card, "Code count", status.dtcCount.toString(), COLOR_TEXT)
        status.monitors.forEach { (name, state) ->
            when (state) {
                MonitorState.COMPLETE -> valueRow(card, name, "Passed", COLOR_SUCCESS)
                MonitorState.INCOMPLETE -> valueRow(card, name, "Not ready", COLOR_WARNING)
                MonitorState.UNAVAILABLE -> valueRow(card, name, "N/A", COLOR_MUTED)
            }
        }
    }

    private fun valueRow(container: LinearLayout, label: String, value: String, valueColor: Int) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(COLOR_TEXT)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = value
            textSize = 14f
            setTextColor(valueColor)
            setTypeface(typeface, Typeface.BOLD)
        })
        container.addView(row, marginParams(bottom = 6))
    }

    private fun leaveSettings() {
        if (settingsReturnsToDashboard) showDashboard() else showStartupScreen(autoConnect = false)
    }

    private fun buildMetricCards() {
        val container = metricsList ?: return
        container.removeAllViews()
        val chosen = selectedMetrics
        metricsCounter?.text =
            "${chosen.size} of ${ObdMetrics.MAX_SELECTED} selected. Tap to add or remove. " +
                "Values a car doesn't support show as —."
        for (metric in ObdMetrics.ALL) {
            val position = chosen.indexOfFirst { it.id == metric.id }
            val selected = position >= 0
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            addText(
                content,
                if (selected) "✓ ${position + 1}   ${metric.label}" else metric.label,
                15f,
                if (selected) COLOR_ACCENT else COLOR_TEXT,
                bold = true
            )
            val detail = if (metric.isAdapterVoltage) "Read from the adapter" else "PID ${metric.id}"
            addText(content, if (metric.unit.isEmpty()) detail else "$detail  ·  ${metric.unit}", 12f, COLOR_MUTED, top = 2)
            val card = MaterialCardView(this).apply {
                radius = dp(16).toFloat()
                cardElevation = dp(1).toFloat()
                setCardBackgroundColor(ColorStateList.valueOf(COLOR_PANEL))
                setStrokeColor(ColorStateList.valueOf(if (selected) COLOR_ACCENT else COLOR_BORDER))
                strokeWidth = if (selected) dp(2) else dp(1)
                isClickable = true
                isFocusable = true
                addView(content, ViewGroup.LayoutParams(-1, -2))
                setOnClickListener { toggleMetric(metric) }
            }
            container.addView(card, marginParams(bottom = 8))
        }
    }

    private fun toggleMetric(metric: ObdMetric) {
        val current = selectedMetrics
        val updated = when {
            current.any { it.id == metric.id } -> {
                if (current.size == 1) {
                    Toast.makeText(this, "Keep at least one value selected.", Toast.LENGTH_SHORT).show()
                    return
                }
                current.filter { it.id != metric.id }
            }
            current.size >= ObdMetrics.MAX_SELECTED -> {
                Toast.makeText(this, "Deselect one first (maximum ${ObdMetrics.MAX_SELECTED}).", Toast.LENGTH_SHORT).show()
                return
            }
            else -> current + metric
        }
        selectedMetrics = updated
        preferences.edit().putString(SELECTED_METRICS, ObdMetrics.serialize(updated)).apply()
        buildMetricCards()
    }

    private fun showDisplayScreen() {
        screen = Screen.DISPLAY
        buildDisplay()
        Toast.makeText(this, "Press back to leave the full-screen dashboard", Toast.LENGTH_SHORT).show()
        if (adapterState == AdapterState.CONNECTED) startPolling() else pollGeneration.incrementAndGet()
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, STATUS_REFRESH_MS)
    }

    private fun buildDisplay() {
        metricValues.clear()
        gaugeViews.clear()
        feedView = null
        val metrics = selectedMetrics
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val columns = when {
            metrics.size == 1 -> 1
            metrics.size == 2 -> if (landscape) 2 else 1
            else -> 2
        }
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        metrics.chunked(columns).forEach { rowMetrics ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowMetrics.forEach {
                row.addView(displayCell(it), LinearLayout.LayoutParams(0, -1, 1f).apply {
                    setMargins(dp(6), dp(6), dp(6), dp(6))
                })
            }
            repeat(columns - rowMetrics.size) { row.addView(View(this), LinearLayout.LayoutParams(0, -1, 1f)) }
            grid.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setDisplayMode(true)
        setContentView(grid)
    }

    private fun gaugePalette() = GaugePalette(
        accent = COLOR_ACCENT, track = COLOR_BORDER, text = COLOR_TEXT,
        muted = COLOR_MUTED, face = COLOR_PANEL, warning = COLOR_ERROR
    )

    private fun newGauge(metric: ObdMetric, style: GaugeStyle = gaugeStyle): GaugeView {
        val (low, high) = ObdMetrics.range(metric)
        return GaugeView(this, style).also {
            it.configure(metric.label, metric.unit, low, high, gaugePalette())
        }
    }

    private fun gaugeCard(gauge: GaugeView, radius: Int, padding: Int) = MaterialCardView(this).apply {
        this.radius = dp(radius).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(ColorStateList.valueOf(COLOR_PANEL))
        setStrokeColor(ColorStateList.valueOf(COLOR_BORDER))
        strokeWidth = dp(1)
        setContentPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        addView(gauge, ViewGroup.LayoutParams(-1, -1))
    }

    private fun displayCell(metric: ObdMetric): View {
        if (gaugeStyle != GaugeStyle.PLAIN) {
            val gauge = newGauge(metric)
            gaugeViews[metric.label] = gauge
            return gaugeCard(gauge, 24, 8)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(14), dp(12), dp(14))
        }
        content.addView(TextView(this).apply {
            text = metric.label.uppercase()
            textSize = 14f
            setTextColor(COLOR_MUTED)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 2
        }, LinearLayout.LayoutParams(-1, -2))
        val value = TextView(this).apply {
            text = "—"
            setTextColor(COLOR_MUTED)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(24, 220, 2, TypedValue.COMPLEX_UNIT_SP)
        }
        content.addView(value, LinearLayout.LayoutParams(-1, 0, 1f))
        metricValues[metric.label] = value
        if (metric.unit.isNotEmpty()) {
            content.addView(TextView(this).apply {
                text = metric.unit
                textSize = 20f
                setTextColor(COLOR_MUTED)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, -2))
        }
        return MaterialCardView(this).apply {
            radius = dp(24).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(ColorStateList.valueOf(COLOR_PANEL))
            setStrokeColor(ColorStateList.valueOf(COLOR_BORDER))
            strokeWidth = dp(1)
            addView(content, ViewGroup.LayoutParams(-1, -1))
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (screen == Screen.DISPLAY) buildDisplay()
    }

    private var immersive = false

    // Full-screen mode hides the system bars and keeps the screen awake.
    private fun setDisplayMode(on: Boolean) {
        if (immersive == on) return
        immersive = on
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController ?: return
            if (on) controller.hide(WindowInsets.Type.systemBars()) else controller.show(WindowInsets.Type.systemBars())
        } else if (on) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else {
            applySystemBars()
        }
    }

    private fun applySystemBars() {
        // Accessing decorView installs it; insetsController is null before that.
        val decor = window.decorView
        @Suppress("DEPRECATION")
        window.statusBarColor = COLOR_BACKGROUND
        @Suppress("DEPRECATION")
        window.navigationBarColor = if (darkMode) COLOR_BACKGROUND else COLOR_ACCENT
        window.setBackgroundDrawable(ColorDrawable(COLOR_BACKGROUND))
        // Dark icons only make sense on the light theme's status bar.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                if (darkMode) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            decor.systemUiVisibility =
                if (darkMode) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }

    private fun dotText(color: Int, text: String): CharSequence =
        SpannableString("●  $text").apply {
            setSpan(ForegroundColorSpan(color), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    private fun baseLayout() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(28), dp(24), dp(28))
        setBackgroundColor(COLOR_BACKGROUND)
    }

    private fun statusView() = TextView(this).apply {
        text = "Disconnected"
        textSize = 17f
        setTextColor(COLOR_TEXT)
    }

    private fun showScreen() {
        if (screen != Screen.DASHBOARD && screen != Screen.PROTOCOL) feedView = null
        setDisplayMode(false)
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setBackgroundColor(COLOR_BACKGROUND)
            addView(root, ViewGroup.LayoutParams(-1, -2))
        }
        setContentView(scrollView)
    }

    private fun addText(
        container: LinearLayout,
        value: CharSequence,
        size: Float,
        color: Int,
        bold: Boolean = false,
        top: Int = 0,
        bottom: Int = 0
    ): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        container.addView(this, marginParams(top, bottom))
    }

    private fun addCard(container: LinearLayout, content: LinearLayout) {
        val card = MaterialCardView(this).apply {
            radius = dp(22).toFloat()
            cardElevation = dp(2).toFloat()
            setCardBackgroundColor(ColorStateList.valueOf(COLOR_PANEL))
            setStrokeColor(ColorStateList.valueOf(COLOR_BORDER))
            strokeWidth = dp(1)
            addView(content, ViewGroup.LayoutParams(-1, -2))
        }
        container.addView(card, marginParams(bottom = 16))
    }

    private fun addActionButton(
        container: LinearLayout,
        label: String,
        primary: Boolean,
        onClick: () -> Unit
    ): MaterialButton {
        val button = MaterialButton(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            cornerRadius = dp(16)
            insetTop = 0
            insetBottom = 0
            minHeight = dp(54)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            backgroundTintList = ColorStateList.valueOf(if (primary) COLOR_ACCENT else COLOR_PANEL)
            setTextColor(if (primary) COLOR_BACKGROUND else COLOR_TEXT)
            strokeWidth = if (primary) 0 else dp(1)
            setStrokeColor(ColorStateList.valueOf(COLOR_BORDER))
            setOnClickListener { onClick() }
        }
        container.addView(button, marginParams(bottom = 10))
        return button
    }

    private fun addMetricRow(container: LinearLayout, metrics: List<ObdMetric>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cardHeight = if (gaugeStyle == GaugeStyle.PLAIN) dp(112) else dp(190)
        metrics.forEachIndexed { index, metric ->
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            if (gaugeStyle == GaugeStyle.PLAIN) {
                addText(content, metric.label.uppercase(), 11f, COLOR_MUTED, bold = true, bottom = 8)
                    .maxLines = 2
                val value = addText(content, "—", 27f, COLOR_MUTED, bold = true)
                metricValues[metric.label] = value
                if (peakOn(GaugeStyle.PLAIN)) {
                    peakViews[metric.label] = addText(content, "Peak —", 12f, COLOR_WARNING, top = 4)
                }
            }

            val card = if (gaugeStyle != GaugeStyle.PLAIN) {
                val gauge = newGauge(metric)
                gaugeViews[metric.label] = gauge
                gaugeCard(gauge, 20, 6)
            } else MaterialCardView(this).apply {
                radius = dp(20).toFloat()
                cardElevation = dp(2).toFloat()
                setCardBackgroundColor(ColorStateList.valueOf(COLOR_PANEL))
                setStrokeColor(ColorStateList.valueOf(COLOR_BORDER))
                strokeWidth = dp(1)
                addView(content, ViewGroup.LayoutParams(-1, -1))
            }
            row.addView(card, LinearLayout.LayoutParams(0, cardHeight, 1f).apply {
                if (index == 0) marginEnd = dp(6) else marginStart = dp(6)
            })
        }
        if (metrics.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, cardHeight, 1f))
        container.addView(row, marginParams(bottom = 12))
    }

    private fun updateMetric(label: String, value: String?, unit: String, number: Double? = null) {
        if (number != null && value != null && number > (peaks[label]?.first ?: Double.NEGATIVE_INFINITY)) {
            peaks[label] = number to value
        }
        val peak = peaks[label]
        val showPeak = peakOn(gaugeStyle)
        gaugeViews[label]?.apply {
            setReading(if (value == null) null else number, value)
            setPeak(if (showPeak) peak?.first else null)
        }
        peakViews[label]?.text = if (peak == null) "Peak —" else "Peak ${peak.second} $unit".trim()
        metricValues[label]?.apply {
            val showUnit = screen != Screen.DISPLAY && unit.isNotEmpty()
            text = if (value == null) "—" else if (showUnit) "$value $unit" else value
            setTextColor(if (value == null) COLOR_MUTED else COLOR_TEXT)
        }
    }

    private fun roundedBackground(color: Int, stroke: Int, radius: Int) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), stroke)
        }

    private fun marginParams(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun BluetoothDevice.displayName(): String =
        "${name ?: "Unnamed Bluetooth device"}\n$address"

    // ---------------------------------------------------------------- permissions / lifecycle

    private fun hasBluetoothPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    private fun requestBluetoothPermissionsIfNeeded(onGranted: () -> Unit) {
        pendingPermissionAction = onGranted
        val permissions = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            else -> emptyArray()
        }
        if (permissions.isEmpty()) {
            pendingPermissionAction = null
            onGranted()
        } else {
            ActivityCompat.requestPermissions(this, permissions, BLUETOOTH_PERMISSION_REQUEST)
        }
    }

    @Deprecated("Use the Activity Result API for permission requests.")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != BLUETOOTH_PERMISSION_REQUEST) return

        val action = pendingPermissionAction
        pendingPermissionAction = null
        if (hasBluetoothPermission()) {
            action?.invoke()
        } else if (screen == Screen.STARTUP || screen == Screen.PICKER) {
            status.text = "Bluetooth permissions are required to find and connect to an adapter."
        }
    }

    override fun onDestroy() {
        pollGeneration.incrementAndGet()
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(discoveryReceiver)
        link.close()
        super.onDestroy()
    }

    private inline fun <reified T : android.os.Parcelable> Intent.getParcelableExtraCompat(
        name: String
    ): T? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(name)
    }

    private fun registerReceiverCompat(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    companion object {
        private const val APP_NAME = "Hooch's Dash"
        private const val DARK_MODE = "dark_mode"
        private const val GAUGE_STYLE = "gauge_style"
        private const val START_IN_DISPLAY = "start_in_display"
        private const val PEAK_PREFIX = "peak_"
        private val PREVIEW_RPM = listOf(2740.0, 5200.0, 1500.0, 6800.0, 3300.0)
        private const val SELECTED_METRICS = "selected_metrics"
        private const val PREFERENCES_NAME = "hoochwindgrass_obd2"
        private const val SAVED_DEVICE_ADDRESS = "saved_device_address"
        private const val SAVED_DEVICE_NAME = "saved_device_name"
        private const val SELECTED_PROTOCOL_ID = "selected_protocol_id"
        private const val CUSTOM_NAME = "custom_protocol_name"
        private const val CUSTOM_COMMANDS = "custom_protocol_commands"
        private const val BLUETOOTH_PERMISSION_REQUEST = 1001
        private const val POLL_INTERVAL_MS = 30L
        private const val STATUS_REFRESH_MS = 3000L
        private const val STALE_MS = 15_000L
        private const val BACK_EXIT_WINDOW_MS = 2000L
        private const val ADAPTER_TIMEOUT_MS = 4000L
        private const val RESET_TIMEOUT_MS = 6000L
        private const val PID_TIMEOUT_MS = 12_000L
        private const val VIN_TIMEOUT_MS = 3_000L
        private const val MAX_VIN_ATTEMPTS = 3
        private const val TEST_TIMEOUT_MS = 20_000L
        private const val SCAN_TIMEOUT_MS = 15_000L
        private const val AUTO_SCAN_TIMEOUT_MS = 30_000L
        private const val FEED_CAPACITY = 200
        private const val FEED_VISIBLE_LINES = 14
        private const val FEED_REFRESH_MS = 250L
        private val LIVENESS_PIDS = listOf("010C", "010D", "0105", "0111")
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        private val SPP_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
