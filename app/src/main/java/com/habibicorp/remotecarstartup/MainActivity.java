package com.habibicorp.remotecarstartup;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;


import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Timer;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static android.content.ContentValues.TAG;

public class MainActivity extends AppCompatActivity {

    private String deviceName = null;
    private String deviceAddress;
    private int firstTry = 1;
    public static Handler handler;
    public static BluetoothSocket mmSocket;
    public static ConnectedThread connectedThread;
    public static CreateConnectThread createConnectThread;

    private final static int CONNECTING_STATUS = 1; // used in bluetooth handler to identify message status
    private final static int MESSAGE_READ = 2; // used in bluetooth handler to identify message update

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // UI Initialization
        final Button buttonConnect = findViewById(R.id.buttonConnect);
        buttonConnect.setVisibility(View.GONE);
        final Toolbar toolbar = findViewById(R.id.toolbar);
        final ProgressBar progressBar = findViewById(R.id.progressBar);
        progressBar.setVisibility(View.GONE);
        final Button buttonAngelEyes = findViewById(R.id.buttonAngelEyes);
        buttonAngelEyes.setEnabled(false);
        final Button buttonLock = findViewById(R.id.buttonLock);
        buttonLock.setEnabled(false);
        final Button buttonStartStop = findViewById(R.id.buttonStartStop);
        buttonStartStop.setEnabled(false);
        final ImageView imageView = findViewById(R.id.imageView);
        final TextView batteryVoltage = findViewById(R.id.batteryVoltage);



        // If a bluetooth device has been selected from SelectDeviceActivity
        deviceName = getIntent().getStringExtra("deviceName");
        if (deviceName != null){
            // Get the device address to make BT Connection
            deviceAddress = getIntent().getStringExtra("deviceAddress");
            // Show progress and connection status
            toolbar.setSubtitle("Connecting to " + deviceName + "...");
            progressBar.setVisibility(View.VISIBLE);
            buttonConnect.setEnabled(false);

            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            createConnectThread = new CreateConnectThread(bluetoothAdapter,deviceAddress);
            createConnectThread.start();
        }else if (firstTry == 1){
            deviceName = "";
            deviceAddress = "";
            toolbar.setSubtitle("Connecting to " + deviceName + "...");
            progressBar.setVisibility(View.VISIBLE);
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            createConnectThread = new CreateConnectThread(bluetoothAdapter,deviceAddress);
            createConnectThread.start();
            firstTry--;
        }


