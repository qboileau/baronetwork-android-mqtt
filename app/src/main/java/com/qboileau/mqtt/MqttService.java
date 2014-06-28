package com.qboileau.mqtt;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import org.meqantt.MqttException;
import org.meqantt.MqttListener;
import org.meqantt.SocketClient;
import org.meqantt.message.ConnAckMessage;

import java.io.IOException;

/**
 * Mqtt client service.
 *
 * Inspired from https://github.com/JesseFarebro/Android-Mqtt/blob/master/src/com/jessefarebro/mqtt/MqttService.java
 * using custom version of MQanTT mqtt client.
 * @author qboileau
 */
public class MqttService extends Service implements MqttListener {

    public static final String ACTION_START         = "START_MQTT"; // Action to start
    public static final String ACTION_STOP          = "STOP_MQTT"; // Action to stop
    public static final String ACTION_KEEPALIVE     = "KEEPALIVE_MQTT"; // Action to keep alive used by alarm manager
    public static final String ACTION_RECONNECT     = "RECONNECT_MQTT"; // Action to reconnect
    public static final String ACTION_PUBLISH       = "PUBLISH_MQTT"; // Action to reconnect
    public static final String ACTION_SUBSCRIBE     = "SUBSCRIBE_MQTT"; // Action to reconnect
    public static final String ACTION_UNSUBSCRIBE   = "UNSUBSCRIBE_MQTT"; // Action to reconnect

    private static final String LOG_TAG = MqttService.class.getCanonicalName();
    private static final String MQTT_THREAD_NAME = "THREAD_MQTT";
    private static final int KEEP_ALIVE = 5;
    private static final int TIMEOUT = 5000; //5s

    private Handler mHandler;
    private AlarmManager mAlarmManager;
    private ConnectivityManager mConnectivityManager;

    private String mDeviceId;
    private SocketClient mMqttClient;
    private String mHost;
    private Integer mPort;

