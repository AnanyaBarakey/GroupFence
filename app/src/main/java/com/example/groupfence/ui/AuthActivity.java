package com.example.groupfence.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.example.groupfence.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.UserProfileChangeRequest;
import com.google.firebase.firestore.FirebaseFirestore;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class AuthActivity extends AppCompatActivity {
    private FirebaseAuth auth;
    private EditText name, email, password;
    private ProgressBar progress;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        auth = FirebaseAuth.getInstance();
        if (auth.getCurrentUser() != null) {
            String cached = getSharedPreferences("user_profile", MODE_PRIVATE).getString("display_name", null);
            if (cached == null || cached.isEmpty()) {
                String emailStr = auth.getCurrentUser().getEmail();
                if (emailStr != null && !emailStr.isEmpty()) {
                    int at = emailStr.indexOf('@');
                    String n = at > 0 ? emailStr.substring(0, at) : emailStr;
                    n = n.substring(0, 1).toUpperCase(Locale.US) + (n.length() > 1 ? n.substring(1) : "");
                    getSharedPreferences("user_profile", MODE_PRIVATE).edit().putString("display_name", n).apply();
                }
            }
            openHome();
            return;
        }
        setContentView(R.layout.activity_auth);
        name = findViewById(R.id.nameInput); email = findViewById(R.id.emailInput);
        password = findViewById(R.id.passwordInput); progress = findViewById(R.id.progress);
        findViewById(R.id.loginButton).setOnClickListener(v -> login());
        findViewById(R.id.registerButton).setOnClickListener(v -> register());
        findViewById(R.id.resetButton).setOnClickListener(v -> resetPassword());
    }

    private boolean valid() {
        String e = email.getText().toString().trim(), p = password.getText().toString();
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(e).matches() || p.length() < 6) {
            Toast.makeText(this, "Enter a valid email and password of at least 6 characters.", Toast.LENGTH_LONG).show(); return false;
        } return true;
    }

    private void busy(boolean b) { progress.setVisibility(b ? View.VISIBLE : View.GONE); }

    private void login() {
        if (!valid()) return;
        busy(true);
        String emailStr = email.getText().toString().trim();
        auth.signInWithEmailAndPassword(emailStr, password.getText().toString())
            .addOnCompleteListener(t -> {
                busy(false);
                if (t.isSuccessful()) {
                    String uid = auth.getCurrentUser() != null ? auth.getCurrentUser().getUid() : null;
                    if (uid != null) {
                        int atIdx = emailStr.indexOf('@');
                        String fallbackName = atIdx > 0 ? emailStr.substring(0, atIdx) : emailStr;
                        if (!fallbackName.isEmpty()) {
                            fallbackName = fallbackName.substring(0, 1).toUpperCase(Locale.US) + (fallbackName.length() > 1 ? fallbackName.substring(1) : "");
                        }
                        final String defaultName = fallbackName;
                        getSharedPreferences("user_profile", MODE_PRIVATE).edit().putString("display_name", defaultName).apply();

                        FirebaseFirestore.getInstance().collection("users").document(uid).get().addOnSuccessListener(d -> {
                            if (d != null && d.getString("displayName") != null && !d.getString("displayName").trim().isEmpty()) {
                                String dName = d.getString("displayName").trim();
                                getSharedPreferences("user_profile", MODE_PRIVATE).edit().putString("display_name", dName).apply();
                                if (auth.getCurrentUser() != null) {
                                    auth.getCurrentUser().updateProfile(new UserProfileChangeRequest.Builder().setDisplayName(dName).build());
                                }
                            }
                        });
                    }
                    openHome();
                } else toast(error(t.getException()));
            });
    }

    private void register() {
        if (!valid()) return;
        String display = name.getText().toString().trim();
        if (display.isEmpty()) { toast("Enter a display name."); return; }
        busy(true);
        auth.createUserWithEmailAndPassword(email.getText().toString().trim(), password.getText().toString())
            .addOnCompleteListener(t -> {
                if (!t.isSuccessful()) { busy(false); toast(error(t.getException())); return; }
                String uid = auth.getCurrentUser().getUid();
                UserProfileChangeRequest profileUpdates = new UserProfileChangeRequest.Builder()
                        .setDisplayName(display).build();
                auth.getCurrentUser().updateProfile(profileUpdates);
                getSharedPreferences("user_profile", MODE_PRIVATE).edit().putString("display_name", display).apply();

                Map<String,Object> user = new HashMap<>(); user.put("uid", uid); user.put("displayName", display);
                user.put("email", email.getText().toString().trim()); user.put("createdAt", com.google.firebase.firestore.FieldValue.serverTimestamp());
                FirebaseFirestore.getInstance().collection("users").document(uid).set(user)
                    .addOnCompleteListener(saved -> { busy(false); if (saved.isSuccessful()) openHome(); else toast(error(saved.getException())); });
            });
    }

    private void resetPassword() {
        String e = email.getText().toString().trim();
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(e).matches()) { toast("Enter your email first."); return; }
        auth.sendPasswordResetEmail(e).addOnSuccessListener(v -> toast("Password reset email sent."))
            .addOnFailureListener(e1 -> toast(error(e1)));
    }

    private String error(Exception e) { return e == null ? "Something went wrong." : e.getLocalizedMessage(); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private void openHome() { startActivity(new Intent(this, MainActivity.class)); finish(); }
}
