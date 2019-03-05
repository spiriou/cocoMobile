/*
 * Copyright 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.basicnetworking;

import android.Manifest;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.support.v4.content.ContextCompat;
import android.content.pm.PackageManager;
import android.support.v4.app.ActivityCompat;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.support.v4.app.FragmentActivity;
import android.view.Menu;
import android.view.MenuItem;

import com.example.android.common.logger.Log;
import com.example.android.common.logger.LogFragment;
import com.example.android.common.logger.LogWrapper;
import com.example.android.common.logger.MessageOnlyLogFilter;

import android.os.Handler;

import android.content.Intent;
import android.os.IBinder;
import android.content.ServiceConnection;
import android.content.ComponentName;

import com.example.android.basicnetworking.TestService.MyBinder;
import com.google.android.gms.location.LocationServices;

/**
 * Sample application demonstrating how to test whether a device is connected,
 * and if so, whether the connection happens to be wifi or mobile (it could be
 * something else).
 *
 * This sample uses the logging framework to display log output in the log
 * fragment (LogFragment).
 */


public class MainActivity extends FragmentActivity implements ActivityCompat.OnRequestPermissionsResultCallback {

    public static final String TAG = "CoCoApp";

    // TestService mBoundService;
    Messenger mBoundService = null;
    boolean mServiceBound = false;
    boolean mIsDataCollectStarted = false;

    // Reference to the fragment showing events, so we can clear it with a button
    // as necessary.
    private LogFragment mLogFragment;
    private Handler mHandler;

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        Log.i(TAG, "PERMISSION CHECK "+Integer.toString(requestCode)+" "+permissions);
    }

    private boolean checkMobilePermissions() {
        String[] permList = new String[] {
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.WAKE_LOCK
        };


        // for String perm : permList){
        for (int i = 0; i < permList.length; i++) {
            try {
                if (ContextCompat.checkSelfPermission(this, permList[i])
                        != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, new String[]{permList[i]}, i);
                }
            } catch (Exception ex) {
                Log.e(TAG, "Error requesting perm "+permList[i]+": " + ex.getMessage());
                return false;

            }
        }
        return true;
    }

    private ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i(TAG, "onServiceDisconnected");
            mServiceBound = false;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "onServiceConnected");
            // MyBinder myBinder = (MyBinder) service;
            // Log.i(TAG, myBinder.toString());
            Log.i(TAG, service.toString());
            mBoundService = new Messenger(service);
            // mBoundService = myBinder.getService();
            mServiceBound = true;
        }
    };
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.sample_main);

        // Initialize the logging framework.
        initializeLogging();

        if (!checkMobilePermissions()) {
            Log.e(TAG, "ERROR: permission check failed");
        }

        /* Bind to service */
        Log.i(TAG, "Try start service");
        Intent intent = new Intent(this, TestService.class);
        startForegroundService(intent); // Does nothing if service already started
        bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
    }
    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.i(TAG, "onStop !!!");
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.i(TAG, "onPause !!!");
        // mConnectionClassManager.remove(mListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "onResume !!!");
        // mConnectionClassManager.register(mListener);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    private int sendMessage(int cmd)
    {
        if (!mServiceBound) {
            return -1;
        }

        Message msg = Message.obtain();

        Bundle bundle = new Bundle();
        bundle.putString("MyString", "Message Received");
        bundle.putInt("cmd", cmd);
        msg.setData(bundle);

        try {
            mBoundService.send(msg);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return 0;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.quit_action:
                Log.i(TAG, "onQuit, stop service ...");
                if (mServiceBound) {
                    unbindService(mServiceConnection);
                    mServiceBound = false;
                }
                Intent intent = new Intent(this, TestService.class);
                stopService(intent);

                // finish();
                return true;

            case R.id.start_stop_action:
                if (!mServiceBound) {
                    Log.w(TAG, "Service not bound !!!");
                    return true;
                }
                if (mIsDataCollectStarted) {
                    Log.i(TAG, "Try stop service");
                    // mBoundService.stopDataCollection();
                    // mBoundService.deactivateNotification();
                    sendMessage(2);
                    item.setTitle(R.string.start_service);
                }
                else {
                    Log.i(TAG, "Try start service");
                    // mBoundService.startDataCollection();
                    // mBoundService.activateNotification();
                    sendMessage(1);
                    item.setTitle(R.string.stop_service);
                }
                mIsDataCollectStarted = !mIsDataCollectStarted;
                return true;
            // Clear the log view fragment.
            // case R.id.clear_action:
            //     mLogFragment.getLogView().setText("");
            //     return true;
        }
        return false;
    }

    /**
     * Check whether the device is connected, and if so, whether the connection
     * is wifi or mobile (it could be something else).
     */
    private void checkNetworkConnection() {
        if (mServiceBound) {
            Log.i(TAG, "Last location: "); // +mBoundService.getLastLocationString());
            // Log.i(TAG, "RX bytes: "+Long.toString(mBoundService.getCurrentCell()));
        }
    }

    /** Create a chain of targets that will receive log data */
    public void initializeLogging() {

        // Using Log, front-end to the logging chain, emulates
        // android.util.log method signatures.

        // Wraps Android's native log framework
        LogWrapper logWrapper = new LogWrapper();
        Log.setLogNode(logWrapper);

        // A filter that strips out everything except the message text.
        MessageOnlyLogFilter msgFilter = new MessageOnlyLogFilter();

        logWrapper.setNext(msgFilter);

        // On screen logging via a fragment with a TextView.
        mLogFragment =
                (LogFragment) getSupportFragmentManager().findFragmentById(R.id.log_fragment);
        msgFilter.setNext(mLogFragment.getLogView());
    }
}
