package com.example.groupfence.notifications;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import androidx.core.content.ContextCompat;
import androidx.core.app.NotificationCompat;
import com.example.groupfence.R;
import com.example.groupfence.ui.MainActivity;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class NotificationHelper {
    public static final String CHANNEL_ID = "groupfence_alerts";
    public static final String CHANNEL_NAME = "Location alerts";
    private static final String TAG = "NotificationHelper";
    
    // Prevent duplicated notification sounds/alerts within a short time window
    private static final Set<String> RECENT_EVENTS = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private NotificationHelper() {}

    public static void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            
            NotificationChannel channel = nm.getNotificationChannel(CHANNEL_ID);
            if (channel == null || channel.getImportance() < NotificationManager.IMPORTANCE_HIGH) {
                channel = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("Alerts when members enter or leave group vicinities");
                channel.enableLights(true);
                channel.enableVibration(true);
                channel.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
                
                Uri defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                AudioAttributes audioAttributes = new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build();
                channel.setSound(defaultSoundUri, audioAttributes);
                nm.createNotificationChannel(channel);
            }
        }
    }

    public static void showNotification(Context context, String title, String body, String groupId) {
        showNotification(context, title, body, groupId, null);
    }

    public static void showNotification(Context context, String title, String body, String groupId, String eventKey) {
        if (context == null) return;
        
        // De-duplicate if the exact same event was notified
        if (eventKey != null && !eventKey.trim().isEmpty()) {
            if (RECENT_EVENTS.contains(eventKey)) {
                Log.d(TAG, "Skipping duplicate notification for in-memory event: " + eventKey);
                return;
            }
            SharedPreferences notifPrefs = context.getSharedPreferences("notified_events_cache", Context.MODE_PRIVATE);
            if (notifPrefs.getBoolean("notified_" + eventKey, false)) {
                Log.d(TAG, "Skipping duplicate notification for persisted event: " + eventKey);
                return;
            }
            RECENT_EVENTS.add(eventKey);
            notifPrefs.edit().putBoolean("notified_" + eventKey, true).apply();
            scheduler.schedule(() -> RECENT_EVENTS.remove(eventKey), 60, TimeUnit.SECONDS);
        }

        createNotificationChannel(context);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "POST_NOTIFICATIONS permission not granted, skipping display");
                return;
            }
        }

        if (title == null || title.isEmpty()) title = "GroupFence — Location Alert";
        if (body == null || body.isEmpty()) body = "A group location event occurred.";

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Intent intent = new Intent(context, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (groupId != null) {
            intent.putExtra("groupId", groupId);
        }

        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(context, (int) System.currentTimeMillis(), intent, pendingFlags);

        Uri defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setSound(defaultSoundUri)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setAutoCancel(true)
                .setContentIntent(pi);

        int notificationId = (eventKey != null && !eventKey.trim().isEmpty())
                ? Math.abs(eventKey.hashCode())
                : (int) (System.currentTimeMillis() & 0x7fffffff);
        nm.notify(notificationId, builder.build());
        Log.i(TAG, "Notification displayed [id=" + notificationId + "]: " + title + " - " + body);
    }
}
