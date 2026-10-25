
package com.example.groupfence.notifications;

import android.content.Context;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.messaging.FirebaseMessaging;
import java.util.*;

public final class TokenManager {
    private TokenManager() {}
    public static void refreshToken(Context context) {
        if(FirebaseAuth.getInstance().getCurrentUser()==null) return;
        FirebaseMessaging.getInstance().getToken().addOnSuccessListener(token -> save(token));
    }
    public static void save(String token) {
        if(FirebaseAuth.getInstance().getCurrentUser()==null || token==null || token.isEmpty()) return;
        String uid=FirebaseAuth.getInstance().getCurrentUser().getUid();
        Map<String,Object> data=new HashMap<>(); data.put("token",token); data.put("updatedAt",FieldValue.serverTimestamp());
        FirebaseFirestore.getInstance().collection("users").document(uid).collection("devices").document(tokenDocumentId(token)).set(data);
    }
    public static String tokenDocumentId(String token) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i=0; i<12; i++) b.append(String.format(java.util.Locale.US, "%02x", digest[i]));
            return b.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
