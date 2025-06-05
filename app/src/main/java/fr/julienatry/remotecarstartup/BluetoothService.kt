package fr.julienatry.remotecarstartup

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class BluetoothService(private val context: Context, private val handler: Handler) {

    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null
    private var state: Int = STATE_NONE
    private var newState: Int = state

    companion object {
        private const val TAG = "BluetoothService"

        // Unique UUID for this application
        // Standard SPP UUID for modules like HC-05
        private val MY_UUID_SECURE: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        // Fallback for some devices, not typically needed for HC-05 with SPP
        // private val MY_UUID_INSECURE: UUID = UUID.fromString("...")


        const val STATE_NONE = 0       // we're doing nothing
        const val STATE_LISTEN = 1     // now listening for incoming connections (not used in this client-only example)
        const val STATE_CONNECTING = 2 // now initiating an outgoing connection
        const val STATE_CONNECTED = 3  // now connected to a remote device
    }

    /**
     * Update UI title according to the current state of the chat connection
     */
    @Synchronized
    private fun updateUserInterfaceTitle() {
        state = getState()
        Log.d(TAG, "updateUserInterfaceTitle() $newState -> $state")
        newState = state

        // Give the new state to the Handler so the UI Activity can update
        handler.obtainMessage(MainActivity.MESSAGE_STATE_CHANGE, newState, -1).sendToTarget()
    }


    @Synchronized
    fun getState(): Int {
        return state
    }

    @Synchronized
    fun start() {
        Log.d(TAG, "start")

        // Cancel any thread attempting to make a connection
        connectThread?.cancel()
        connectThread = null

        // Cancel any thread currently running a connection
        connectedThread?.cancel()
        connectedThread = null

        state = STATE_NONE
        updateUserInterfaceTitle()
    }

    @SuppressLint("MissingPermission") // Permissions checked by calling activities
    @Synchronized
    fun connect(device: BluetoothDevice) {
        Log.d(TAG, "connect to: $device")

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "BLUETOOTH_CONNECT permission not granted.")
            connectionFailed("BLUETOOTH_CONNECT permission denied")
            return
        }

        // Cancel any thread attempting to make a connection
        if (state == STATE_CONNECTING) {
            connectThread?.cancel()
            connectThread = null
        }

        // Cancel any thread currently running a connection
        connectedThread?.cancel()
        connectedThread = null

        // Start the thread to connect with the given device
        connectThread = ConnectThread(device)
        connectThread?.start()
        state = STATE_CONNECTING
        updateUserInterfaceTitle()
    }

    @SuppressLint("MissingPermission") // Permissions checked by calling activities
    @Synchronized
    fun connected(socket: BluetoothSocket, device: BluetoothDevice) {
        Log.d(TAG, "connected to: ${device.name}")

        // Cancel the thread that completed the connection
        connectThread?.cancel()
        connectThread = null

        // Cancel any thread currently running a connection
        connectedThread?.cancel()
        connectedThread = null

        // Start the thread to manage the connection and perform transmissions
        connectedThread = ConnectedThread(socket)
        connectedThread?.start()

        // Send the name of the connected device back to the UI Activity
        val msg = handler.obtainMessage(MainActivity.MESSAGE_DEVICE_NAME)
        val bundle = Bundle()

        var deviceName = "Unknown"
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            deviceName = device.name ?: "Unknown Device"
        }
        bundle.putString(MainActivity.DEVICE_NAME, deviceName)
        msg.data = bundle
        handler.sendMessage(msg)

        state = STATE_CONNECTED
        updateUserInterfaceTitle()
    }

    @Synchronized
    fun stop() {
        Log.d(TAG, "stop")
        connectThread?.cancel()
        connectThread = null
        connectedThread?.cancel()
        connectedThread = null
        state = STATE_NONE
        updateUserInterfaceTitle()
    }

    fun write(out: ByteArray) {
        var r: ConnectedThread?
        synchronized(this) {
            if (state != STATE_CONNECTED) return
            r = connectedThread
        }
        r?.write(out)
    }

    private fun connectionFailed(reason: String = "Failed to connect") {
        Log.e(TAG, "Connection Failed: $reason")
        // Send a failure message back to the Activity
        val msg = handler.obtainMessage(MainActivity.MESSAGE_TOAST)
        val bundle = Bundle()
        bundle.putString(MainActivity.TOAST, "Unable to connect device: $reason")
        msg.data = bundle
        handler.sendMessage(msg)

        state = STATE_NONE
        updateUserInterfaceTitle()
    }

    private fun connectionLost() {
        Log.e(TAG, "Connection Lost")
        // Send a failure message back to the Activity
        val msg = handler.obtainMessage(MainActivity.MESSAGE_TOAST)
        val bundle = Bundle()
        bundle.putString(MainActivity.TOAST, "Device connection was lost")
        msg.data = bundle
        handler.sendMessage(msg)

        state = STATE_NONE
        updateUserInterfaceTitle()
    }


    @SuppressLint("MissingPermission") // Permissions checked before calling connect()
    private inner class ConnectThread(private val mmDevice: BluetoothDevice) : Thread() {
        private val mmSocket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            try {
                // MY_UUID is the app's UUID string
                mmDevice.createRfcommSocketToServiceRecord(MY_UUID_SECURE)
            } catch (e: IOException) {
                Log.e(TAG, "Socket create() failed", e)
                null
            } catch (se: SecurityException) {
                Log.e(TAG, "Socket create() failed due to security exception. Check BLUETOOTH_CONNECT permission.", se)
                null
            }
        }

        override fun run() {
            Log.i(TAG, "BEGIN mConnectThread SocketType")
            name = "ConnectThread" // Secure or Insecure

            try {
                // Connect the device through the socket. This will block
                // until it succeeds or throws an exception.
                mmSocket?.connect()
            } catch (e: IOException) {
                // Close the socket
                try {
                    mmSocket?.close()
                } catch (e2: IOException) {
                    Log.e(TAG, "unable to close() socket during connection failure", e2)
                }
                connectionFailed("ConnectThread IOException: ${e.message}")
                return
            } catch (se: SecurityException) {
                Log.e(TAG, "ConnectThread SecurityException: ${se.message}. Check permissions.")
                connectionFailed("ConnectThread SecurityException: ${se.message}")
                return
            }


            // Reset the ConnectThread because we're done
            synchronized(this@BluetoothService) {
                connectThread = null
            }

            // Start the connected thread
            mmSocket?.let { socket ->
                connected(socket, mmDevice)
            } ?: run {
                connectionFailed("Socket is null after connection attempt")
            }
        }

        fun cancel() {
            try {
                mmSocket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "close() of connect socket failed", e)
            }
        }
    }

    private inner class ConnectedThread(private val mmSocket: BluetoothSocket) : Thread() {
        private val mmInStream: InputStream = mmSocket.inputStream
        private val mmOutStream: OutputStream = mmSocket.outputStream
        private val mmBuffer: ByteArray = ByteArray(1024) // mmBuffer store for the stream

        override fun run() {
            Log.i(TAG, "BEGIN mConnectedThread")
            var numBytes: Int // bytes returned from read()

            // Keep listening to the InputStream until an exception occurs.
            while (this@BluetoothService.state == STATE_CONNECTED) {
                try {
                    // Read from the InputStream.
                    numBytes = mmInStream.read(mmBuffer)
                    // Send the obtained bytes to the UI activity.
                    // Make sure mmBuffer.copyOf(numBytes) is correct as read may return -1
                    if (numBytes > 0) {
                        handler.obtainMessage(MainActivity.MESSAGE_READ, numBytes, -1, mmBuffer.copyOf(numBytes))
                            .sendToTarget()
                    } else if (numBytes == -1) { // End of stream
                        Log.d(TAG, "Input stream ended.")
                        connectionLost()
                        break
                    }
                } catch (e: IOException) {
                    Log.d(TAG, "Input stream was disconnected", e)
                    connectionLost()
                    break
                }
            }
            Log.i(TAG, "END mConnectedThread")
        }

        // Call this from the main activity to send data to the remote device.
        fun write(bytes: ByteArray) {
            try {
                mmOutStream.write(bytes)
                // Share the sent message back to the UI Activity
                handler.obtainMessage(MainActivity.MESSAGE_WRITE, -1, -1, bytes)
                    .sendToTarget()
            } catch (e: IOException) {
                Log.e(TAG, "Error occurred when sending data", e)
                // Send a failure message back to the activity.
                val writeErrorMsg = handler.obtainMessage(MainActivity.MESSAGE_TOAST)
                val bundle = Bundle().apply {
                    putString(MainActivity.TOAST, "Couldn't send data")
                }
                writeErrorMsg.data = bundle
                handler.sendMessage(writeErrorMsg)
                return // Exit method if error occurs
            }
        }

        // Call this method from the main activity to shut down the connection.
        fun cancel() {
            try {
                mmSocket.close()
            } catch (e: IOException) {
                Log.e(TAG, "Could not close the connect socket", e)
            }
        }
    }
}

