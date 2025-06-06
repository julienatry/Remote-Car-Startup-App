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
    private lateinit var buttonAction1: Button
    private lateinit var buttonAction2: Button
    private lateinit var textViewReceivedData1: TextView
    private lateinit var textViewReceivedData2: TextView

    private var bluetoothService: BluetoothService? = null
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    // To store the name of the connected device
    private var connectedDeviceName: String? = null

    // State for the toggle buttons
    private var isAction1StateA: Boolean = true
    private var isAction2StateA: Boolean = true

    companion object {
        private const val TAG = "MainActivity"

        // Message types sent from the BluetoothService Handler
        const val MESSAGE_STATE_CHANGE = 1
        const val MESSAGE_READ = 2
        const val MESSAGE_WRITE = 3
        const val MESSAGE_DEVICE_NAME = 4
        const val MESSAGE_TOAST = 5

        // Key names received from the BluetoothService Handler
        const val DEVICE_NAME = "device_name"
        const val TOAST = "toast"
    }

    // Handler to get information back from the BluetoothService
    @SuppressLint("HandlerLeak")
    private val handler = object : Handler(Looper.getMainLooper()) {
        @SuppressLint("SetTextI18n")
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MESSAGE_STATE_CHANGE -> {
                    when (msg.arg1) {
                        BluetoothService.STATE_CONNECTED -> {
                            textViewStatus.text = "Connected to: ${connectedDeviceName ?: "Unknown Device"}"
                            setActionButtonState(true) // Enable buttons
                        }
                        BluetoothService.STATE_CONNECTING -> {
                            textViewStatus.text = "Connecting..."
                            connectedDeviceName = null // Clear previous name while connecting
                            setActionButtonState(false) // Disable buttons while connecting
                        }
                        BluetoothService.STATE_LISTEN, BluetoothService.STATE_NONE -> {
                            textViewStatus.text = "Status: Not Connected"
                            connectedDeviceName = null // Clear name when not connected
                            setActionButtonState(false) // Disable buttons
                        }
                    }
                }
                MESSAGE_WRITE -> {
                    // val writeBuf = msg.obj as ByteArray
                    // val writeMessage = String(writeBuf)
                    // Log.d(TAG, "Sent: $writeMessage via Handler")
                }
                MESSAGE_READ -> {
                    val readBuf = msg.obj as ByteArray
                    val readMessage = String(readBuf, 0, msg.arg1)
                    Log.d(TAG, "Received: $readMessage")
                    if (textViewReceivedData1.text.toString() == "---" || textViewReceivedData1.text.isEmpty() || textViewReceivedData1.text == readMessage) {
                        textViewReceivedData1.text = readMessage
                    } else if (textViewReceivedData2.text.toString() == "---" || textViewReceivedData2.text.isEmpty() || textViewReceivedData2.text == readMessage) {
                        textViewReceivedData2.text = readMessage
                    } else {
                        textViewReceivedData1.text = readMessage
                    }
                }
                MESSAGE_DEVICE_NAME -> {
                    connectedDeviceName = msg.data.getString(DEVICE_NAME)
                    Toast.makeText(applicationContext, "Device: ${connectedDeviceName ?: "Unknown Device"}", Toast.LENGTH_SHORT).show()
                }
                MESSAGE_TOAST -> {
                    Toast.makeText(applicationContext, msg.data.getString(TOAST), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }


    // Launcher for enabling Bluetooth
    private val enableBluetoothLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(this, "Bluetooth enabled", Toast.LENGTH_SHORT).show()
            setupBluetoothService() // Setup service after BT is enabled
            openConnectActivity() // Proceed to connect activity
        } else {
            Toast.makeText(this, "Bluetooth not enabled.", Toast.LENGTH_SHORT).show()
        }
    }

    // Launcher for getting the device to connect from BluetoothConnectActivity
    private val connectDeviceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val deviceAddress = result.data?.getStringExtra(BluetoothConnectActivity.EXTRA_DEVICE_ADDRESS)
            if (deviceAddress != null) {
                val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
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

        // Initialize UI elements
        buttonConnect = findViewById(R.id.buttonConnect)
        textViewStatus = findViewById(R.id.textViewStatus)
        textViewEngineState = findViewById(R.id.textViewEngineState)
        textViewBoostLevel = findViewById(R.id.textViewBoostLevel)
        buttonAction1 = findViewById(R.id.buttonAction1)
        buttonAction2 = findViewById(R.id.buttonAction2)
        textViewReceivedData1 = findViewById(R.id.textViewReceivedData1)
        textViewReceivedData2 = findViewById(R.id.textViewReceivedData2)

        // Set up button listeners
        buttonConnect.setOnClickListener {
            checkPermissionsAndConnect()
        }

        buttonAction1.setOnClickListener {
            // Toggle the state and update the TextView and send data
            isAction1StateA = !isAction1StateA
            if (isAction1StateA) {
                textViewEngineState.text = "Engine State: ON"
                sendData("StartupSequence\n")
            } else {
                textViewEngineState.text = "Engine State: OFF"
                sendData("EngineOff\n")
            }
        }
        buttonAction2.setOnClickListener {
            // Toggle the state and update the TextView and send data
            isAction2StateA = !isAction2StateA
            if (isAction2StateA) {
                textViewBoostLevel.text = "Boost Level: Low"
                sendData("LowBoost\n")
            } else {
                textViewBoostLevel.text = "Boost Level: High"
                sendData("HighBoost\n")
            }
        }

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not supported on this device", Toast.LENGTH_LONG).show()
            buttonConnect.isEnabled = false // Disable BT features
            return
        }
        // Initial state of status
        textViewStatus.text = "Status: Not Connected"
        textViewEngineState.text = "Engine State: "
        textViewBoostLevel.text = "Boost Level: "
        setActionButtonState(false) // Initially disable action buttons
    }

    override fun onStart() {
        super.onStart()
        if (bluetoothAdapter?.isEnabled == true) {
            if (bluetoothService == null) setupBluetoothService()
        } else {
            Log.d(TAG, "Bluetooth is not enabled onStart.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothService?.stop()
        bluetoothService = null
    }

    override fun onResume() {
        super.onResume()
        if (bluetoothService != null) {
            if (bluetoothService?.getState() == BluetoothService.STATE_NONE) {
                bluetoothService?.start() // Prepares the service, doesn't auto-connect.
            }
        }
    }

    private fun setupBluetoothService() {
        Log.d(TAG, "setupBluetoothService()")
        if (bluetoothService == null) { // Ensure only one instance
            bluetoothService = BluetoothService(this, handler)
        }
    }


    private fun checkPermissionsAndConnect() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported.", Toast.LENGTH_SHORT).show()
            return
        }

        val requiredPermissionsS = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        val missingPermissionsS = requiredPermissionsS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissionsS.isNotEmpty()) {
            Log.d(TAG, "Requesting S permissions: ${missingPermissionsS.joinToString()}")
            requestBluetoothPermissionsLauncherS.launch(missingPermissionsS.toTypedArray())
        } else {
            ensureBluetoothEnabledAndConnect()
        }
    }

    private val requestBluetoothPermissionsLauncherS =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.BLUETOOTH_SCAN] == true && permissions[Manifest.permission.BLUETOOTH_CONNECT] == true) {
                Toast.makeText(this, "Bluetooth permissions granted (S+)", Toast.LENGTH_SHORT).show()
                ensureBluetoothEnabledAndConnect()
            } else {
                Toast.makeText(this, "Bluetooth permissions (SCAN & CONNECT) are required.", Toast.LENGTH_LONG).show()
                Log.w(TAG, "S+ Bluetooth permissions denied. Scan: ${permissions[Manifest.permission.BLUETOOTH_SCAN]}, Connect: ${permissions[Manifest.permission.BLUETOOTH_CONNECT]}")

            }
        }


    private fun ensureBluetoothEnabledAndConnect() {
        if (bluetoothAdapter?.isEnabled == false) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "BLUETOOTH_CONNECT permission needed to enable Bluetooth.", Toast.LENGTH_LONG).show()
                return
            }
            enableBluetoothLauncher.launch(enableBtIntent)
        } else {
            if (bluetoothService == null) setupBluetoothService()
            openConnectActivity()
        }
    }

    private fun openConnectActivity() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "BLUETOOTH_SCAN permission needed to list devices.", Toast.LENGTH_LONG).show()
            return
        }

        val intent = Intent(this, BluetoothConnectActivity::class.java)
        connectDeviceLauncher.launch(intent)
    }

    private fun sendData(message: String) {
        if (bluetoothService?.getState() != BluetoothService.STATE_CONNECTED) {
            Toast.makeText(this, "Not connected to a device", Toast.LENGTH_SHORT).show()
            return
        }

        if (message.isNotEmpty()) {
            val send = message.toByteArray()
            bluetoothService?.write(send)
            Log.d(TAG, "Attempting to send: $message")
        }
    }

    private fun setActionButtonState(enabled: Boolean) {
        buttonAction1.isEnabled = enabled
        buttonAction2.isEnabled = enabled
    }
}