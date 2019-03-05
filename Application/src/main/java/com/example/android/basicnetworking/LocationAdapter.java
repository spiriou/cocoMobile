package com.example.android.basicnetworking;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.location.OnNmeaMessageListener;
import android.os.Bundle;
import android.util.Log;
import android.location.LocationListener;



public class LocationAdapter implements LocationListener { // }, OnNmeaMessageListener {
    interface OnLocationAdapterEventListener {
        public void onLocationEvent(Location location);
    }
    private static String TAG = "CoCoGnss";
    private LocationManager mLocationManager;
    private Location mLastLocation;
    private OnLocationAdapterEventListener mLocationListener;

    private LocationAdapter() {}

    public LocationAdapter(LocationManager locationManager,OnLocationAdapterEventListener listener) {
        super();
        mLocationManager = locationManager;
        mLocationListener = listener;
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        Log.i(TAG, "onStatusChanged "+provider+" "+Integer.toString(status));
    }
    @Override
    public void onProviderEnabled(String provider) {
        Log.i(TAG, "onProviderEnabled "+provider);
    }
    @Override
    public void onProviderDisabled(String provider) {
        Log.i(TAG, "onProviderDisabled "+provider);
    }

    /*
    @Override
    public void onNmeaMessage(String message, long timestamp) {
        Log.i(TAG, "GOT NMEA "+message+" "+Long.toString(timestamp));
    }
    */

    public void start() {
        Log.i(TAG, "START GET LOCATIONS");
        try {
            // Register the listener with the Location Manager to receive location updates
            mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to get Location permission");
        }
    }

    public void stop() {
        Log.i(TAG, "STOP GET LOCATIONS");
        mLocationManager.removeUpdates(this);
    }

    @Override
    public void onLocationChanged(Location location) {
        // Called when a new location is found by the network location provider.
        Log.i(TAG, "onLocationChanged");
        mLocationListener.onLocationEvent(location);
        mLastLocation = location;
        // Log.i(TAG, "GOT location "+location.toString());
    }

    public Location getLastLocation() {
        return mLastLocation;
    }
}
