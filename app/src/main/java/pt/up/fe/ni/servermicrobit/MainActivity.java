package pt.up.fe.ni.servermicrobit;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "Server_Microbit";

    public BluetoothAdapter mBluetoothAdapter;
    private BluetoothServerSocket mmServerSocket;
    private static final UUID MY_UUID_SECURE = UUID.fromString("ae5a9be0-1972-11e8-b566-0800200c9a66");

    TextView text;

    //Handler to change UI
    public final Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            final int what = msg.what;
            switch(what) {
                case MessageConstants.MESSAGE_READ:
                    TextView textviewRead = (TextView) findViewById(R.id.text_view_current);
                    textviewRead.setText(msg.obj.toString());
                    break;
                case MessageConstants.MESSAGE_STATUS:
                    TextView textviewStatus = (TextView) findViewById(R.id.text_view_status);
                    textviewStatus.setText(msg.obj.toString());
                    break;
                case MessageConstants.MESSAGE_BUTTON:
                    Button button = (Button) findViewById(R.id.button_start);
                    button.setText(msg.obj.toString());
                    break;
            }
        }
    };

    // Defines several constants used when transmitting messages between the
    // service and the UI.
    private interface MessageConstants {
        public static final int MESSAGE_READ = 0;
        public static final int MESSAGE_WRITE = 1;
        public static final int MESSAGE_TOAST = 2;
        public static final int MESSAGE_STATUS = 3;
        public static final int MESSAGE_BUTTON = 4;
        // Add as needed
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        text = (TextView)findViewById(R.id.text_view_current);
        text.setText(android.provider.Settings.Secure.getString(this.getContentResolver(), "bluetooth_address"));
    }

    public void onClick(View view) throws IOException {
        switch (view.getId()) {
            case R.id.button_reset:  //BUG: not closing bluetooth socket if exists

                if(mmServerSocket!=null)
                    mmServerSocket.close();
                mmServerSocket = null;
                mBluetoothAdapter = null;

                text.setText(android.provider.Settings.Secure.getString(this.getContentResolver(), "bluetooth_address"));
                mHandler.obtainMessage(MessageConstants.MESSAGE_STATUS, "Offline").sendToTarget();
                break;


            case R.id.button_start:

                mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
                if (mBluetoothAdapter == null) {
                    text.setText("Does not support bluetooth");
                    return;
                }

                while(mmServerSocket==null) {
                    AcceptThread();
                    Thread thread = new Thread() {
                        @Override
                        public void run() {
                            BluetoothSocket socket = null;
                            // Keep listening until exception occurs or a socket is returned.
                            while (true) {
                                try {
                                    socket = mmServerSocket.accept();
                                } catch (IOException e) {
                                    Log.e(TAG, "Socket's accept() method failed");
                                    break;
                                }

                                if (socket != null) {
                                    // A connection was accepted. Perform work associated with
                                    // the connection in a separate thread.
                                    mHandler.obtainMessage(MessageConstants.MESSAGE_STATUS, "Online").sendToTarget();
                                    ConnectedThread ct = new ConnectedThread(socket);
                                    ct.start();
                                    try {
                                        mmServerSocket.close();
                                    } catch (IOException e){
                                        e.printStackTrace();
                                    }
                                    break;
                                }
                            }
                        }
                    };
                    thread.start();
                }
        }
    }

    public void AcceptThread() throws IOException {
        BluetoothServerSocket tmp = null;
        try {
            tmp = mBluetoothAdapter.listenUsingRfcommWithServiceRecord("ServerMicrobit", MY_UUID_SECURE);
            mHandler.obtainMessage(MessageConstants.MESSAGE_STATUS, "Listening for connections").sendToTarget();
        } catch (IOException e) {
            Log.e(TAG, "AcceptThread method failed!");
        }
        mmServerSocket = tmp;
    }



    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;
        private byte[] mmBuffer; // mmBuffer store for the stream

        private ConnectedThread(BluetoothSocket socket) {
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the input and output streams; using temp objects because
            // member streams are final.
            try {
                tmpIn = socket.getInputStream();
            } catch (IOException e) {
                Log.e(TAG, "Error occurred when creating input stream", e);
            }
            try {
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "Error occurred when creating output stream", e);
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run() {
            mmBuffer = new byte[1024];
            int numBytes; // bytes returned from read()

            // Keep listening to the InputStream until an exception occurs.
            while (mmSocket.isConnected()) {
                try {
                    // Read from the InputStream.
                    numBytes = mmInStream.read(mmBuffer);
                    // Send the obtained bytes to the UI activity.
                    Message readMsg = mHandler.obtainMessage(
                            MessageConstants.MESSAGE_READ, numBytes, -1,
                            new String(mmBuffer, 0, numBytes));
                    readMsg.sendToTarget();

                } catch (IOException e) {
                    Log.d(TAG, "Input stream was disconnected", e);
                    break;
                }
            }
            mHandler.obtainMessage(MessageConstants.MESSAGE_STATUS, "Offline").sendToTarget();
            mHandler.obtainMessage(MessageConstants.MESSAGE_READ, android.provider.Settings.Secure.getString(getContentResolver(), "bluetooth_address")).sendToTarget();
            cancel();

        }

        // Call this from the main activity to send data to the remote device.
        public void write(byte[] bytes) {
            try {
                mmOutStream.write(bytes);

                // Share the sent message with the UI activity.
                Message writtenMsg = mHandler.obtainMessage(
                        MessageConstants.MESSAGE_WRITE, -1, -1, mmBuffer);
                writtenMsg.sendToTarget();
            } catch (IOException e) {
                Log.e(TAG, "Error occurred when sending data", e);

                // Send a failure message back to the activity.
                Message writeErrorMsg =
                        mHandler.obtainMessage(MessageConstants.MESSAGE_TOAST);
                Bundle bundle = new Bundle();
                bundle.putString("toast",
                        "Couldn't send data to the other device");
                writeErrorMsg.setData(bundle);
                mHandler.sendMessage(writeErrorMsg);
            }
        }

        // Call this method from the main activity to shut down the connection.
        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Could not close the connect socket", e);
            }
        }
    }
}
