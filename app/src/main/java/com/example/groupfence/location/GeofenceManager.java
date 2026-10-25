package com.example.groupfence.location;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;
import androidx.core.content.ContextCompat;
import com.google.android.gms.location.*;
import com.google.firebase.auth.FirebaseAuth;
import java.util.*;

public final class GeofenceManager {
    private static final String PREFS = "registered_geofences";
    private GeofenceManager() {}

    private static PendingIntent pendingIntent(Context c) {
        Intent i = new Intent(c, GeofenceReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_MUTABLE : 0);
        return PendingIntent.getBroadcast(c, 7301, i, flags);
    }

    public static void register(Context c, String groupId, String groupName, double lat, double lon, int radius, int version) {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(c, "Allow precise location to enable geofence monitoring.", Toast.LENGTH_LONG).show();
            return;
        }
        Geofence fence = new Geofence.Builder().setRequestId(groupId + "|" + version)
            .setCircularRegion(lat, lon, radius)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setNotificationResponsiveness(5000)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER | Geofence.GEOFENCE_TRANSITION_EXIT).build();

        // setInitialTrigger(0) prevents false ENTER alerts when already inside during registration
        GeofencingRequest request = new GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofence(fence).build();

        LocationServices.getGeofencingClient(c).addGeofences(request, pendingIntent(c))
            .addOnSuccessListener(v -> c.getSharedPreferences(PREFS, 0).edit()
                    .putString(groupId, groupId + "|" + version)
                    .putString("group_name_" + groupId, groupName != null ? groupName : "Group")
                    .putLong("reg_time_" + groupId, System.currentTimeMillis())
                    .apply())
            .addOnFailureListener(e -> Toast.makeText(c, "Geofence registration failed: " + e.getLocalizedMessage(), Toast.LENGTH_LONG).show());
    }

    public static void register(Context c, String groupId, double lat, double lon, int radius, int version) {
        register(c, groupId, null, lat, lon, radius, version);
    }

    public static void remove(Context c, String groupId) {
        String id = c.getSharedPreferences(PREFS, 0).getString(groupId, null);
        if (id != null) {
            LocationServices.getGeofencingClient(c).removeGeofences(Collections.singletonList(id))
                .addOnCompleteListener(t -> c.getSharedPreferences(PREFS, 0).edit().remove(groupId).remove("group_name_" + groupId).apply());
        }
    }

    public static void removeAll(Context c) {
        LocationServices.getGeofencingClient(c).removeGeofences(pendingIntent(c));
        c.getSharedPreferences(PREFS, 0).edit().clear().apply();
    }
}
