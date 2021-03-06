package com.amos.flyinn.nearbyservice;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.widget.Toast;

import com.amos.flyinn.ConnectionSetupActivity;
import com.amos.flyinn.R;
import com.amos.flyinn.ShowCodeActivity;
import com.amos.flyinn.summoner.ADBService;
import com.amos.flyinn.summoner.ConnectionSigleton;
import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;

import java.util.Objects;

/**
 * Manage nearby connections with a server.
 * <p>
 * After starting this service. This will actively advertise
 * the current device for nearby connections. A server can then
 * initiate a connection over nearby.
 */
public class NearbyService extends IntentService {
    public static final String TAG = "NearbyService";

    public static final String ACTION_START = "nearby_start";
    public static final String ACTION_STOP = "nearby_stop";
    public static final String VIDEO_START = "video_start";
    private static final String CHANNEL_ID = "flyinn_nearby";
    private static final int FOREGROUND_ID = 1;
    private static final int NOTIFY_ID = 2;

    private String nearbyCode = "";
    private NearbyServer server;

    /**
     * Define the serviceState of our service.
     */
    private NearbyState serviceState = NearbyState.STOPPED;

    public NearbyService() {
        super("NearbyService");
    }

    public static String[] getRequiredPermissions() {
        return NearbyServer.REQUIRED_PERMISSIONS;
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        Log.d(TAG, "Creating channel");
        createChannel();
        startForeground(FOREGROUND_ID, buildForegroundNotification(
                getString(R.string.notification_initialising), null));

        return super.onStartCommand(intent, flags, startId);
    }

