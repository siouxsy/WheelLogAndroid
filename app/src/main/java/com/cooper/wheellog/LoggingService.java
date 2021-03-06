package com.cooper.wheellog;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.widget.Toast;

import com.cooper.wheellog.utils.Constants;
import com.cooper.wheellog.utils.FileUtil;
import com.cooper.wheellog.utils.PermissionsUtil;
import com.cooper.wheellog.utils.SettingsUtil;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import timber.log.Timber;

public class LoggingService extends Service
{
    private static LoggingService instance = null;
    SimpleDateFormat sdf;
    private String filename;
    private Location mLocation;
    private Location mLastLocation;
    private double mLocationDistance;
    private LocationManager mLocationManager;
    private String mLocationProvider = LocationManager.NETWORK_PROVIDER;
    private boolean logLocationData = false;
    private File file;

    public static boolean isInstanceCreated() {
        return instance != null;
    }

    @SuppressWarnings("MissingPermission")
    private final BroadcastReceiver mBluetoothUpdateReceiver = new BroadcastReceiver()
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            String action = intent.getAction();
            switch (action) {
                case Constants.ACTION_BLUETOOTH_CONNECTION_STATE:
                    if (mLocationManager != null && logLocationData) {
                        int connectionState = intent.getIntExtra(Constants.INTENT_EXTRA_CONNECTION_STATE, BluetoothLeService.STATE_DISCONNECTED);
                        if (connectionState == BluetoothLeService.STATE_CONNECTED) {
                            mLocationManager.requestLocationUpdates(mLocationProvider, 250, 0, locationListener);
                        } else {
                            mLocationManager.removeUpdates(locationListener);
                        }
                    }
                    break;
                case Constants.ACTION_WHEEL_DATA_AVAILABLE:
                    updateFile();
                    break;
            }
        }
    };

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        instance = this;
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Constants.ACTION_WHEEL_DATA_AVAILABLE);
        intentFilter.addAction(Constants.ACTION_BLUETOOTH_CONNECTION_STATE);
        registerReceiver(mBluetoothUpdateReceiver, intentFilter);

        if (!PermissionsUtil.checkExternalFilePermission(this)) {
            showToast(R.string.logging_error_no_storage_permission);
            stopSelf();
            return START_STICKY;
        }

        if (!isExternalStorageReadable() || !isExternalStorageWritable()) {
            showToast(R.string.logging_error_storage_unavailable);
            stopSelf();
            return START_STICKY;
        }

        logLocationData = SettingsUtil.isLogLocationEnabled(this);

        if (logLocationData && !PermissionsUtil.checkLocationPermission(this)) {
            showToast(R.string.logging_error_no_location_permission);
            logLocationData = false;
        }

        sdf = new SimpleDateFormat("yyyy-MM-dd,HH:mm:ss.SSS", Locale.US);

        SimpleDateFormat sdFormatter = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US);

        filename = sdFormatter.format(new Date()) + ".csv";
        file = FileUtil.getFile(filename);

        if (file == null) {
            stopSelf();
            return START_STICKY;
        }

        if (logLocationData) {
            mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

            // Getting GPS Provider status
            boolean isGPSEnabled = mLocationManager
                    .isProviderEnabled(LocationManager.GPS_PROVIDER);

            // Getting Network Provider status
            boolean isNetworkEnabled = mLocationManager
                    .isProviderEnabled(LocationManager.NETWORK_PROVIDER);

            // Getting if the users wants to use GPS
            boolean useGPS = SettingsUtil.isUseGPSEnabled(this);

            if (!isGPSEnabled && !isNetworkEnabled) {
                logLocationData = false;
                mLocationManager = null;
                showToast(R.string.logging_error_all_location_providers_disabled);
            } else if (useGPS && !isGPSEnabled) {
                useGPS = false;
                showToast(R.string.logging_error_gps_disabled);
            } else if (!useGPS && !isNetworkEnabled) {
                logLocationData = false;
                mLocationManager = null;
                showToast(R.string.logging_error_network_disabled);
            }

            if (logLocationData) {
                FileUtil.writeLine(filename, "date,time,latitude,longitude,location_distance,speed,voltage,current,power,battery_level,distance,totaldistance,battery_temp,system_temp,tilt,roll,mode,alert");
                mLocation = getLastBestLocation();
                mLocationProvider = LocationManager.NETWORK_PROVIDER;
                if (useGPS)
                    mLocationProvider = LocationManager.GPS_PROVIDER;
                // Acquire a reference to the system Location Manager
                mLocationManager.requestLocationUpdates(mLocationProvider, 250, 0, locationListener);
            } else
                FileUtil.writeLine(filename, "date,time,speed,voltage,current,power,battery_level,distance,totaldistance,battery_temp,system_temp,tilt,roll,mode,alert");
        }
        else
            FileUtil.writeLine(filename, "date,time,speed,voltage,current,power,battery_level,distance,totaldistance,battery_temp,system_temp,tilt,roll,mode,alert");

        Intent serviceIntent = new Intent(Constants.ACTION_LOGGING_SERVICE_TOGGLED);
        serviceIntent.putExtra(Constants.INTENT_EXTRA_LOGGING_FILE_LOCATION, file.getAbsolutePath());
        serviceIntent.putExtra(Constants.INTENT_EXTRA_IS_RUNNING, true);
        sendBroadcast(serviceIntent);

        Timber.i("DataLogger Started");

        return START_STICKY;
    }

    @SuppressWarnings("MissingPermission")
    @Override
    public void onDestroy() {
        if (file != null) {
            Intent serviceIntent = new Intent(Constants.ACTION_LOGGING_SERVICE_TOGGLED);
            serviceIntent.putExtra(Constants.INTENT_EXTRA_LOGGING_FILE_LOCATION, file.getAbsolutePath());
            serviceIntent.putExtra(Constants.INTENT_EXTRA_IS_RUNNING, false);
            sendBroadcast(serviceIntent);
        }
        instance = null;
        unregisterReceiver(mBluetoothUpdateReceiver);
        if (mLocationManager != null && logLocationData)
            mLocationManager.removeUpdates(locationListener);

        if (SettingsUtil.isAutoUploadEnabled(this)) {
            Intent uploadIntent = new Intent(getApplicationContext(), GoogleDriveService.class);
            uploadIntent.putExtra(Constants.INTENT_EXTRA_LOGGING_FILE_LOCATION, file.getAbsolutePath());
            startService(uploadIntent);
        }

        Timber.i("DataLogger Stopped");
    }

    /* Checks if external storage is available for read and write */
    public boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        return Environment.MEDIA_MOUNTED.equals(state);
    }

    /* Checks if external storage is available to at least read */
    public boolean isExternalStorageReadable() {
        String state = Environment.getExternalStorageState();
        return Environment.MEDIA_MOUNTED.equals(state) ||
                Environment.MEDIA_MOUNTED_READ_ONLY.equals(state);
    }

    private void updateFile() {
        if (logLocationData) {
            String longitude = "";
            String latitude = "";
            if (mLocation != null) {
                longitude = String.valueOf(mLocation.getLongitude());
                latitude = String.valueOf(mLocation.getLatitude());

                if (mLastLocation != null)
                    mLocationDistance += mLastLocation.distanceTo(mLocation) / 1000.0;

                mLastLocation = mLocation;
            }
            FileUtil.writeLine(filename,
                    String.format(Locale.US, "%s,%s,%s,%.2f,%.2f,%.2f,%.2f,%.2f,%d,%d,%d,%d,%d,%.2f,%.2f,%s,%s",
                            sdf.format(new Date()),
                            latitude,
                            longitude,
                            mLocationDistance,
                            WheelData.getInstance().getSpeedDouble(),
                            WheelData.getInstance().getVoltageDouble(),
                            WheelData.getInstance().getCurrentDouble(),
                            WheelData.getInstance().getPowerDouble(),
                            WheelData.getInstance().getBatteryLevel(),
                            WheelData.getInstance().getDistance(),
							WheelData.getInstance().getTotalDistance(),
                            WheelData.getInstance().getTemperature(),
							WheelData.getInstance().getTemperature2(),
							WheelData.getInstance().getAngle(),
							WheelData.getInstance().getRoll(),
							WheelData.getInstance().getModeStr(),
							WheelData.getInstance().getAlert()
                    ));
        } else {
            FileUtil.writeLine(filename,
                    String.format(Locale.US, "%s,%.2f,%.2f,%.2f,%.2f,%d,%d,%d,%d,%d,%.2f,%.2f,%s,%s",
                            sdf.format(new Date()),
                            WheelData.getInstance().getSpeedDouble(),
                            WheelData.getInstance().getVoltageDouble(),
                            WheelData.getInstance().getCurrentDouble(),
                            WheelData.getInstance().getPowerDouble(),
                            WheelData.getInstance().getBatteryLevel(),
                            WheelData.getInstance().getDistance(),
							WheelData.getInstance().getTotalDistance(),
                            WheelData.getInstance().getTemperature(),
							WheelData.getInstance().getTemperature2(),
							WheelData.getInstance().getAngle(),
							WheelData.getInstance().getRoll(),
							WheelData.getInstance().getModeStr(),
							WheelData.getInstance().getAlert()
                    ));
        }
    }

    // Define a listener that responds to location updates
    LocationListener locationListener = new LocationListener() {
        public void onLocationChanged(Location location) {
            // Called when a new location is found by the network location provider.
            mLocation = location;
        }

        public void onStatusChanged(String provider, int status, Bundle extras) {}

        public void onProviderEnabled(String provider) {}

        public void onProviderDisabled(String provider) {}
    };

    @SuppressWarnings("MissingPermission")
    private Location getLastBestLocation() {

        Location locationGPS = mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        Location locationNet = mLocationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);

        long GPSLocationTime = 0;
        if (null != locationGPS) { GPSLocationTime = locationGPS.getTime(); }

        long NetLocationTime = 0;

        if (null != locationNet) {
            NetLocationTime = locationNet.getTime();
        }

        if ( 0 < GPSLocationTime - NetLocationTime ) {
            return locationGPS;
        }
        else {
            return locationNet;
        }
    }

    private void showToast(int message_id) {
        for (int i = 0; i <= 3; i++)
            Toast.makeText(this, message_id, Toast.LENGTH_LONG).show();
    }
}