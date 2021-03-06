package com.amos.server.nearby;

import android.content.Context;
import android.os.Build;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.SparseArray;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Singleton managing Android nearby connection on the server side
 * <p>
 * The server needs to send input events to the client and correctly receive the recorded screen.
 */
@SuppressWarnings("WeakerAccess")
public class ServerConnection implements PayloadHandling {

    private static final ServerConnection ourInstance = new ServerConnection();

    /**
     * 1-to-1 since a device will be connected to only one other device at most.
     */
    private static final Strategy STRATEGY = Strategy.P2P_POINT_TO_POINT;

    private final String serverName = generateName();

    private boolean connected = false;

    private String clientID;

    /**
     * List of all discovered servers by name, continuously updated.
     */
    private List<String> clients = new ArrayList<>();

    /**
     * Maps server names to their nearby connection IDs.
     */
    private HashMap<String, String> clientNamesToIDs = new HashMap<>();

    /**
     * Maps server IDs to their nearby connection names.
     */
    private HashMap<String, String> clientIDsToNames = new HashMap<>();

    /**
     * Tag for logging purposes.
     */
    private static final String TAG = "NearbyServerConnection";

    /**
     * Connection manager for the connection to FlyInn clients.
     */
    private ConnectionsClient connectionsClient;

    private SparseArray<HandlePayload> handleMap = new SparseArray<>();

    public static ServerConnection getInstance() {
        return ourInstance;
    }


    /**
     * Create a new server connection.
     */
    private ServerConnection() {
        // All real work is in the init!
    }

    /**
     * Bind application context to the singleton.
     *
     * @param ctx Application context should be passed to ensure survival between different activities.
     */
    public void init(Context ctx) {
        connectionsClient = Nearby.getConnectionsClient(ctx);
    }

    @Override
    public void addHandle(int type, HandlePayload handle) {
        handleMap.put(type, handle);
    }

    @Override
    public HandlePayload getHandle(int type) {
        return handleMap.get(type);
    }

    @Override
    public void handlePayload(Payload payload) {
        Log.d(TAG, String.format("Handling payload %d", payload.getId()));
        HandlePayload handle = getHandle(payload.getType());
        if (handle != null) {
            Log.d(TAG, "Payload handled by receiver");
            handle.receive(payload);
        } else {
            switch(payload.getType()) {
                case Payload.Type.BYTES:
                    /* Todo: Use and apply received configuration JSON string */
                    Log.d(TAG, "Received configuration");
                    break;
                case Payload.Type.STREAM:
                    Log.d(TAG, "Received unregistered stream");
                    break;
                default:
                    Log.d(TAG, "Unknown payload type received");
                    break;
            }
        }
    }

    @Override
    public void handlePayloadUpdate(PayloadTransferUpdate update) {
        Log.d(TAG, String.format("Update for payload %d", update.getPayloadId()));
    }


    /**
     * Discover new endpoints
     * @param callback Callbacks for success / Failure in discovery
     * @param discoveryCallback Discovery callbacks to act on discovered entpoints
     */
    public void discover(ConnectCallback callback, EndpointDiscoveryCallback discoveryCallback) {
        if (connectionsClient == null) {
            Log.e(TAG, "connectionsClient is null, cannot discover.");
            return;
        }
        resetDiscovery();

        DiscoveryOptions discoveryOptions =
                new DiscoveryOptions.Builder().setStrategy(STRATEGY).build();

        connectionsClient.startDiscovery("com.amos.server", discoveryCallback,
                discoveryOptions)
                .addOnSuccessListener((Void unused) -> Log.e(TAG, "Success discover"))
                .addOnFailureListener((Exception e) -> {
                    Log.e(TAG, "Failed to discover");
                    callback.failure("Failed to discover");
                });
    }

    public List<String> getClients() {
        return clients;
    }

    public String getClientID() {
        return clientID;
    }

    /**
     * Combined attempt at connecting or initiating discovery if failed.
     * @param code Suffix of endpoint name to connect to
     * @param callback Callbacks for success/failure
     */
    public void discoverConnect(String code, ConnectCallback callback) {
        connectTo(code, new ConnectCallback() {
            @Override
            public void success(String message) {
                callback.success(message);
            }

            @Override
            public void failure(String message) {
                discover(callback, buildEndpointDiscoveryCallback(code, callback));
            }
        });
    }

    /**
     * Connect to nearby service with client name ending with our required code.
     *
     * @param code     Suffix of connection target
     * @param callback Callbacks on connection success and failure
     */
    public void connectTo(String code, ConnectCallback callback) {
        if (connectionsClient == null) {
            Log.e(TAG, "connectionsClient is null, cannot discover.");
            return;
        }
        String searchedClientName = null;
        for (String clientName : clients) {
            if (clientName.endsWith(code)) {
                searchedClientName = clientName;
                break;
            }
        }

        String endpoint = clientNamesToIDs.get(searchedClientName);
        if (endpoint != null && searchedClientName != null) {
            clientID = endpoint;
            // callback success will be called in the subsequent function
            connectionsClient.requestConnection(searchedClientName, endpoint, buildConnectionLifecycleCallback(callback));
        } else {
            callback.failure("Did not find endpoint");
        }

    }

    public void abort() {
        connectionsClient.stopAllEndpoints();
        resetDiscovery();
        resetClientData();
    }

    public PipedOutputStream sendStream() throws IOException {
        if (connectionsClient == null) {
            Log.e(TAG, "connectionsClient is null, cannot create stream.");
            throw new IOException("connectionsClient is null, cannot create stream.");
        }
        PipedInputStream stream = new PipedInputStream();
        PipedOutputStream data = new PipedOutputStream(stream);
        Payload payload = Payload.fromStream(stream);
        connectionsClient.sendPayload(clientID, payload);
        Log.d(TAG, "Sent test payload to receiver " + clientID);
        return data;
    }

