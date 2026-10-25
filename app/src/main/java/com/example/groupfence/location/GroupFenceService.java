package com.example.groupfence.location;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.example.groupfence.R;
import com.example.groupfence.notifications.NotificationHelper;
import com.example.groupfence.ui.MainActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import java.util.ArrayList;
import java.util.List;

public class GroupFenceService extends Service {
    private static final String TAG = "GroupFenceService";
    private static final String SERVICE_CHANNEL_ID = "groupfence_service_channel";
    private static final int SERVICE_NOTIFICATION_ID = 9001;
    private final List<ListenerRegistration> activeListeners = new ArrayList<>();
    private FirebaseFirestore db;
    private FirebaseAuth auth;

    @Override
    public void onCreate() {
        super.onCreate();
        db = FirebaseFirestore.getInstance();
        auth = FirebaseAuth.getInstance();
        createServiceNotificationChannel();
    }

    private void createServiceNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel channel = nm.getNotificationChannel(SERVICE_CHANNEL_ID);
                if (channel == null) {
                    channel = new NotificationChannel(
                            SERVICE_CHANNEL_ID,
                            "GroupFence Service",
                            NotificationManager.IMPORTANCE_LOW
                    );
                    channel.setDescription("Keeps GroupFence active to alert all members in real-time.");
                    channel.setShowBadge(false);
                    nm.createNotificationChannel(channel);
                }
            }
        }
    }

    private Notification buildForegroundNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, flags);

        return new NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
                .setContentTitle("GroupFence Active")
                .setContentText("Monitoring group boundaries for all members")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(SERVICE_NOTIFICATION_ID, buildForegroundNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(SERVICE_NOTIFICATION_ID, buildForegroundNotification());
        }

        startListeningToAllGroupEvents();
        return START_STICKY;
    }

    private void clearListeners() {
        for (ListenerRegistration reg : activeListeners) {
            if (reg != null) reg.remove();
        }
        activeListeners.clear();
    }

    private void startListeningToAllGroupEvents() {
        clearListeners();
        if (auth.getCurrentUser() == null) return;
        String myUid = auth.getCurrentUser().getUid();

        // 1. Listen to personal notifications
        ListenerRegistration userNotifReg = db.collection("users").document(myUid)
                .collection("notifications")
                .addSnapshotListener((snapshots, error) -> {
                    if (error != null || snapshots == null) return;
                    for (DocumentChange dc : snapshots.getDocumentChanges()) {
                        if (dc.getType() == DocumentChange.Type.ADDED) {
                            handleIncomingNotificationDoc(dc.getDocument(), myUid);
                        }
                    }
                });
        activeListeners.add(userNotifReg);

        // 2. Listen to all groups the user is a member of
        ListenerRegistration membershipsReg = db.collection("users").document(myUid)
                .collection("memberships")
                .addSnapshotListener((snapshot, error) -> {
                    if (error != null || snapshot == null) return;
                    for (DocumentSnapshot mDoc : snapshot.getDocuments()) {
                        String groupId = mDoc.getId();
                        listenToGroupEvents(groupId, myUid);
                    }
                });
        activeListeners.add(membershipsReg);
    }

    private void listenToGroupEvents(String groupId, String myUid) {
        ListenerRegistration groupEventsReg = db.collection("groups").document(groupId)
                .collection("events")
                .addSnapshotListener((snapshots, error) -> {
                    if (error != null || snapshots == null) return;
                    for (DocumentChange dc : snapshots.getDocumentChanges()) {
                        if (dc.getType() == DocumentChange.Type.ADDED) {
                            handleIncomingEventDoc(dc.getDocument(), groupId, myUid);
                        }
                    }
                });
        activeListeners.add(groupEventsReg);
    }

    private void handleIncomingNotificationDoc(DocumentSnapshot d, String myUid) {
        String memberUid = d.getString("memberUid");
        if (memberUid != null && memberUid.equals(myUid)) return;

        String eventId = d.getString("eventId");
        if (eventId == null || eventId.isEmpty()) eventId = d.getId();

        SharedPreferences notifPrefs = getSharedPreferences("notified_events_cache", MODE_PRIVATE);
        if (notifPrefs.getBoolean("notified_" + eventId, false)) return;

        long eventTime = 0;
        if (d.getTimestamp("createdAt") != null) {
            eventTime = d.getTimestamp("createdAt").toDate().getTime();
        } else if (d.getDate("clientEventTime") != null) {
            eventTime = d.getDate("clientEventTime").getTime();
        }
        if (eventTime > 0 && (System.currentTimeMillis() - eventTime > 15 * 60 * 1000)) {
            notifPrefs.edit().putBoolean("notified_" + eventId, true).apply();
            return;
        }

        String title = d.getString("title");
        String body = d.getString("body");
        String groupId = d.getString("groupId");

        if (title == null || title.isEmpty()) title = "GroupFence — Location Alert";
        if (body == null || body.isEmpty()) body = "A group member entered or left the designated area.";

        notifPrefs.edit().putBoolean("notified_" + eventId, true).apply();
        NotificationHelper.showNotification(this, title, body, groupId, eventId);
        Log.i(TAG, "Notification shown by background service: " + title + " - " + body);
    }

    private void handleIncomingEventDoc(DocumentSnapshot d, String groupId, String myUid) {
        String memberUid = d.getString("memberUid");
        if (memberUid != null && memberUid.equals(myUid)) return;

        String type = d.getString("eventType");
        if ("DELETED".equals(type)) return;

        String eventId = d.getString("eventId");
        if (eventId == null || eventId.isEmpty()) eventId = d.getId();

        SharedPreferences notifPrefs = getSharedPreferences("notified_events_cache", MODE_PRIVATE);
        if (notifPrefs.getBoolean("notified_" + eventId, false)) return;

        long eventTime = 0;
        if (d.getTimestamp("createdAt") != null) {
            eventTime = d.getTimestamp("createdAt").toDate().getTime();
        } else if (d.getDate("clientEventTime") != null) {
            eventTime = d.getDate("clientEventTime").getTime();
        }
        if (eventTime > 0 && (System.currentTimeMillis() - eventTime > 15 * 60 * 1000)) {
            notifPrefs.edit().putBoolean("notified_" + eventId, true).apply();
            return;
        }

        String groupName = d.getString("groupName");
        if (groupName == null || groupName.isEmpty()) groupName = "Group";
        String memberName = d.getString("memberName");
        if (memberName == null || memberName.isEmpty()) memberName = "A member";

        String title = groupName + " — Location Alert";
        String body = "EXIT".equals(type)
                ? memberName + " has left " + groupName + "'s designated area."
                : memberName + " has entered " + groupName + "'s designated area.";

        notifPrefs.edit().putBoolean("notified_" + eventId, true).apply();
        NotificationHelper.showNotification(this, title, body, groupId, eventId);
        Log.i(TAG, "Group event alert shown by background service: " + title + " - " + body);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        clearListeners();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
