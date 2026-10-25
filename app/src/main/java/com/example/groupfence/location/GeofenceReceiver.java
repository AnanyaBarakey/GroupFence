package com.example.groupfence.location;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingEvent;
import com.google.firebase.auth.FirebaseAuth;
import java.util.List;

public class GeofenceReceiver extends BroadcastReceiver {
    private static final String TAG = "GeofenceReceiver";
    private static final String PREFS = "registered_geofences";

    @Override
    public void onReceive(Context context, Intent intent) {
        GeofencingEvent event = GeofencingEvent.fromIntent(intent);
        if (event == null) {
            Log.e(TAG, "GeofencingEvent is null");
            return;
        }
        if (event.hasError()) {
            Log.e(TAG, "Geofence error: " + event.getErrorCode());
            return;
        }

        int transition = event.getGeofenceTransition();
        final String type;
        if (transition == Geofence.GEOFENCE_TRANSITION_ENTER) {
            type = "ENTER";
        } else if (transition == Geofence.GEOFENCE_TRANSITION_EXIT) {
            type = "EXIT";
        } else {
            return;
        }

        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            Log.w(TAG, "No authenticated user for geofence transition");
            return;
        }

        List<Geofence> triggeringGeofences = event.getTriggeringGeofences();
        if (triggeringGeofences == null || triggeringGeofences.isEmpty()) {
            return;
        }

        final PendingResult pendingResult = goAsync();
        final Context appContext = context.getApplicationContext();

        new Thread(() -> {
            try {
                SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                for (Geofence f : triggeringGeofences) {
                    String requestId = f.getRequestId();
                    if (requestId == null) continue;
                    String[] parts = requestId.split("\\|");
                    if (parts.length != 2) continue;

                    String groupId = parts[0];
                    int version = 1;
                    try {
                        version = Integer.parseInt(parts[1]);
                    } catch (Exception ignored) {}

                    // Prevent synthetic initial ENTER when user was already inside during registration
                    long regTime = prefs.getLong("reg_time_" + groupId, 0);
                    long now = System.currentTimeMillis();
                    if ("ENTER".equals(type) && (now - regTime < 5000)) {
                        Log.i(TAG, "Suppressed synthetic initial ENTER notification for group: " + groupId);
                        continue;
                    }

                    String groupName = prefs.getString("group_name_" + groupId, null);
                    VicinityNotifier.handleTransition(appContext, groupId, groupName, version, type);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing geofence transition", e);
            } finally {
                pendingResult.finish();
            }
        }).start();
    }
}