    /**
     * Create a notification channel.
     * <p>
     * Notifications are organized into different channels to theoretically enable the user to
     * individually set what they want to be informed about.
     * <p>
     * This is required since Android 8.
     */
    private void createChannel() {
        NotificationManager mgr =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                mgr.getNotificationChannel(CHANNEL_ID) == null) {

            NotificationChannel c = new NotificationChannel(CHANNEL_ID,
                    "flyinn_channel", NotificationManager.IMPORTANCE_LOW);

            c.enableLights(true);
            c.setLightColor(0xFFFF0000);
            c.enableVibration(false);

            mgr.createNotificationChannel(c);
        }
    }

    /**
     * Create a sticky notification that won't go away.
     *
     * @param message String message shown in the notification.
     * @param target  Optional target intent to switch to after tapping the notification.
     * @return
     */
    private Notification buildForegroundNotification(String message, @Nullable Intent target) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID);

        b.setOngoing(true) // persistent notification
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setContentTitle(String.format("%s %s", getString(R.string.notification_name), nearbyCode))
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_notify_sync);

        /*
        // this code created new instances of the activities
        if (target != null) {
            TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
            stackBuilder.addNextIntentWithParentStack(target);
            PendingIntent pendingIntent = stackBuilder.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT);
            b.setContentIntent(pendingIntent);
        }
        */

        // open FlyInn when clicking on notification
        Intent intent = new Intent(this, ShowCodeActivity.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);

        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                intent, 0);
        b.setContentIntent(pendingIntent);

        return (b.build());
    }

    private void raiseNotification(Notification notification) {
        NotificationManager mgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        mgr.notify(NOTIFY_ID, notification);
    }

    /**
     * Create or update shown notification.
     *
     * @param message
     */
    public void notify(String message) {
        Intent intent = null;
        switch (serviceState) {
            case STOPPED:
                intent = new Intent(this, ShowCodeActivity.class);
                intent.putExtra("code", getNearbyCode());
                break;
            case ADVERTISING:
                intent = new Intent(this, ShowCodeActivity.class);
                intent.putExtra("code", getNearbyCode());
                break;
            case CONNECTING:
                intent = new Intent(this, ConnectionSetupActivity.class);
                intent.putExtra("code", getNearbyCode());
                break;
            case CONNECTED:
                intent = new Intent(this, ConnectionSetupActivity.class);
                intent.putExtra("code", getNearbyCode());
                break;
            default:
                Log.e(TAG, "Unknown state");
                break;
        }
        raiseNotification(buildForegroundNotification(message, intent));
    }

    /**
     * Activate the given activity
     *
     * @param cls Class of the target activity
     */
    private void switchActivity(Class<?> cls) {
        Intent intent = new Intent(this, cls);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    public NearbyState getServiceState() {
        return serviceState;
    }

    /**
     * Set state of the nearby service. This is used by the nearby server.
     *
     * @param state
     * @param message
     */
    public void setServiceState(NearbyState state, @Nullable String message) {
        // do extra things if we are switching state
        if (serviceState != state) {
            switch (serviceState) {
                case CONNECTING:
                    switchActivity(ConnectionSetupActivity.class);
                    break;
            }
            serviceState = state;
        }
        if (message != null) {
            notify(message);
        }
    }

    /**
     * Create an Intent to control NearbyService.
     *
     * @param state   Defined serviceState for the application as enum.
     * @param context
     * @return Intent containing desired application serviceState.
     */
    public static Intent createNearbyIntent(String state, Context context) {
        Intent intent = new Intent(context, NearbyService.class);
        intent.setAction(state);
        return intent;
    }

    public void handleResponse(boolean error, String message) {
        if (error) {
            Log.d(TAG, String.format("Error received: %s", message));
        } else {
            Log.d(TAG, String.format("Status update: %s", message));
        }
    }

    public void handlePayload(Payload payload) {
        Log.d(TAG, "Received payload");
        switch (payload.getType()) {
            case Payload.Type.STREAM:
                Log.d(TAG, "Payload is of type stream");
                Intent i = new Intent(this, ADBService.class);
                i.setAction("stream");
                ConnectionSigleton.getInstance().inputStream = Objects.requireNonNull(payload.asStream()).asInputStream();
                startService(i);
                Log.d(TAG, "Send payload to activity");
                break;
            default:
                Log.e(TAG, "Unknown payload type");
                break;
        }
    }

    public void handlePayloadTransferUpdate(PayloadTransferUpdate update) {
        Log.d(TAG, "Received transfer update");
    }

    /**
     * Start advertising Android nearby.
     */
    public void start() {
        if (serviceState == NearbyState.STOPPED) {
            Log.d(TAG, "Starting NearbyService");
            if (server == null) {
                try {
                    server = new NearbyServer(this);
                    server.start();
                } catch (SecurityException error) {
                    (new Handler(Looper.getMainLooper())).post(() -> Toast.makeText(
                            this.getApplicationContext(),
                            R.string.nearby_missing_permissions, Toast.LENGTH_LONG).show());
                    Log.e(TAG, "SecurityException when starting NearbyServer in NearbyService");
                    sendBroadcastMessage("exit");
                }
            }
        } else {
            Log.d(TAG, "NearbyService already started");
        }
    }

    /**
     * Stop advertising Android nearby.
     */
    public void stop() {
       try {
           server.stop();
       } catch (Exception ignored) {}
        if (serviceState != NearbyState.STOPPED) {
            Log.d(TAG, "Stopping NearbyService");
            serviceState = NearbyState.STOPPED;
        } else {
            Log.d(TAG, "NearbyService already stopped");
        }
    }

    /**
     * Handle intents for initiation of advertising and connection shutdown.
     *
     * @param intent An intent with a custom Extra field called action.
     */
    @Override
    protected void onHandleIntent(@Nullable Intent intent) {
        Log.d(TAG, "Handling intent now");
        if (intent.hasExtra("code")) {
            try {
                String code = intent.getStringExtra("code");
                setNearbyCode(code);
                Log.d(TAG, String.format("Setting code to %s", code));
            } catch (NullPointerException err) {
                Log.d(TAG, "Could not get code from intent.", err);
            }
        }
        try {
            Log.d(TAG, "Got action " + intent.getAction());
            switch (intent.getAction()) {
                case ACTION_START:
                    start();
                    break;
                case ACTION_STOP:
                    stop();
                    break;
                case VIDEO_START:
                    assert VideoStreamSingleton.getInstance().os != null;
                    Log.d(TAG, "Warped video_start stream");
                    Log.d(TAG, "Send stream to " + VideoStreamSingleton.getInstance().serverID);
                    Nearby.getConnectionsClient(this).sendPayload(VideoStreamSingleton.getInstance().serverID, Payload.fromStream(VideoStreamSingleton.getInstance().os));
                    Log.d(TAG, "Sent video_start stream");
                    break;
                default:
                    Log.d(TAG, "Unknown intent received. Will do nothing");
                    break;
            }
        } catch (NullPointerException error) {
            Log.d(TAG, "Null pointer for action received. Will do nothing", error);
        }
    }

    public void setNearbyCode(String code) {
        nearbyCode = code;
    }

    public String getNearbyCode() {
        return nearbyCode;
    }

    /**
     * Sends a message (to the start activity) via a LocalBroadcastManager.
     * Used to close or restart the app. Prepends package prefix.
     *
     * @param message The message sent to the activity as boolean.
     */
    public void sendBroadcastMessage (String message) {
        Intent intent = new Intent ("msg-flyinn");
        intent.putExtra("com.amos.flyinn." + message, true);
        Log.i(TAG, "NearbyService broadcasting message com.amos.flyinn." + message);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
}
