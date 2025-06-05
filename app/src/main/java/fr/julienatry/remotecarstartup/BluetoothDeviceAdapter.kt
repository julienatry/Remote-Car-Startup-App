package fr.julienatry.remotecarstartup

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.content.ContextCompat

class BluetoothDeviceAdapter(context: Context, private val deviceList: ArrayList<BluetoothDevice>) :
    ArrayAdapter<BluetoothDevice>(context, 0, deviceList) {

    @SuppressLint("MissingPermission") // Permissions should be checked before querying device names/addresses
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.list_item_device, parent, false)

        val device = deviceList[position]

        val textViewName = view.findViewById<TextView>(R.id.textViewDeviceName)
        val textViewAddress = view.findViewById<TextView>(R.id.textViewDeviceAddress)

        // Check for BLUETOOTH_CONNECT permission before accessing device.name
        // Although for bonded devices, name might be available without it.
        // However, it's good practice to ensure permissions for any Bluetooth operation.
        var deviceName = "Unknown Device"
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            deviceName = device.name ?: "Unknown Device"
        } else {
            deviceName = "Name requires CONNECT permission"
        }

        textViewName.text = deviceName
        textViewAddress.text = device.address

        return view
    }

    // Method to add a device, checking for duplicates by address
    override fun add(device: BluetoothDevice?) {
        device?.let {
            if (deviceList.none { it.address == device.address }) {
                super.add(device)
            }
        }
    }
}
