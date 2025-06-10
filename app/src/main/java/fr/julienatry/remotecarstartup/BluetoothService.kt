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
    private var state = STATE_NONE
    private var newState = state

    companion object {
        private const val TAG = "BluetoothService"
        private val MY_UUID_SECURE: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        const val STATE_NONE = 0
        //const val STATE_LISTEN = 1
        const val STATE_CONNECTING = 2
        const val STATE_CONNECTED = 3
    }

    @Synchronized
    private fun updateUserInterfaceTitle() {
        state = getState()
        Log.d(TAG, "updateUserInterfaceTitle() $newState -> $state")
        newState = state
        handler.obtainMessage(MainActivity.MESSAGE_STATE_CHANGE, newState, -1).sendToTarget()
    }

    @Synchronized
    fun getState(): Int = state

    @Synchronized
    fun start() {
        Log.d(TAG, "start")
        connectThread?.cancel()
        connectThread = null
        connectedThread?.cancel()
        connectedThread = null
        state = STATE_NONE
        updateUserInterfaceTitle()
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun connect(device: BluetoothDevice) {
        Log.d(TAG, "connect to: $device")

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "BLUETOOTH_CONNECT permission not granted.")
            connectionFailed("BLUETOOTH_CONNECT permission denied")
            return
        }

        if (state == STATE_CONNECTING) {
            connectThread?.cancel()
            connectThread = null
        }

        connectedThread?.cancel()
        connectedThread = null

        connectThread = ConnectThread(device)
        connectThread?.start()
        state = STATE_CONNECTING
        updateUserInterfaceTitle()
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun connected(socket: BluetoothSocket, device: BluetoothDevice) {
        Log.d(TAG, "connected to: ${device.name}")

        connectThread?.cancel()
        connectThread = null

        connectedThread?.cancel()
        connectedThread = null

        state = STATE_CONNECTED
        updateUserInterfaceTitle()

        connectedThread = ConnectedThread(socket)
        connectedThread?.start()

        val msg = handler.obtainMessage(MainActivity.MESSAGE_DEVICE_NAME)
        val bundle = Bundle()

        val deviceName = if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            device.name ?: "Unknown Device"
        } else "Unknown"

        bundle.putString(MainActivity.DEVICE_NAME, deviceName)
        msg.data = bundle
        handler.sendMessage(msg)
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
        val thread: ConnectedThread?
        synchronized(this) {
            if (state != STATE_CONNECTED) return
            thread = connectedThread
        }
        thread?.write(out)
    }

    private fun connectionFailed(reason: String = "Failed to connect") {
        Log.e(TAG, "Connection Failed: $reason")
        val msg = handler.obtainMessage(MainActivity.MESSAGE_TOAST)
        msg.data = Bundle().apply {
            putString(MainActivity.TOAST, "Unable to connect device: $reason")
        }
        handler.sendMessage(msg)

        state = STATE_NONE
        updateUserInterfaceTitle()
    }

    private fun connectionLost() {
        Log.e(TAG, "Connection Lost")
        val msg = handler.obtainMessage(MainActivity.MESSAGE_TOAST)
        msg.data = Bundle().apply {
            putString(MainActivity.TOAST, "Device connection was lost")
        }
        handler.sendMessage(msg)

        state = STATE_NONE
        updateUserInterfaceTitle()
    }

    @SuppressLint("MissingPermission")
    private inner class ConnectThread(private val device: BluetoothDevice) : Thread() {
        private val socket: BluetoothSocket? = try {
            device.createRfcommSocketToServiceRecord(MY_UUID_SECURE)
        } catch (e: IOException) {
            Log.e(TAG, "Socket create() failed", e)
            null
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception: BLUETOOTH_CONNECT not granted", e)
            null
        }

        override fun run() {
            Log.i(TAG, "BEGIN ConnectThread")

            try {
                socket?.connect()
            } catch (e: IOException) {
                Log.e(TAG, "Connection failed", e)
                try {
                    socket?.close()
                } catch (closeEx: IOException) {
                    Log.e(TAG, "Unable to close socket", closeEx)
                }
                connectionFailed("IOException: ${e.message}")
                return
            } catch (se: SecurityException) {
                Log.e(TAG, "SecurityException during connect: ${se.message}")
                connectionFailed("SecurityException: ${se.message}")
                return
            }

            synchronized(this@BluetoothService) {
                connectThread = null
            }

            socket?.let {
                connected(it, device)
            } ?: connectionFailed("Socket was null after connect")
        }

        fun cancel() {
            try {
                socket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "close() of connect socket failed", e)
            }
        }
    }

    private inner class ConnectedThread(private val socket: BluetoothSocket) : Thread() {
        private val input: InputStream = socket.inputStream
        private val output: OutputStream = socket.outputStream

        init {
            name = "ConnectedThread"
        }

        override fun run() {
            Log.i(TAG, "BEGIN ConnectedThread")
            val buffer = ByteArray(1024)
            val messageBuilder = StringBuilder()

            while (this@BluetoothService.state == STATE_CONNECTED) {
                try {
                    val bytes = input.read(buffer)
                    if (bytes > 0) {
                        val data = String(buffer, 0, bytes, Charsets.UTF_8)
                        messageBuilder.append(data)

                        var newlineIndex: Int
                        while (messageBuilder.indexOf('\n').also { newlineIndex = it } >= 0) {
                            val fullMessage = messageBuilder.substring(0, newlineIndex).trim()
                            messageBuilder.delete(0, newlineIndex + 1)

                            if (fullMessage.isNotEmpty()) {
                                Log.d(TAG, "Received: '$fullMessage'")
                                handler.obtainMessage(
                                    MainActivity.MESSAGE_READ,
                                    fullMessage.length,
                                    -1,
                                    fullMessage.toByteArray()
                                ).sendToTarget()
                            }
                        }
                    } else if (bytes == -1) {
                        Log.d(TAG, "Input stream ended")
                        connectionLost()
                        break
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Disconnected", e)
                    connectionLost()
                    break
                }
            }
            Log.i(TAG, "END ConnectedThread")
        }

        fun write(bytes: ByteArray) {
            try {
                output.write(bytes)
                handler.obtainMessage(MainActivity.MESSAGE_WRITE, -1, -1, bytes).sendToTarget()
            } catch (e: IOException) {
                Log.e(TAG, "Error sending data", e)
                val errorMsg = handler.obtainMessage(MainActivity.MESSAGE_TOAST)
                errorMsg.data = Bundle().apply {
                    putString(MainActivity.TOAST, "Couldn't send data")
                }
                handler.sendMessage(errorMsg)
            }
        }

        fun cancel() {
            try {
                socket.close()
            } catch (e: IOException) {
                Log.e(TAG, "Could not close the connected socket", e)
            }
        }
    }
}
