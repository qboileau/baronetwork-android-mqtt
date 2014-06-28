package com.qboileau.mqtt;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.ToggleButton;


public class MainActivity extends Activity implements SensorEventListener, LocationListener {

    public static final String TOPIC_PRESSURE = "/sensor/pressure";
    public static final String EXTRA_HOST = "com.qboileau.mqtt.MQTT_HOST";
    public static final String EXTRA_PORT = "com.qboileau.mqtt.MQTT_PORT";
    public static final String EXTRA_TOPIC = "com.qboileau.mqtt.MQTT_TOPIC";
    public static final String EXTRA_MESSAGE = "com.qboileau.mqtt.MQTT_MESSAGE";

    //record only changes of pressure <= or >= DELTA
    private static final long DELTA = 1;

    //delay between two publish request
    private static final long DELAY = 5000;

    private LocationManager mLocationManager;
    private String mLocationProvider;
    private SensorManager mSensorManager;
    private Sensor mPressure;

    private Location mCurrentLocation;
    private float mCurrentSensorPressure = 0f;
    private float mCurrentPressure = 0f;
    private float mPressureCalibration = 0f;
    private boolean mMqttStarted = false;
    private boolean mPressureAvailable = false;
    private long mlastTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.mqtt_activity);

        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        mPressure = mSensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
        if (mPressure != null) {
            mSensorManager.registerListener(this, mPressure, SensorManager.SENSOR_DELAY_NORMAL);
            mPressureAvailable = true;
        }

        mLocationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (isLocationAvailable()) {
            mLocationProvider = LocationManager.GPS_PROVIDER;
        } else {
            mLocationProvider = LocationManager.NETWORK_PROVIDER;
        }
        mCurrentLocation = mLocationManager.getLastKnownLocation(mLocationProvider);

        mlastTime = System.currentTimeMillis();

        ToggleButton toggle = (ToggleButton) findViewById(R.id.toggleButton);
        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    startMQTTClient();
                } else {
                    stopMQTTClient();
                }
            }
        });

        final Context ctx = this;
        Button calibrate = (Button) findViewById(R.id.calibrateBtn);
        calibrate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                LayoutInflater layoutInflater = LayoutInflater.from(ctx);
                View calibrationView = layoutInflater.inflate(R.layout.calibration_view, null);

                final EditText input = (EditText) calibrationView.findViewById(R.id.userInput);
                input.setText(String.valueOf(mCurrentSensorPressure));

                AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
                builder.setTitle("Pressure calibration");
                builder.setView(calibrationView);
                builder.setCancelable(true);
                builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        float realValue = Float.parseFloat(input.getText().toString());
                        mPressureCalibration = realValue - mCurrentSensorPressure;
                    }
                }).setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.cancel();
                    }
                });

                builder.create().show();
            }
        });
    }

    private void startMQTTClient() {
        final TextView hostTF = (TextView) findViewById(R.id.hostTF);
        final TextView portTF = (TextView) findViewById(R.id.portTF);

        Intent publish = new Intent(this, MqttService.class);
        publish.setAction(MqttService.ACTION_START);
        Bundle bundle = new Bundle();
        bundle.putString(EXTRA_HOST, hostTF.getText().toString());
        bundle.putInt(EXTRA_PORT, Integer.valueOf(portTF.getText().toString()));
        publish.putExtras(bundle);
        startService(publish);
        mMqttStarted = true;
    }

    private void publish(String topic, String message) {
        if (mMqttStarted) {
            Intent publish = new Intent(this, MqttService.class);
            publish.setAction(MqttService.ACTION_PUBLISH);
            publish.putExtra(EXTRA_TOPIC, topic);
            publish.putExtra(EXTRA_MESSAGE, message);
            startService(publish);
        }
    }

    private void stopMQTTClient() {
        Intent publish = new Intent(this, MqttService.class);
        publish.setAction(MqttService.ACTION_STOP);
        startService(publish);
        mMqttStarted = false;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.mqtt, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            Intent i = new Intent(this, SettingsActivity.class);
            startActivity(i);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        // Register a listener for the sensor.
        super.onResume();
        if (mPressureAvailable) {
            mSensorManager.registerListener(this, mPressure, SensorManager.SENSOR_DELAY_NORMAL);
        }

        if (isLocationAvailable()) {
            mLocationManager.requestLocationUpdates(mLocationProvider, 400, 1, this);
        }
        startMQTTClient();
    }

    @Override
    protected void onPause() {
        // Be sure to unregister the sensor when the activity pauses.
        super.onPause();
        if (mPressureAvailable) {
            mSensorManager.unregisterListener(this);
        }

        if (isLocationAvailable()) {
            mLocationManager.removeUpdates(this);
        }
        stopMQTTClient();

    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mPressureAvailable) {
            mSensorManager.unregisterListener(this);
        }

        if (isLocationAvailable()) {
            mLocationManager.removeUpdates(this);
        }
        stopMQTTClient();
    }

    /* Sensor API */

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        mCurrentSensorPressure = sensorEvent.values[0];
        float pressure = fixedPressure(mCurrentSensorPressure);
        float diff = mCurrentPressure - pressure;
        if (diff <= DELTA || diff >= DELTA) {
            mCurrentPressure = pressure;
            final TextView textView = (TextView) findViewById(R.id.guiText);
            textView.setText(pressure + " hPa");

            long currentTime = System.currentTimeMillis();
            if (currentTime - mlastTime >= DELAY) {
                publish(TOPIC_PRESSURE, String.valueOf(mCurrentPressure));
                mlastTime = currentTime;
            }
        }
    }

    /**
     * Fix pressure from sensor with calibration difference.
     * @param sensorPressure
     * @return
     */
    private float fixedPressure(float sensorPressure) {
        return sensorPressure + mPressureCalibration;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {
    }

    /* Location API */

    private boolean isLocationAvailable() {
        return mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
    }

    @Override
    public void onLocationChanged(Location location) {
        this.mCurrentLocation = location;
        System.out.println(location);
    }

    @Override
    public void onStatusChanged(String s, int i, Bundle bundle) {
    }

    @Override
    public void onProviderEnabled(String s) {
    }

    @Override
    public void onProviderDisabled(String s) {
    }
}
