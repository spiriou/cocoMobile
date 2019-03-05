package com.example.android.basicnetworking;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.location.Location;
import com.google.android.gms.location.LocationListener;
import android.location.LocationManager;
import android.net.TrafficStats;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.SystemClock;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.telephony.CellIdentityCdma;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellInfo;
import android.telephony.CellInfoCdma;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellSignalStrengthCdma;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.CellSignalStrengthLte;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.Chronometer;
import android.os.Handler;

import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.nio.ByteBuffer;
import java.util.List;

public class TestService
        extends Service
        implements LocationAdapter.OnLocationAdapterEventListener {
    private static String TAG = "CoCoSvc";
    private static int mCocoUUID = 178;
    private static String CHANNEL_ID = "cocoChannelNotif";

    /* Service client management (only one client supported for now) */
    private IBinder mBinder = new MyBinder();
    final Messenger mMyMessenger = new Messenger(new IncomingHandler());
    private boolean mShouldStop = false;

    /* Periodic task management */
    private Handler mMobileHandler = new Handler(); // <= work queue
    private Runnable mMobileRunnable;
    private Runnable mMCocoFrameSenderRunnable;
    private Runnable mTestPingRunnable;
    int mPingCounter = 0;

    /* Mobile & location events management */
    TelephonyManager mTelephonyManager;
    LocationManager mLocationManager;
    NotificationManager mNotificationManager;
    LocationAdapter mLocationAdapter;

    /* Notification management */
    private static int NOTIFICATION_ID = 0xdeadb33f;

    /* Data collection structures */
    cocoProtobuf.CocoFrame.Builder mCocoFrameBuilder;
    private int mFrameCounter = 0;

    /* MQTT configuration */
    final String serverUri         = "tcp://51.15.68.109:8097";
    String clientId                = "cocoClient"+Integer.toString(mCocoUUID);
    final String subscriptionTopic = "/test/cocomob/reply/"+Integer.toString(mCocoUUID);
    final String publishTopic      = "/test/cocomob/frame/"+Integer.toString(mCocoUUID);
    MqttAndroidClient mqttAndroidClient;


    private boolean mIsMqttTaskEnabled;

    class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            Bundle data = msg.getData();
            String dataString = data.getString("MyString");
            Log.i(TAG, "Got string:"+dataString);
            int command = data.getInt("cmd");
            switch (command) {
                case 1:
                    // activateNotification();
                    break;
                case 2:
                    // deactivateNotification();
                    break;
                default:
                    Log.i(TAG, "Unhandled command "+Integer.toString(command));
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "in onCreate");

        mIsMqttTaskEnabled = false;
        activateNotification();

        mCocoFrameBuilder = cocoProtobuf.CocoFrame.newBuilder();

        // attachBaseContext(mBaseConext);
        mTelephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        /* Init GNSS adapter */
        mLocationAdapter = new LocationAdapter(mLocationManager, this);

        /* Register notification channel */
        createNotificationChannel();

        mTestPingRunnable = new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "Coco hello2 !! "+Integer.toString(mPingCounter));
                mPingCounter += 1;
                mMobileHandler.postDelayed(this, 1000);
            }
        };
        mMobileHandler.post(mTestPingRunnable);

        mMobileRunnable = new Runnable() {
            @Override
            public void run() {
                // Do something here on the main thread
                Log.i(TAG, "MOBILE task");
                mMobileHandler.postDelayed(this, 500);
                addMobileNetworkChunk();
            }
        };

        mMCocoFrameSenderRunnable = new Runnable() {
            @Override
            public void run() {
                mCocoFrameBuilder.setId(mFrameCounter);
                mCocoFrameBuilder.setTxTimestamp(System.currentTimeMillis());

                byte [] out_msg = mCocoFrameBuilder.build().toByteArray();
                out_msg = ByteBuffer.allocate(4 + 4 + out_msg.length)
                        .putInt(mFrameCounter)
                        .putInt(out_msg.length)
                        .put(out_msg)
                        .array();
                publishMessage(out_msg);

                Log.i(TAG, "MQTT sender "+Integer.toString(out_msg.length)+
                                 " bytes ("+Integer.toString(mFrameCounter)+")");

                mFrameCounter = (mFrameCounter+1)%8092;
                /* Clears all the fields back to the empty state */
                mCocoFrameBuilder.clear();
                /* Schedule next MQTT message publish */
                mMobileHandler.postDelayed(this, 2000);
            }
        };

        clientId = clientId + System.currentTimeMillis();

        mqttAndroidClient = new MqttAndroidClient(this, serverUri, clientId);
        if (mqttAndroidClient == null) {
            Log.e(TAG, "FATAL error, cannot create MQTT client");
        }
        else {
            Log.e(TAG, "Got mqtt client "+mqttAndroidClient.toString());
        }
        mqttAndroidClient.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {

                if (reconnect) {
                    Log.i(TAG,"Reconnected to : " + serverURI);
                    // Because Clean Session is true, we need to re-subscribe
                    subscribeToTopic();
                } else {
                    Log.i(TAG,"Connected to: " + serverURI);
                }

                if (! mIsMqttTaskEnabled) {
                    mIsMqttTaskEnabled = true;
                    mMobileHandler.post(mMCocoFrameSenderRunnable);
                }
            }

            @Override
            public void connectionLost(Throwable cause) {
                Log.i(TAG,"The Connection was lost.");
                mMobileHandler.removeCallbacks(mMCocoFrameSenderRunnable);
                mIsMqttTaskEnabled = false;
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) throws Exception {
                long timestamp = System.currentTimeMillis();

                // Log.i(TAG,"In messageArrived");

                try {

                    if (message.getPayload().length < 4) {
                        /* ERROR, invalid payload size */
                        Log.e(TAG, "Invalid payload size: " + Integer.toString(message.getPayload().length));
                        return;
                    }
                    cocoProtobuf.LatencyChunk.Builder latencyChunkBuilder = cocoProtobuf.LatencyChunk.newBuilder();

                    int frameId = ByteBuffer.wrap(message.getPayload(), 0, 4).getInt();
                    latencyChunkBuilder.setId(frameId);
                    latencyChunkBuilder.setLatency(timestamp);

                    Log.i(TAG, "Send latency reply " + Integer.toString(frameId) + " " + Long.toString(timestamp));
                    mCocoFrameBuilder.addLatency(latencyChunkBuilder.build());
                } catch (Exception ex){
                    Log.i(TAG, "ERROR exception "+ex.toString());
                    ex.printStackTrace();
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {

            }
        });
    }

    @Override
    public void onDestroy() {
        stopDataCollection();
        Log.v(TAG, "in onDestroy");

        super.onDestroy();

        // Intent intent = new Intent("com.example.android.basicnetworking");
        // intent.putExtra("yourvalue", "torestore");
        // sendBroadcast(intent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "in onBind");
        // return mBinder;
        return mMyMessenger.getBinder();
    }

    @Override
    public void onRebind(Intent intent) {
        Log.i(TAG, "in onRebind");
        super.onRebind(intent);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(TAG, "in onUnbind");
        return true;
    }

    public class MyBinder extends Binder {
        TestService getService() {
            return TestService.this;
        }
    }

    private void subscribeToTopic(){
        try {
            mqttAndroidClient.subscribe(subscriptionTopic, 0, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    Log.i(TAG,"Subscribed!");
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    Log.i(TAG,"Failed to subscribe");
                }
            });

        } catch (MqttException ex){
            System.err.println("Exception whilst subscribing");
            ex.printStackTrace();
        }
    }

    private void publishMessage(byte [] payload){

        try {
            MqttMessage message = new MqttMessage();
            message.setPayload(payload);
            mqttAndroidClient.publish(publishTopic, message);
            // Log.i(TAG,"Message Published");
            if(!mqttAndroidClient.isConnected()){
                Log.i(TAG,mqttAndroidClient.getBufferedMessageCount() + " messages in buffer.");
            }
        } catch (Exception e) { // MqttException e) {
            System.err.println("Error Publishing: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void createNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = getString(R.string.channel_name);
            String description = getString(R.string.channel_description);
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void makeNotification(Context context) {
        // Create an explicit intent for an Activity in your app
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(context,
                NOTIFICATION_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("My notification")
                .setContentText("Hello World!")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                // Set the intent that will fire when the user taps the notification
                .setContentIntent(pendingIntent);
                // .setAutoCancel(true);

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        Notification n;
        n = builder.build();

        startForeground(NOTIFICATION_ID, n);
        // instead of:
        // n.flags |= Notification.FLAG_NO_CLEAR | Notification.FLAG_ONGOING_EVENT;
        // notificationId is a unique int for each notification that you must define
        // notificationManager.notify(NOTIFICATION_ID, n);

        return;

/*
        Intent intent = new Intent(context, MainActivity.class);

        PendingIntent pendingIntent = PendingIntent.getActivity(context,
                NOTIFICATION_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder builder = new Notification.Builder(context)
                .setContentTitle("Notification Title")
                .setContentText("Sample Notification Content")
                .setContentIntent(pendingIntent)
                .setSmallIcon(R.drawable.ic_launcher)
                .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.drawable.ic_launcher))
                ;
        Notification n;

        n = builder.build();
        n.flags |= Notification.FLAG_NO_CLEAR | Notification.FLAG_ONGOING_EVENT;
        mNotificationManager.notify(NOTIFICATION_ID, n);
        */
    }

    /* Network service API */
    public void addMobileNetworkChunk() {
        List<CellInfo> cellLocations = null;
        try {
            cellLocations = mTelephonyManager.getAllCellInfo();
        } catch (SecurityException e) {
            return;
        }
        if (cellLocations == null) {
            return;
        }

        cocoProtobuf.NetChunk.Builder netChunkBuilder = cocoProtobuf.NetChunk.newBuilder();

        netChunkBuilder.setNetworkType(mTelephonyManager.getNetworkType());
        netChunkBuilder.setRxPackets(TrafficStats.getMobileRxPackets());
        netChunkBuilder.setTxPackets(TrafficStats.getMobileTxPackets());
        netChunkBuilder.setRxBytes(TrafficStats.getMobileRxBytes());
        netChunkBuilder.setTxBytes(TrafficStats.getMobileTxBytes());

        for (CellInfo info : cellLocations) {
            // Log.i(TAG, "=> "+info.toString());

            if (info instanceof CellInfoGsm) {
                /*
                final CellSignalStrengthGsm gsm = ((CellInfoGsm) info).getCellSignalStrength();
                final CellIdentityGsm identityGsm = ((CellInfoGsm) info).getCellIdentity();
                // Signal Strength
                pDevice.mCell.setDBM(gsm.getDbm()); // [dBm]
                // Cell Identity
                pDevice.mCell.setCID(identityGsm.getCid());
                pDevice.mCell.setMCC(identityGsm.getMcc());
                pDevice.mCell.setMNC(identityGsm.getMnc());
                pDevice.mCell.setLAC(identityGsm.getLac());
                */
            } else if (info instanceof CellInfoCdma) {
                /*
                final CellSignalStrengthCdma cdma = ((CellInfoCdma) info).getCellSignalStrength();
                final CellIdentityCdma identityCdma = ((CellInfoCdma) info).getCellIdentity();
                // Signal Strength
                pDevice.mCell.setDBM(cdma.getDbm());
                // Cell Identity
                pDevice.mCell.setCID(identityCdma.getBasestationId());
                pDevice.mCell.setMNC(identityCdma.getSystemId());
                pDevice.mCell.setLAC(identityCdma.getNetworkId());
                pDevice.mCell.setSID(identityCdma.getSystemId());
                */

            } else if (info instanceof CellInfoLte) {
                final CellSignalStrengthLte lte = ((CellInfoLte) info).getCellSignalStrength();
                final CellIdentityLte identityLte = ((CellInfoLte) info).getCellIdentity();

                cocoProtobuf.CellInfoLte.Builder cellLTE = cocoProtobuf.CellInfoLte.newBuilder();

                cellLTE.setTimestamp(info.getTimeStamp());
                cellLTE.setRegistered(info.isRegistered());

                // Signal Strength
                cellLTE.setCid((identityLte.getCi()));
                cellLTE.setEarfcn(identityLte.getEarfcn());
                cellLTE.setMcc(identityLte.getMcc());
                cellLTE.setMnc(identityLte.getMnc());
                cellLTE.setPci(identityLte.getPci());
                cellLTE.setTac(identityLte.getTac());

                cellLTE.setSs(lte.getDbm());
                cellLTE.setRsrp(lte.getRsrp());
                cellLTE.setRsrq(lte.getRsrq());
                cellLTE.setRssnr(lte.getRssnr());
                cellLTE.setCqi(lte.getCqi());
                cellLTE.setTa(lte.getTimingAdvance());
                cellLTE.setLevel(lte.getLevel());

                cocoProtobuf.NetChunk.CellInfo.Builder cellInfoBuilder = cocoProtobuf.NetChunk.CellInfo.newBuilder();
                cellInfoBuilder.setLte(cellLTE);
                netChunkBuilder.addCells(cellInfoBuilder);


/*
            } else if  (lCurrentApiVersion >= Build.VERSION_CODES.JELLY_BEAN_MR2 && info instanceof CellInfoWcdma) {
                final CellSignalStrengthWcdma wcdma = ((CellInfoWcdma) info).getCellSignalStrength();
                final CellIdentityWcdma identityWcdma = ((CellInfoWcdma) info).getCellIdentity();
                // Signal Strength
                pDevice.mCell.setDBM(wcdma.getDbm());
                // Cell Identity
                pDevice.mCell.setLAC(identityWcdma.getLac());
                pDevice.mCell.setMCC(identityWcdma.getMcc());
                pDevice.mCell.setMNC(identityWcdma.getMnc());
                pDevice.mCell.setCID(identityWcdma.getCid());
                pDevice.mCell.setPSC(identityWcdma.getPsc());
*/
            } else {
                Log.i(TAG, "Unknown type of cell signal!"
                        + "\n ClassName: " + info.getClass().getSimpleName()
                        + "\n ToString: " + info.toString());
            }
        }

        mCocoFrameBuilder.addNet(netChunkBuilder.build());
    }

    @Override
    public void onLocationEvent(Location location) {
        /* Generate a GNSS chunk */
        cocoProtobuf.GnssChunk.Builder gnssChunckBuilder = cocoProtobuf.GnssChunk.newBuilder();
        gnssChunckBuilder.setLatitude(location.getLatitude());
        gnssChunckBuilder.setLongitude(location.getLongitude());
        gnssChunckBuilder.setTimestamp(location.getTime());

        if (location.hasAltitude()) {
            gnssChunckBuilder.setAltitude(location.getAltitude());
        }
        if (location.hasAccuracy()) {
            gnssChunckBuilder.setAccuracy(location.getAccuracy());
        }
        if (location.hasBearing()) {
            gnssChunckBuilder.setBearing(location.getBearing());
        }
        if (location.hasSpeed()) {
            gnssChunckBuilder.setSpeed(location.getSpeed());
        }

        /* Insert GNSS chunk into cocoFrame */
        mCocoFrameBuilder.addGnss(gnssChunckBuilder.build());
    }

    /* Public service API */

    public String getLastLocationString() {
        try {
            return mLocationAdapter.getLastLocation().toString();
        } catch (Exception e) {
            return "None";
        }
    }

    public void activateNotification() {
        Log.i(TAG, "activateNotification!");
        makeNotification(this);
    }

    public void deactivateNotification() {
        Log.i(TAG, "deactivateNotification!");
        mNotificationManager.cancel(NOTIFICATION_ID);
    }

    public void startDataCollection() {

        /* Reset data collection */
        mCocoFrameBuilder = cocoProtobuf.CocoFrame.newBuilder();
        mLocationAdapter.start();
        mMobileHandler.post(mMobileRunnable);

        /* Connect to MQTT server */
        MqttConnectOptions mqttConnectOptions = new MqttConnectOptions();
        mqttConnectOptions.setAutomaticReconnect(true);
        mqttConnectOptions.setCleanSession(false);
        mqttConnectOptions.setUserName("cocomob");
        mqttConnectOptions.setPassword("cocopasswd".toCharArray());

        try {
            Log.i(TAG,"Connecting to " + serverUri);
            mqttAndroidClient.connect(mqttConnectOptions, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    DisconnectedBufferOptions disconnectedBufferOptions = new DisconnectedBufferOptions();
                    disconnectedBufferOptions.setBufferEnabled(true);
                    disconnectedBufferOptions.setBufferSize(100);
                    disconnectedBufferOptions.setPersistBuffer(false);
                    disconnectedBufferOptions.setDeleteOldestMessages(false);
                    mqttAndroidClient.setBufferOpts(disconnectedBufferOptions);
                    subscribeToTopic();
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    Log.i(TAG, "Failed to connect to: " + serverUri);
                    Log.i(TAG, exception.toString());
                    exception.printStackTrace();
                }
            });
        } catch (MqttException ex){
            ex.printStackTrace();
        }
    }

    public void stopDataCollection() {
        mLocationAdapter.stop();
        mMobileHandler.removeCallbacks(mMobileRunnable);
        mMobileHandler.removeCallbacks(mMCocoFrameSenderRunnable);

        mIsMqttTaskEnabled = false;

        try {
            if (mqttAndroidClient.isConnected()) {
                mqttAndroidClient.disconnect();
            }
        } catch (MqttException e) {

        }
    }
}
