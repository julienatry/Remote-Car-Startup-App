package fr.julienatry.remotecarstartup

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.widget.Button
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var buttonConnect: Button
    private lateinit var textViewStatus: TextView
    private lateinit var textViewEngineState: TextView
    private lateinit var textViewBoostLevel: TextView
    private lateinit var textViewAccessoriesState: TextView
    private lateinit var textViewIgnitionState: TextView
    private lateinit var textViewStarterState: TextView
    private lateinit var textViewBatteryVoltage: TextView
    private lateinit var buttonStartStopEngine: Button
    private lateinit var buttonBoostLevel: Button
    private lateinit var buttonAccessories: Button
    private lateinit var buttonIgnition: Button
    private lateinit var buttonStarter: Button
    private lateinit var numberPickerStarterDuration: NumberPicker

    private var bluetoothService: BluetoothService? = null
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private var connectedDeviceName: String? = null

    private var isStartStopEngineStateA = true
    private var isBoostLevelStateA = true
    private var isAccessoriesStateA = true
    private var isIgnitionStateA = true
    private var isStarterStateA = true

    private val DEVICE_MAC_ADDRESS_TO_AUTOCONNECT = "00:14:03:05:F1:97"

    private val batteryUpdateHandler = Handler(Looper.getMainLooper())
    private val batteryUpdateRunnable = object : Runnable {
        override fun run() {
            sendData("BatteryVoltage\n")
            batteryUpdateHandler.postDelayed(this, 2000)
        }
    }

    companion object {
        private const val TAG = "MainActivity"

        const val MESSAGE_STATE_CHANGE = 1
        const val MESSAGE_READ = 2
        const val MESSAGE_WRITE = 3
        const val MESSAGE_DEVICE_NAME = 4
        const val MESSAGE_TOAST = 5

        const val DEVICE_NAME = "device_name"
        const val TOAST = "toast"
    }

    @SuppressLint("HandlerLeak")
    private val handler = object : Handler(Looper.getMainLooper()) {
        @SuppressLint("SetTextI18n")
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MESSAGE_READ -> {
                    val readBuf = msg.obj as ByteArray
                    val readMessage = String(readBuf, 0, msg.arg1)
                    val parts = readMessage.split(" ", limit = 2)
                    val command = parts[0]
                    val value = parts.getOrNull(1)?.trim() ?: ""

                    when (command) {
                        "EngineState" -> {
                            textViewEngineState.text = "Engine State: ${if (value == "1") "ON" else "OFF"}"
                            isStartStopEngineStateA = value == "1"
                        }
                        "BoostMode" -> {
                            textViewBoostLevel.text = "Boost Level: ${if (value == "1") "High" else "Low"}"
                            isBoostLevelStateA = value != "1"
                        }
                        "AccessoriesState" -> {
                            textViewAccessoriesState.text = "Accessories State: ${if (value == "1") "ON" else "OFF"}"
                            isAccessoriesStateA = value != "1"
                        }
                        "IgnitionState" -> {
                            textViewIgnitionState.text = "Ignition State: ${if (value == "1") "ON" else "OFF"}"
                            isIgnitionStateA = value != "1"
                        }
                        "StarterState" -> {
                            textViewStarterState.text = "Starter State: ${if (value == "1") "ON" else "OFF"}"
                            isStarterStateA = value == "1"
                        }
                        "Battery" -> {
                            textViewBatteryVoltage.text = "Battery: $value V"
                        }
                        else -> Log.d(TAG, "Unknown command received: $command")
                    }
                }
                MESSAGE_STATE_CHANGE -> {
                    when (msg.arg1) {
                        BluetoothService.STATE_CONNECTED -> {
                            textViewStatus.text = "Status: Connected to ${connectedDeviceName ?: "Unknown Device"}"
                            setActionButtonState(true)
                            batteryUpdateHandler.post(batteryUpdateRunnable)
                        }
                        BluetoothService.STATE_CONNECTING -> {
                            textViewStatus.text = "Status: Connecting..."
                        }
                        BluetoothService.STATE_LISTEN, BluetoothService.STATE_NONE -> {
                            textViewStatus.text = "Status: Not Connected"
                            setActionButtonState(false)
                            batteryUpdateHandler.removeCallbacks(batteryUpdateRunnable)
                        }
                    }
                }
                MESSAGE_DEVICE_NAME -> {
                    connectedDeviceName = msg.data.getString(DEVICE_NAME)
                    textViewStatus.text = "Connected to: ${connectedDeviceName ?: "Unknown Device"}"
                    Toast.makeText(applicationContext, "Device: ${connectedDeviceName ?: "Unknown Device"}", Toast.LENGTH_SHORT).show()
                    sendData("Connected\n")
                    setActionButtonState(true)
                    batteryUpdateHandler.post(batteryUpdateRunnable)
                }
                MESSAGE_TOAST -> {
                    Toast.makeText(applicationContext, msg.data.getString(TOAST), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            setupBluetoothService()
            openConnectActivity()
        } else {
            Toast.makeText(this, "Bluetooth not enabled.", Toast.LENGTH_SHORT).show()
        }
    }

    private val connectDeviceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val deviceAddress = result.data?.getStringExtra(BluetoothConnectActivity.EXTRA_DEVICE_ADDRESS)
            deviceAddress?.let {
                val device = bluetoothAdapter?.getRemoteDevice(it)
                if (device != null && bluetoothService != null) {
                    bluetoothService?.connect(device)
                } else {
                    Toast.makeText(this, "Failed to get device or service not ready.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupButtonListeners()

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported on this device", Toast.LENGTH_LONG).show()
            buttonConnect.isEnabled = false
            return
        }

        textViewStatus.text = "Status: Not Connected"
        setDefaultStates()
        setActionButtonState(false)
    }

    override fun onStart() {
        super.onStart()
        if (bluetoothAdapter?.isEnabled == true && bluetoothService == null) {
            setupBluetoothService()
        }
    }

    override fun onResume() {
        super.onResume()
        if (bluetoothService == null) {
            setupBluetoothService()
            attemptAutoConnect()
        } else if (bluetoothService?.getState() == BluetoothService.STATE_NONE) {
            bluetoothService?.start()
            attemptAutoConnect()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothService?.stop()
        bluetoothService = null
        batteryUpdateHandler.removeCallbacks(batteryUpdateRunnable)
    }

    private fun initViews() {
        buttonConnect = findViewById(R.id.buttonConnect)
        textViewStatus = findViewById(R.id.textViewStatus)
        textViewEngineState = findViewById(R.id.textViewEngineState)
        textViewBoostLevel = findViewById(R.id.textViewBoostLevel)
        textViewAccessoriesState = findViewById(R.id.textViewAccessoriesState)
        textViewIgnitionState = findViewById(R.id.textViewIgnitionState)
        textViewStarterState = findViewById(R.id.textViewStarterState)
        textViewBatteryVoltage = findViewById(R.id.textViewBatteryVoltage)
        buttonStartStopEngine = findViewById(R.id.buttonStartStopEngine)
        buttonBoostLevel = findViewById(R.id.buttonBoostLevel)
        buttonAccessories = findViewById(R.id.buttonAccessories)
        buttonIgnition = findViewById(R.id.buttonIgnition)
        buttonStarter = findViewById(R.id.buttonStarter)
        numberPickerStarterDuration = findViewById(R.id.numberPickerStarterDuration)

        numberPickerStarterDuration.minValue = 1
        numberPickerStarterDuration.maxValue = 10
        numberPickerStarterDuration.value = 3
    }

    private fun setupButtonListeners() {
        buttonConnect.setOnClickListener { checkPermissionsAndConnect() }

        buttonStartStopEngine.setOnClickListener {
            isStartStopEngineStateA = !isStartStopEngineStateA
            textViewEngineState.text = "Engine State: ${if (isStartStopEngineStateA) "ON" else "OFF"}"
            sendData(if (isStartStopEngineStateA) "EngineON\n" else "EngineOFF\n")
        }

        buttonBoostLevel.setOnClickListener {
            isBoostLevelStateA = !isBoostLevelStateA
            textViewBoostLevel.text = "Boost Level: ${if (isBoostLevelStateA) "Low" else "High"}"
            sendData(if (isBoostLevelStateA) "LowBoost\n" else "HighBoost\n")
        }

        buttonAccessories.setOnClickListener {
            isAccessoriesStateA = !isAccessoriesStateA
            textViewAccessoriesState.text = "Accessories State: ${if (isAccessoriesStateA) "OFF" else "ON"}"
            sendData(if (isAccessoriesStateA) "AccessoriesOFF\n" else "AccessoriesON\n")
        }

        buttonIgnition.setOnClickListener {
            isIgnitionStateA = !isIgnitionStateA
            textViewIgnitionState.text = "Ignition State: ${if (isIgnitionStateA) "OFF" else "ON"}"
            sendData(if (isIgnitionStateA) "IgnitionOFF\n" else "IgnitionON\n")
        }

        buttonStarter.setOnClickListener {
            val starterDuration = numberPickerStarterDuration.value
            textViewStarterState.text = "Starter State: ON for $starterDuration sec"
            sendData("StarterON $starterDuration\n")

            Handler(Looper.getMainLooper()).postDelayed({
                textViewStarterState.text = "Starter State: OFF"
                isStarterStateA = false
            }, (starterDuration * 1000).toLong())
        }
    }

    private fun setDefaultStates() {
        textViewEngineState.text = "Engine State: OFF"
        textViewBoostLevel.text = "Boost Level: Low"
        textViewAccessoriesState.text = "Accessories State: OFF"
        textViewIgnitionState.text = "Ignition State: OFF"
        textViewStarterState.text = "Starter State: OFF"
        textViewBatteryVoltage.text = "Battery: "
    }

    private fun setupBluetoothService() {
        if (bluetoothService == null) {
            bluetoothService = BluetoothService(this, handler)
        }
    }

    private fun checkPermissionsAndConnect() {
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestBluetoothPermissionsLauncherS.launch(missingPermissions.toTypedArray())
        } else {
            ensureBluetoothEnabledAndConnect()
        }
    }

    private val requestBluetoothPermissionsLauncherS =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.BLUETOOTH_SCAN] == true &&
                permissions[Manifest.permission.BLUETOOTH_CONNECT] == true
            ) {
                ensureBluetoothEnabledAndConnect()
            } else {
                Toast.makeText(this, "Bluetooth permissions are required.", Toast.LENGTH_LONG).show()
            }
        }

    private fun ensureBluetoothEnabledAndConnect() {
        if (bluetoothAdapter?.isEnabled == false) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        } else {
            if (bluetoothService == null) setupBluetoothService()
            openConnectActivity()
        }
    }

    private fun openConnectActivity() {
        val intent = Intent(this, BluetoothConnectActivity::class.java)
        connectDeviceLauncher.launch(intent)
    }

    private fun sendData(message: String) {
        if (bluetoothService?.getState() != BluetoothService.STATE_CONNECTED) {
            Toast.makeText(this, "Not connected to a device", Toast.LENGTH_SHORT).show()
            return
        }
        if (message.isNotEmpty()) {
            bluetoothService?.write(message.toByteArray())
        }
    }

    private fun setActionButtonState(enabled: Boolean) {
        buttonStartStopEngine.isEnabled = enabled
        buttonBoostLevel.isEnabled = enabled
        buttonAccessories.isEnabled = enabled
        buttonIgnition.isEnabled = enabled
        buttonStarter.isEnabled = enabled
        numberPickerStarterDuration.isEnabled = enabled
    }

    private fun attemptAutoConnect() {
        val adapter = bluetoothAdapter ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        if (adapter.isEnabled && bluetoothService?.getState() == BluetoothService.STATE_NONE) {
            try {
                val device = adapter.getRemoteDevice(DEVICE_MAC_ADDRESS_TO_AUTOCONNECT)
                bluetoothService?.connect(device)
                Toast.makeText(this, "Attempting to auto-connect...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Auto-connect error", e)
                Toast.makeText(this, "Auto-connect failed", Toast.LENGTH_SHORT).show()
            }
        }
    }
}