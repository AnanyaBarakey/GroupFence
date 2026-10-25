package com.example.groupfence.location;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.util.Log;
import com.example.groupfence.notifications.NotificationHelper;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.FirebaseFunctionsException;
import com.google.firebase.functions.HttpsCallableReference;
import com.google.firebase.functions.HttpsCallableResult;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class VicinityNotifier {
    private static final String TAG = "VicinityNotifier";
    private static final String PREFS = "vicinity_state_prefs";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final String FUNCTIONS_REGION = "us-central1";

    private VicinityNotifier() {}

    public static void handleTransition(Context context, String groupId, int version, String type) {
        handleTransition(context, groupId, null, version, type);
    }

    public static void handleTransition(Context context, String groupId, String groupNameHint, int version, String type) {
        if (context == null || groupId == null || groupId.trim().isEmpty() || type == null) {
            Log.w(TAG, "Invalid arguments passed to handleTransition");
            return;
        }

        // Ensure non-blocking execution off the main thread
        if (Looper.myLooper() == Looper.getMainLooper()) {
            EXECUTOR.execute(() -> processTransitionInternal(context.getApplicationContext(), groupId, groupNameHint, version, type));
        } else {
            processTransitionInternal(context.getApplicationContext(), groupId, groupNameHint, version, type);
        }
    }

    private static void processTransitionInternal(Context context, String groupId, String groupNameHint, int version, String type) {
        FirebaseAuth auth = FirebaseAuth.getInstance();
        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser == null) {
            Log.w(TAG, "No authenticated user for transition");
            return;
        }

        if (!"ENTER".equals(type) && !"EXIT".equals(type)) {
            Log.w(TAG, "Unsupported transition type: " + type);
            return;
        }

        final String uid = currentUser.getUid();
        final String email = currentUser.getEmail();

        // 1. Resolve display name reliably
        SharedPreferences userPrefs = context.getSharedPreferences("user_profile", Context.MODE_PRIVATE);
        String displayName = currentUser.getDisplayName();
        if ((displayName == null || displayName.trim().isEmpty()) && userPrefs.contains("display_name")) {
            displayName = userPrefs.getString("display_name", null);
        }
        if (displayName == null || displayName.trim().isEmpty()) {
            if (email != null && !email.isEmpty()) {
                int atIdx = email.indexOf('@');
                String prefix = atIdx > 0 ? email.substring(0, atIdx) : email;
                displayName = prefix.substring(0, 1).toUpperCase(Locale.US) + (prefix.length() > 1 ? prefix.substring(1) : "");
            } else {
                displayName = "Member";
            }
            userPrefs.edit().putString("display_name", displayName).apply();
        }
        final String senderName = displayName;

        // 2. Debounce rapid identical transitions per group and event type
        String debounceKey = groupId + "_" + type;
        long lastTrigger = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("last_trigger_" + debounceKey, 0);
        long now = System.currentTimeMillis();
        if (now - lastTrigger < 10000) {
            Log.d(TAG, "Debouncing rapid transition for " + debounceKey);
            return;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("last_trigger_" + debounceKey, now).apply();

        // 3. Resolve group name (use hint, cached pref, or fallback)
        String groupName = groupNameHint;
        if (groupName == null || groupName.trim().isEmpty()) {
            groupName = context.getSharedPreferences("registered_geofences", Context.MODE_PRIVATE)
                    .getString("group_name_" + groupId, null);
        }
        if (groupName == null || groupName.trim().isEmpty()) {
            groupName = "Group";
        }

        final String resolvedGroupName = groupName;
        final String eventId = UUID.randomUUID().toString();
        final String title = resolvedGroupName + " — Location Alert";

        // Construct clear, user-specific alert message for the moving user
        final String myBody = "ENTER".equals(type)
                ? senderName + " (You) has entered " + resolvedGroupName + "'s designated area."
                : senderName + " (You) has left " + resolvedGroupName + "'s designated area.";

        // Display local notification on the triggering device immediately (preventing duplicate alerts)
        NotificationHelper.showNotification(context, title, myBody, groupId, eventId);

        // 4. Save to triggering user's local notification history
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        Map<String, Object> myNotif = new HashMap<>();
        myNotif.put("groupId", groupId);
        myNotif.put("groupName", resolvedGroupName);
        myNotif.put("eventId", eventId);
        myNotif.put("eventType", type);
        myNotif.put("memberUid", uid);
        myNotif.put("memberName", senderName);
        myNotif.put("title", title);
        myNotif.put("body", myBody);
        myNotif.put("createdAt", FieldValue.serverTimestamp());
        myNotif.put("clientEventTime", new Date(now));
        myNotif.put("read", false);
        try {
            Tasks.await(db.collection("users").document(uid).collection("notifications").document(eventId).set(myNotif), 10, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.w(TAG, "Failed saving user notification: " + e.getMessage());
        }

        // 5. Invoke Firebase Cloud Function with proper asynchronous handling, retry and error logging
        int configurationVersion = version > 0 ? version : 1;
        Map<String, Object> payload = new HashMap<>();
        payload.put("groupId", groupId);
        payload.put("groupName", resolvedGroupName);
        payload.put("memberName", senderName);
        payload.put("configurationVersion", configurationVersion);
        payload.put("eventType", type);
        payload.put("eventId", eventId);
        payload.put("clientEventTime", now);

        callProcessGeofenceEventWithRetry(payload, eventId);
    }

    private static void callProcessGeofenceEventWithRetry(Map<String, Object> payload, String eventId) {
        FirebaseFunctions functions = FirebaseFunctions.getInstance(FUNCTIONS_REGION);
        HttpsCallableReference callable = functions.getHttpsCallable("processGeofenceEvent");

        final int maxRetries = 2;
        boolean success = false;

        for (int attempt = 0; attempt <= maxRetries && !success; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(attempt * 1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    Log.w(TAG, "Retry sleep interrupted for event: " + eventId);
                    break;
                }
                Log.i(TAG, "Retrying processGeofenceEvent (attempt " + attempt + ") with same eventId: " + eventId);
            }

            try {
                HttpsCallableResult result = Tasks.await(callable.call(payload), 15, TimeUnit.SECONDS);
                Object data = result.getData();
                Log.i(TAG, "processGeofenceEvent succeeded for eventId=" + eventId + ": " + data);
                success = true;
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                if (cause instanceof FirebaseFunctionsException) {
                    FirebaseFunctionsException ffe = (FirebaseFunctionsException) cause;
                    FirebaseFunctionsException.Code code = ffe.getCode();
                    Object details = ffe.getDetails();
                    Log.e(TAG, "Firebase Functions error for event " + eventId + " [" + code + "]: "
                            + ffe.getMessage() + (details != null ? " Details: " + details : ""));

                    // Permanent client or permission errors: do not retry
                    if (code == FirebaseFunctionsException.Code.INVALID_ARGUMENT ||
                        code == FirebaseFunctionsException.Code.NOT_FOUND ||
                        code == FirebaseFunctionsException.Code.PERMISSION_DENIED ||
                        code == FirebaseFunctionsException.Code.UNAUTHENTICATED ||
                        code == FirebaseFunctionsException.Code.ALREADY_EXISTS ||
                        code == FirebaseFunctionsException.Code.FAILED_PRECONDITION) {
                        break;
                    }
                } else {
                    Log.e(TAG, "Network or server failure calling processGeofenceEvent for event " + eventId
                            + " (attempt " + attempt + "): " + cause.getMessage(), cause);
                }
            } catch (TimeoutException te) {
                Log.w(TAG, "Timed out waiting for processGeofenceEvent for event " + eventId + " on attempt " + attempt);
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error calling processGeofenceEvent for event " + eventId + ": " + e.getMessage(), e);
            }
        }

        if (!success) {
            Log.e(TAG, "Failed delivering geofence event " + eventId + " to Cloud Function after all attempts.");
        }
    }
}