        //GUI Handler
        handler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg){
                switch (msg.what){
                    case CONNECTING_STATUS:
                        switch(msg.arg1){
                            case 1:
                                toolbar.setSubtitle("Connected to " + deviceName);
                                progressBar.setVisibility(View.GONE);
                                buttonAngelEyes.setEnabled(true);
                                buttonLock.setEnabled(true);
                                buttonStartStop.setEnabled(true);
                                connectedThread.write("AngelEyesState\n");
                                connectedThread.write("LockState\n");
                                break;
                            case -1:
                                toolbar.setSubtitle("Device fails to connect");
                                progressBar.setVisibility(View.GONE);
                                buttonConnect.setEnabled(true);
                                buttonConnect.setVisibility(View.VISIBLE);
                                break;
                        }
                        break;

                    case MESSAGE_READ:
                        String arduinoMsg = msg.obj.toString(); // Read message from Arduino
                        switch (arduinoMsg.toLowerCase()){
                            case "500":
                                buttonAngelEyes.setText("Angel Eyes OFF");
                                break;
                            case "501":
                                buttonAngelEyes.setText("Angel Eyes ON");
                                break;
                            case "600":
                                buttonLock.setText("Lock");
                                break;
                            case "601":
                                buttonLock.setText("Unlock");
                                break;
                            default:
                                batteryVoltage.setText(arduinoMsg);
                        }
                        break;
                }
            }
        };

        // Select Bluetooth Device
        buttonConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // Move to adapter list
                Intent intent = new Intent(MainActivity.this, SelectDeviceActivity.class);
                startActivity(intent);
            }
        });

        // Button to ON/OFF AngelEyes Relay
        buttonAngelEyes.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String cmdText = null;
                String btnState = buttonAngelEyes.getText().toString().toLowerCase();
                switch (btnState){
                    case "angel eyes on":
                        buttonAngelEyes.setText("Angel Eyes OFF");
                        cmdText = "AngelEyesOff\n";
                        break;
                    case "angel eyes off":
                        buttonAngelEyes.setText("Angel Eyes ON");
                        cmdText = "AngelEyesOn\n";
                        break;
                }
                // Send command
                connectedThread.write(cmdText);
            }
        });

        buttonLock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String cmdText = null;
                String btnState = buttonLock.getText().toString().toLowerCase();
                switch (btnState){
                    case "lock":
                        buttonLock.setText("Unlock");
                        cmdText = "LockCar\n";
                        break;
                    case "unlock":
                        buttonLock.setText("Lock");
                        cmdText = "UnlockCar\n";
                        break;
                }
                // Send command
                connectedThread.write(cmdText);
            }
        });

        buttonStartStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String cmdText = null;
                String btnState = buttonStartStop.getText().toString().toLowerCase();
                switch (btnState){
                    case "start car":
                        buttonStartStop.setText("Stop Engine");
                        cmdText = "StartupSequence\n";
                        break;
                    case "stop engine":
                        buttonStartStop.setText("Start Car");
                        cmdText = "EngineOff\n";
                        break;
                }
                // Send command
                connectedThread.write(cmdText);
            }
        });
    }

    /* ============================ Thread to Create Bluetooth Connection =================================== */
    public static class CreateConnectThread extends Thread {

        public CreateConnectThread(BluetoothAdapter bluetoothAdapter, String address) {
            BluetoothDevice bluetoothDevice = bluetoothAdapter.getRemoteDevice(address);
            BluetoothSocket tmp = null;
            UUID uuid = bluetoothDevice.getUuids()[0].getUuid();

            try {
                /*
                if doesn't work should try this :
                tmp = device.createRfcommSocketToServiceRecord(MY_UUID);
                 */
                tmp = bluetoothDevice.createInsecureRfcommSocketToServiceRecord(uuid);

            } catch (IOException e) {
                Log.e(TAG, "Socket's create() method failed", e);
            }
            mmSocket = tmp;
        }

        public void run() {
            // Cancel discovery because it otherwise slows down the connection.
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            bluetoothAdapter.cancelDiscovery();
            try {
                // Connect to the remote device through the socket
                mmSocket.connect();
                Log.e("Status", "Device connected");
                handler.obtainMessage(CONNECTING_STATUS, 1, -1).sendToTarget();
            } catch (IOException connectException) {
                // Unable to connect; close the socket and return.
                try {
                    mmSocket.close();
                    Log.e("Status", "Cannot connect to device");
                    handler.obtainMessage(CONNECTING_STATUS, -1, -1).sendToTarget();
                } catch (IOException closeException) {
                    Log.e(TAG, "Could not close the client socket", closeException);
                }
                return;
            }

            // The connection attempt succeeded. Perform work associated with
            // the connection in a separate thread.
            connectedThread = new ConnectedThread(mmSocket);
            connectedThread.run();
        }

        // Closes the client socket and causes the thread to finish.
        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Could not close the client socket", e);
            }
        }
    }

    /* =============================== Thread for Data Transfer =========================================== */
    public static class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        public ConnectedThread(BluetoothSocket socket) {
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the input and output stream
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) { }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run() {
            byte[] buffer = new byte[1024];  // buffer store for the stream
            int bytes = 0; // bytes returned from read()
            // Keep listening to the InputStream until an exception occurs
            while (true) {
                try {
                    /*
                    Read from the InputStream from Arduino until termination character is reached.
                    Then send the whole String message to GUI Handler.
                     */
                    buffer[bytes] = (byte) mmInStream.read();
                    String readMessage;
                    if (buffer[bytes] == '\n'){
                        readMessage = new String(buffer,0,bytes);
                        Log.e("Arduino Message",readMessage);
                        handler.obtainMessage(MESSAGE_READ,readMessage).sendToTarget();
                        bytes = 0;
                    } else {
                        bytes++;
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    break;
                }
            }
        }

        /* Call this from the main activity to send data to the remote device */
        public void write(String input) {
            byte[] bytes = input.getBytes(); //converts entered String into bytes
            try {
                mmOutStream.write(bytes);
            } catch (IOException e) {
                Log.e("Send Error","Unable to send message",e);
            }
        }

        /* Call this from the main activity to shutdown the connection */
        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) { }
        }
    }

    /* ============================ Terminate Connection at BackPress ====================== */
    @Override
    public void onBackPressed() {
        // Terminate Bluetooth Connection and close app
        if (createConnectThread != null){
            createConnectThread.cancel();
        }
        Intent a = new Intent(Intent.ACTION_MAIN);
        a.addCategory(Intent.CATEGORY_HOME);
        a.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(a);
    }
}
