package fr.julienatry.remotecarstartup

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.AdapterView
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class BluetoothConnectActivity : AppCompatActivity() {

    private lateinit var listViewPairedDevices: ListView
    private lateinit var buttonScan: Button
    private lateinit var progressBarConnect: ProgressBar
    private lateinit var pairedDevicesAdapter: BluetoothDeviceAdapter
    private var bluetoothAdapter: BluetoothAdapter? = null

    companion object {
        const val EXTRA_DEVICE_ADDRESS = "device_address"
        private const val TAG = "BluetoothConnect"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bluetooth_connect)
        title = "Select Bluetooth Device"

        listViewPairedDevices = findViewById(R.id.listViewPairedDevices)
        buttonScan = findViewById(R.id.buttonScan)
        progressBarConnect = findViewById(R.id.progressBarConnect)

        pairedDevicesAdapter = BluetoothDeviceAdapter(this, ArrayList())
        listViewPairedDevices.adapter = pairedDevicesAdapter

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        listViewPairedDevices.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val device = pairedDevicesAdapter.getItem(position) as BluetoothDevice

            // Before attempting to connect, ensure BLUETOOTH_CONNECT permission
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "BLUETOOTH_CONNECT permission required.", Toast.LENGTH_SHORT).show()
                return@OnItemClickListener
            }

            // Return the selected device's address to MainActivity
            val intent = Intent()
            intent.putExtra(EXTRA_DEVICE_ADDRESS, device.address)
            setResult(Activity.RESULT_OK, intent)
            finish()
        }

        buttonScan.setOnClickListener {
            listPairedDevices()
        }

        // Initially list paired devices
        checkPermissionsAndListDevices()
    }


    private fun checkPermissionsAndListDevices() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            // If MainActivity already handled this, it might not be needed here again,
            // but good for robustness if this activity is entered directly or permissions change.
            requestBluetoothScanPermissionLauncher.launch(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            listPairedDevices()
        }
    }

    private val requestBluetoothScanPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                listPairedDevices()
            } else {
                Toast.makeText(this, "BLUETOOTH_SCAN permission is required to list devices.", Toast.LENGTH_LONG).show()
            }
        }


    @SuppressLint("MissingPermission") // Permissions are checked before calling
    private fun listPairedDevices() {
        if (bluetoothAdapter?.isEnabled == false) {
            Toast.makeText(this, "Bluetooth is not enabled.", Toast.LENGTH_SHORT).show()
            // Optionally, request to enable Bluetooth
            // val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            // startActivityForResult(enableBtIntent, MainActivity.REQUEST_ENABLE_BT)
            return
        }

        pairedDevicesAdapter.clear()
        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter?.bondedDevices

        if (pairedDevices?.isNotEmpty() == true) {
            pairedDevices.forEach { device ->
                pairedDevicesAdapter.add(device)
                Log.d(TAG, "Paired Device: ${device.name} - ${device.address}")
            }
        } else {
            Toast.makeText(this, "No paired devices found.", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "No paired devices found.")
        }
        pairedDevicesAdapter.notifyDataSetChanged()
    }
}
