package com.example.groupfence.notifications;

import android.util.Log;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.util.HashMap;
import java.util.Map;

public class GroupFenceMessagingService extends FirebaseMessagingService {
    private static final String TAG = "GroupFenceFCM";

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        TokenManager.save(token);
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        super.onMessageReceived(message);
        Log.d(TAG, "FCM message received: " + message.getMessageId());

        String title = null;
        String body = null;
        String groupId = null;
        String groupName = null;
        String memberName = null;
        String memberUid = null;
        String eventId = null;
        String eventType = null;

        if (message.getData() != null && !message.getData().isEmpty()) {
            title = message.getData().get("title");
            body = message.getData().get("body");
            groupId = message.getData().get("groupId");
            groupName = message.getData().get("groupName");
            memberName = message.getData().get("memberName");
            memberUid = message.getData().get("memberUid");
            eventId = message.getData().get("eventId");
            eventType = message.getData().get("eventType");
        }

        if (title == null && message.getNotification() != null) {
            title = message.getNotification().getTitle();
        }
        if (body == null && message.getNotification() != null) {
            body = message.getNotification().getBody();
        }

        if (title == null || title.isEmpty()) {
            title = (groupName != null ? groupName : "Group") + " — Location Alert";
        }
        if (body == null || body.isEmpty()) {
            String who = memberName != null ? memberName : "A member";
            body = "EXIT".equals(eventType)
                    ? who + " has left " + (groupName != null ? groupName : "the group") + "'s designated area."
                    : who + " has entered " + (groupName != null ? groupName : "the group") + "'s designated area.";
        }

        NotificationHelper.showNotification(this, title, body, groupId, eventId);

        // Record into local user's notifications collection
        if (FirebaseAuth.getInstance().getCurrentUser() != null && eventId != null) {
            String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
            Map<String, Object> myNotif = new HashMap<>();
            myNotif.put("groupId", groupId);
            myNotif.put("groupName", groupName);
            myNotif.put("eventId", eventId);
            myNotif.put("eventType", eventType);
            myNotif.put("memberUid", memberUid);
            myNotif.put("memberName", memberName);
            myNotif.put("title", title);
            myNotif.put("body", body);
            myNotif.put("createdAt", FieldValue.serverTimestamp());
            myNotif.put("clientEventTime", new java.util.Date());
            myNotif.put("read", false);
            FirebaseFirestore.getInstance().collection("users").document(uid).collection("notifications").document(eventId).set(myNotif);
        }
    }
}