    private final BroadcastReceiver mConnectivityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(LOG_TAG,"Connectivity Changed...");
            NetworkInfo info = mConnectivityManager.getActiveNetworkInfo();
            if (info != null && !info.isConnected()) {
                disconnect();
            }
        }
    };

    public MqttService() {
        super();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        Log.i(LOG_TAG,"Service created");
        mDeviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        mMqttClient = new SocketClient(mDeviceId);
        mMqttClient.addListener(this);

        HandlerThread thread = new HandlerThread(MQTT_THREAD_NAME);
        thread.start();
        mHandler = new Handler(thread.getLooper());

        // Do not set keep alive interval on mOpts we keep track of it with alarm's
        mAlarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
        mConnectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        String action = intent.getAction();
        if(action != null) {
            if(action.equals(ACTION_START)) {
                this.mHost = intent.getStringExtra(MainActivity.EXTRA_HOST);
                this.mPort = intent.getIntExtra(MainActivity.EXTRA_PORT, 1883);
                connect(mHost, mPort);
            } else if(action.equals(ACTION_STOP)) {
                disconnect();
            } else if(action.equals(ACTION_PUBLISH)) {
                final String topic = intent.getStringExtra(MainActivity.EXTRA_TOPIC);
                final String message = intent.getStringExtra(MainActivity.EXTRA_MESSAGE);
                publish(topic, message);
            } else if(action.equals(ACTION_SUBSCRIBE)) {
                final String topic = intent.getStringExtra(MainActivity.EXTRA_TOPIC);
                subscribe(topic);
            } else if(action.equals(ACTION_UNSUBSCRIBE)) {
                final String topic = intent.getStringExtra(MainActivity.EXTRA_TOPIC);
                unsubscribe(topic);
            } else if(action.equals(ACTION_KEEPALIVE)) {
                keepAlive();
            } else if(action.equals(ACTION_RECONNECT)) {
                if (mMqttClient != null && !mMqttClient.isConnected()) {
                    if (mHost != null && mPort != null) {
                        connect(mHost, mPort);
                    } else {
                        Log.e(LOG_TAG, "Can't reconnect before initial connection");
                    }
                }
            }
        }

        super.onStartCommand(intent, flags, startId);
        return START_REDELIVER_INTENT;
    }

    /**
     * Connect
     * @param host
     * @param port
     */
    private void connect(final String host, final int port) {
        if (mMqttClient != null && !mMqttClient.isConnected() && isOnline()) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Log.i(LOG_TAG, "Connect to " + host + ":" + port);
                        mMqttClient.connect(host, port, TIMEOUT, KEEP_ALIVE);
                        startKeepAlives();
                        registerReceiver(mConnectivityReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
                    } catch (MqttException e) {
                        Log.e(LOG_TAG, e.getMessage(), e);
                        Toast.makeText(getBaseContext(), "Failed to connect to " + host + ":" + port, Toast.LENGTH_LONG).show();
                    }
                }
            });
        }
    }

    private void disconnect() {
        if (mMqttClient != null && mMqttClient.isConnected()) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Log.i(LOG_TAG, "Close connection");
                        mMqttClient.disconnect();
                        unregisterReceiver(mConnectivityReceiver);
                        stopKeepAlives();
                    } catch (MqttException e) {
                        Log.e(LOG_TAG, e.getMessage(), e);
                    }
                }
            });
        }
    }

    private void publish(final String topic, final String message) {
        if (mMqttClient != null && mMqttClient.isConnected() && isOnline()) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Log.i(LOG_TAG, "Send message on topic : " + topic);
                        mMqttClient.publish(topic, message);
                    } catch (IOException e) {
                        Log.e(LOG_TAG, e.getMessage(), e);
                    }
                }
            });
        } else {
            Log.e(LOG_TAG, "Client not connected");
        }
    }

    private void keepAlive() {
        if (mMqttClient != null && mMqttClient.isConnected() && isOnline()) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Log.d(LOG_TAG, "Ping server");
                        mMqttClient.ping();
                    } catch (MqttException e) {
                        Log.e(LOG_TAG, e.getMessage(), e);
                    }
                }
            });
        } else {
            Log.e(LOG_TAG, "Client not connected");
        }
    }

    private void subscribe(final String topic) {
        if (mMqttClient != null && mMqttClient.isConnected() && isOnline()) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Log.i(LOG_TAG, "Subscribe on topic : " + topic);
                        mMqttClient.subscribe(topic);
                    } catch (IOException e) {
                        Log.e(LOG_TAG, e.getMessage(), e);
                    }
                }
            });
        } else {
            Log.e(LOG_TAG, "Client not connected");
        }
    }

    private void unsubscribe(final String topic) {
        if (mMqttClient != null && mMqttClient.isConnected() && isOnline()) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Log.i(LOG_TAG, "Unsubscribe on topic : " + topic);
                        mMqttClient.unsubscribe(topic);
                    } catch (IOException e) {
                        Log.e(LOG_TAG, e.getMessage(), e);
                    }
                }
            });
        } else {
            Log.e(LOG_TAG, "Client not connected");
        }
    }

    /**
     * Program ping requests
     */
    private void startKeepAlives() {
        Intent i = new Intent();
        i.setClass(this, MqttService.class);
        i.setAction(ACTION_KEEPALIVE);
        PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
        mAlarmManager.setRepeating(AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + KEEP_ALIVE * 1000,
                KEEP_ALIVE * 1000, pi);
    }

    /**
     * Remove all scheduled ping requests
     */
    private void stopKeepAlives() {
        Intent i = new Intent();
        i.setClass(this, MqttService.class);
        i.setAction(ACTION_KEEPALIVE);
        PendingIntent pi = PendingIntent.getService(this, 0, i , 0);
        mAlarmManager.cancel(pi);
    }

    private boolean isOnline() {
        NetworkInfo info = mConnectivityManager.getActiveNetworkInfo();
        return (info != null) && info.isConnected();
    }

    @Override
    public void connectAck(ConnAckMessage.ConnectionStatus connectionStatus) {
    }

    @Override
    public void disconnected() {
    }

    @Override
    public void publishArrived(String s, byte[] bytes) {
        Log.i(LOG_TAG, "New message on topic ("+s+") : "+String.valueOf(bytes));
    }
}