    /**
     * Obtain data from clientID/serverName and data transfer information via this handle.
     */
    private final PayloadCallback payloadCallback =
            new PayloadCallback() {
                @Override
                public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
                    Log.d(TAG, "Payload received from " + endpointId);
                    handlePayload(payload);
                }

                @Override
                public void onPayloadTransferUpdate(@NonNull String endpointId, @NonNull PayloadTransferUpdate update) {
                    Log.d(TAG, "Payload transfer update from " + endpointId);
                    handlePayloadUpdate(update);
                }
            };

    /**
     * Handling of discovered endpoints (servers). Adds new endpoints to servers data maps/list,
     * and removes lost endpoints.
     */
    public EndpointDiscoveryCallback buildEndpointDiscoveryCallback(String name, ConnectCallback callback) {
        return new EndpointDiscoveryCallback() {
            @Override
            public void onEndpointFound(@NonNull String endpointId, @NonNull DiscoveredEndpointInfo info) {
                String endpointName = info.getEndpointName();

                if (!(clientIDsToNames.containsKey(endpointId)
                        || clientNamesToIDs.containsKey(endpointName))) {
                    clients.add(endpointName);
                    clientNamesToIDs.put(endpointName, endpointId);
                    clientIDsToNames.put(endpointId, endpointName);
                    Log.i(TAG, serverName + " discovered endpoint " + endpointId + " with name " + endpointName);

                } else {
                    // this should not happen
                    while (true) {
                        if (!clients.remove(endpointName)) break;
                    }
                    clients.add(endpointName);
                    clientIDsToNames.put(endpointId, endpointName);
                    clientNamesToIDs.put(endpointName, endpointId);
                    Log.i(TAG, serverName + " rediscovered endpoint " + endpointId + " with name " + endpointName);
                }

                if (endpointName.endsWith(name)) {
                    clientID = endpointId;
                    connectionsClient.requestConnection(endpointName, endpointId, buildConnectionLifecycleCallback(callback));
                }
            }

            @Override
            public void onEndpointLost(@NonNull String endpointId) {
                // previously discovered server is no longer reachable, remove from data maps
                String lostEndpointName = clientIDsToNames.get(endpointId);
                clients.remove(lostEndpointName);
                clientIDsToNames.remove(endpointId);
                clientNamesToIDs.remove(lostEndpointName);
                Log.i(TAG, serverName + " lost discovered endpoint " + endpointId);
            }
        };
    }

    private ConnectionLifecycleCallback buildConnectionLifecycleCallback(ConnectCallback callback) {
        return new ConnectionLifecycleCallback() {
            @Override
            public void onConnectionInitiated(@NonNull String endpointId, @NonNull ConnectionInfo connectionInfo) {
                Log.i(TAG, "Connection initiated to " + endpointId);

                if (endpointId.equals(clientID)) {
                    connectionsClient.acceptConnection(endpointId, payloadCallback);
                } else {
                    // initiated connection is not with server selected by user
                    connectionsClient.rejectConnection(endpointId);
                    Log.i(TAG, "Connection rejected to non-selected server "
                            + endpointId);
                }
            }

            @Override
            public void onConnectionResult(@NonNull String endpointId, @NonNull ConnectionResolution result) {
                switch (result.getStatus().getStatusCode()) {
                    case ConnectionsStatusCodes.STATUS_OK:
                        // successful connection with server
                        Log.i(TAG, "Connected with " + endpointId);
                        resetDiscovery();
                        connected = true;
                        callback.success("Connected with " + endpointId);
                        break;
                    case ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED:
                        // connection was rejected by one side (or both)
                        Log.i(TAG, "Connection rejected with " + endpointId);
                        clientID = null;
                        connected = false;
                        callback.failure("Connection rejected");
                        break;
                    case ConnectionsStatusCodes.STATUS_ERROR:
                        // connection was lost
                        Log.w(TAG, "Connection lost: " + endpointId);
                        clientID = null;
                        connected = false;
                        callback.failure("Connection lost");
                        break;
                    default:
                        // unknown status code. we shouldn't be here
                        Log.e(TAG, "Unknown error when attempting to connect with "
                                + endpointId);
                        connected = false;
                        clientID = null;
                        callback.failure("Connection failure unknown");
                        break;
                }
            }

            @Override
            public void onDisconnected(@NonNull String endpointId) {
                // disconnected from server
                Log.i(TAG, "Disconnected from " + endpointId);
                resetClientData();
                connected = false;
                callback.failure("Disconnected");
            }
        };
    }

    /**
     * Generates a name for the server.
     * <p>
     * TODO: Create a better name for the server
     *
     * @return The server name, consisting of the build model + a random string
     */
    private String generateName() {
        String AB = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        SecureRandom rnd = new SecureRandom();

        StringBuilder sb = new StringBuilder(5);
        for (int i = 0; i < 5; i++) {
            sb.append(AB.charAt(rnd.nextInt(AB.length())));
        }

        String name = Build.MODEL + "_" + sb.toString();
        Log.i(TAG, "Current name is: " + name);
        return name;
    }

    public boolean isConnected() {
        return connected;
    }

    /**
     * Clears serverName/serverID and all server data maps as well as the servers list
     */
    private void resetClientData() {
        clientID = null;
    }

    /**
     * Handle established connection with app.
     * <p>
     * Clears servers data maps, stops discovery of new servers and adds close connection button
     */
    private void resetDiscovery() {
        connectionsClient.stopDiscovery();
        clients.clear();
        clientNamesToIDs.clear();
        clientIDsToNames.clear();
    }
}
