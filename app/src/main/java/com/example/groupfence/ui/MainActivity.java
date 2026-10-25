package com.example.groupfence.ui;

import android.Manifest;
import android.util.Log;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.location.Location;
import com.google.android.gms.tasks.CancellationTokenSource;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.maplibre.android.MapLibre;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.annotations.MarkerOptions;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import com.example.groupfence.R;
import com.example.groupfence.location.GeofenceManager;
import com.example.groupfence.location.GroupFenceService;
import com.example.groupfence.location.VicinityNotifier;
import com.example.groupfence.notifications.NotificationHelper;
import com.example.groupfence.notifications.TokenManager;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.UserProfileChangeRequest;
import com.google.firebase.firestore.*;
import android.os.Looper;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private FirebaseFirestore db;
    private FirebaseAuth auth;
    private LinearLayout groups;
    private TextView status;
    private final int LOCATION_PERMISSION_REQUEST = 410;
    private final int PERMISSION_REQUEST_ALL = 501;
    private final int BACKGROUND_LOCATION_REQUEST = 502;
    private MapView pickerMapView;
    private FusedLocationProviderClient fusedLocationClient;
    private EditText pendingLatitude;
    private EditText pendingLongitude;
    private final List<ListenerRegistration> eventListeners = new ArrayList<>();
    private LocationCallback activeLocationCallback;

    private static class MonitoredGroup {
        String id;
        String name;
        double lat;
        double lon;
        int radius;
        int version;
    }
    private final Map<String, MonitoredGroup> activeMonitoredGroups = new HashMap<>();

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s); setContentView(R.layout.activity_main);
        db = FirebaseFirestore.getInstance(); auth = FirebaseAuth.getInstance();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        groups = findViewById(R.id.groupsContainer); status = findViewById(R.id.statusText);
        NotificationHelper.createNotificationChannel(this);
        String uid = auth.getCurrentUser() != null ? auth.getCurrentUser().getUid() : null;
        if (uid != null) {
            db.collection("users").document(uid).get().addOnSuccessListener(d -> {
                if (d != null && d.exists()) {
                    String dName = d.getString("displayName");
                    if (dName != null && !dName.trim().isEmpty()) {
                        getSharedPreferences("user_profile", MODE_PRIVATE).edit().putString("display_name", dName.trim()).apply();
                        if (auth.getCurrentUser() != null) {
                            auth.getCurrentUser().updateProfile(new UserProfileChangeRequest.Builder().setDisplayName(dName.trim()).build());
                        }
                        findViewById(R.id.welcomeText).setContentDescription("Welcome " + dName.trim());
                    }
                }
            });
        }
        TokenManager.refreshToken(this);
        updateStatus();
        findViewById(R.id.createButton).setOnClickListener(v -> createGroupDialog());
        findViewById(R.id.joinButton).setOnClickListener(v -> joinDialog());
        findViewById(R.id.refreshButton).setOnClickListener(v -> loadGroups());
        findViewById(R.id.logoutButton).setOnClickListener(v -> {
            stopGroupFenceService();
            stopActiveLocationTracking();
            clearGroupListeners();
            GeofenceManager.removeAll(this); auth.signOut();
            startActivity(new Intent(this, AuthActivity.class)); finish();
        });
        loadGroups();
        checkAndRequestAllPermissions();
        startActiveLocationTracking();
        startGroupFenceService();
    }
    @Override protected void onResume() {
        super.onResume();
        updateStatus();
        startActiveLocationTracking();
        startGroupFenceService();
    }

    public void checkAndRequestAllPermissions() {
        List<String> needed = new ArrayList<>();
        boolean fine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!fine || !coarse) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERMISSION_REQUEST_ALL);
        } else {
            onForegroundPermissionsGranted();
        }
    }

    private void startGroupFenceService() {
        try {
            Intent serviceIntent = new Intent(this, GroupFenceService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.w("MainActivity", "Failed to start GroupFenceService: " + e.getMessage());
        }
    }

    private void stopGroupFenceService() {
        try {
            stopService(new Intent(this, GroupFenceService.class));
        } catch (Exception ignored) {}
    }

    private void onForegroundPermissionsGranted() {
        startActiveLocationTracking();
        startGroupFenceService();
        updateStatus();
        TokenManager.refreshToken(this);
        for (MonitoredGroup mg : activeMonitoredGroups.values()) {
            GeofenceManager.register(this, mg.id, mg.name, mg.lat, mg.lon, mg.radius, mg.version);
        }
        checkBackgroundLocationPermission();
    }

    private void checkBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                new AlertDialog.Builder(this)
                        .setTitle("Background Location Required")
                        .setMessage("To ensure you and all group members receive alerts when entering or leaving group areas while your phone is locked or the app is closed, please enable 'Allow all the time' for location.")
                        .setPositiveButton("Allow All The Time", (d, w) -> {
                            ActivityCompat.requestPermissions(this,
                                    new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                                    BACKGROUND_LOCATION_REQUEST);
                        })
                        .setNeutralButton("Open App Settings", (d, w) -> {
                            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:" + getPackageName())));
                        })
                        .setNegativeButton("Later", (d, w) -> updateStatus())
                        .show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_ALL || requestCode == LOCATION_PERMISSION_REQUEST) {
            boolean fineGranted = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            boolean notifGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;

            if (fineGranted) {
                onForegroundPermissionsGranted();
                EditText latitude = pendingLatitude;
                EditText longitude = pendingLongitude;
                pendingLatitude = null;
                pendingLongitude = null;
                if (latitude != null && longitude != null) {
                    fillCurrentLocation(latitude, longitude);
                }
                loadGroups();
            } else {
                toast("Precise location is required for all members to monitor geofences and receive alerts.");
            }

            if (!notifGranted) {
                toast("Notification permission is required to receive vicinity alerts.");
            }
            updateStatus();
        } else if (requestCode == BACKGROUND_LOCATION_REQUEST) {
            updateStatus();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                boolean bgGranted = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
                if (bgGranted) {
                    toast("Background location enabled. Continuous boundary monitoring active.");
                    loadGroups();
                } else {
                    toast("Background location not granted. Alerts may only trigger when the app is open.");
                }
            }
        } else if (requestCode == 411) {
            updateStatus();
            TokenManager.refreshToken(this);
        }
    }

    private void updateStatus() {
        boolean fine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean bg = Build.VERSION.SDK_INT < 29 || ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean notif = Build.VERSION.SDK_INT < 33 || ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;

        if (!fine) {
            status.setText("Monitoring paused: Allow precise location & notifications to enable geofencing.");
            status.setOnClickListener(v -> checkAndRequestAllPermissions());
        } else if (!notif) {
            status.setText("Location granted, but notification permission is disabled. Tap to enable.");
            status.setOnClickListener(v -> checkAndRequestAllPermissions());
        } else if (!bg && Build.VERSION.SDK_INT >= 29) {
            status.setText("Foreground active. Tap to allow 'All the time' location in Settings for reliable background alerts.");
            status.setOnClickListener(v -> checkBackgroundLocationPermission());
        } else {
            status.setText("Active: Location & notifications enabled for all members.");
            status.setOnClickListener(null);
        }
    }
    private void createGroupDialog() {
        LinearLayout box = formBox(); EditText name = edit("Group name", box), desc = edit("Description (optional)", box),
                lat = edit("Latitude (e.g. 37.421998)", box), lon = edit("Longitude (e.g. -122.084)", box), radius = edit("Radius in metres (100–5000)", box);
        lat.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        lon.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        radius.setInputType(InputType.TYPE_CLASS_NUMBER);
        Button currentLocation = new Button(this); currentLocation.setText("Use my current location"); box.addView(currentLocation);
        currentLocation.setOnClickListener(v -> fillCurrentLocation(lat, lon));
        Button pickLocation = new Button(this); pickLocation.setText("Choose location on map"); box.addView(pickLocation);
        pickLocation.setOnClickListener(v -> showLocationPicker(lat, lon));
        new AlertDialog.Builder(this).setTitle("Create group and geofence").setView(box)
                .setPositiveButton("Create", (d,w) -> {
                    try {
                        String n=name.getText().toString().trim(), de=desc.getText().toString().trim();
                        double la = Double.parseDouble(lat.getText().toString()), lo = Double.parseDouble(lon.getText().toString());
                        int r=Integer.parseInt(radius.getText().toString());
                        if(n.length()<2 || n.length()>60 || la < -90 || la > 90 || lo < -180 || lo > 180 || r<100 || r>5000) throw new IllegalArgumentException();
                        createGroupDirect(n, de, la, lo, r);
                    } catch(Exception e) { toast("Check coordinates and radius (100–5000 metres)."); }
                }).setNegativeButton("Cancel", null).show();
    }

    private void fillCurrentLocation(EditText latitude, EditText longitude) {
        boolean fine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse) {
            pendingLatitude = latitude;
            pendingLongitude = longitude;
            checkAndRequestAllPermissions();
            return;
        }
        Toast.makeText(this, "Getting your current location…", Toast.LENGTH_SHORT).show();
        CancellationTokenSource token = new CancellationTokenSource();
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.getToken())
                .addOnSuccessListener(location -> {
                    if (location == null) {
                        fusedLocationClient.getLastLocation().addOnSuccessListener(last -> {
                            if (last == null) toast("Couldn't get location. Turn on device location and try again.");
                            else setCoordinates(latitude, longitude, last);
                        }).addOnFailureListener(e -> toast("Couldn't get location: " + e.getMessage()));
                    } else setCoordinates(latitude, longitude, location);
                }).addOnFailureListener(e -> toast("Couldn't get location: " + e.getMessage()));
    }

    private void setCoordinates(EditText latitude, EditText longitude, Location location) {
        latitude.setText(String.valueOf(location.getLatitude()));
        longitude.setText(String.valueOf(location.getLongitude()));
        Toast.makeText(this, "Current location filled in", Toast.LENGTH_SHORT).show();
    }

    private void showLocationPicker(EditText latitude, EditText longitude) {
        MapLibre.getInstance(this);
        LinearLayout container = new LinearLayout(this); container.setOrientation(LinearLayout.VERTICAL);
        TextView hint = new TextView(this); hint.setText("Tap the map to place the group centre. You can fine-tune coordinates in the form.");
        hint.setPadding(20, 12, 20, 12); container.addView(hint);
        pickerMapView = new MapView(this);
        container.addView(pickerMapView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        final AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Select group location")
                .setView(container).setPositiveButton("Use this location", null)
                .setNegativeButton("Cancel", (d,w) -> { if (pickerMapView != null) pickerMapView.onDestroy(); }).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (latitude.getText().length() == 0 || longitude.getText().length() == 0) {
                toast("Tap the map to choose a location first."); return;
            }
            dialog.dismiss(); if (pickerMapView != null) pickerMapView.onDestroy();
        }));
        dialog.setOnDismissListener(d -> { if (pickerMapView != null) { pickerMapView.onDestroy(); pickerMapView = null; } });
        dialog.show();
        pickerMapView.onCreate(null); pickerMapView.onStart();
        pickerMapView.getMapAsync(map -> {
            map.setStyle(new Style.Builder().fromUri("https://tiles.openfreemap.org/styles/liberty"), style -> {
                double initialLat = 20.5937, initialLon = 78.9629;
                try { if (latitude.getText().length() > 0) initialLat = Double.parseDouble(latitude.getText().toString()); } catch (Exception ignored) {}
                try { if (longitude.getText().length() > 0) initialLon = Double.parseDouble(longitude.getText().toString()); } catch (Exception ignored) {}
                LatLng initial = new LatLng(initialLat, initialLon);
                map.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(initial, 5));
                map.addMarker(new MarkerOptions().position(initial).title("Group centre"));
                map.addOnMapClickListener(point -> {
                    latitude.setText(String.valueOf(point.getLatitude())); longitude.setText(String.valueOf(point.getLongitude()));
                    map.clear(); map.addMarker(new MarkerOptions().position(point).title("Group centre"));
                    return true;
                });
            });
        });
    }

    private void createGroupDirect(String name, String description, double latitude, double longitude, int radiusMeters) {
        if (auth.getCurrentUser() == null) return;
        String uid = auth.getCurrentUser().getUid();
        String groupId = db.collection("groups").document().getId();
        String code = newInvitationCode();
        String codeHash = sha256(code);
        DocumentReference groupRef = db.collection("groups").document(groupId);
        DocumentReference memberRef = groupRef.collection("members").document(uid);
        DocumentReference membershipRef = db.collection("users").document(uid).collection("memberships").document(groupId);
        DocumentReference codeRef = db.collection("invitationCodes").document(codeHash);

        Map<String,Object> group = new HashMap<>();
        group.put("name", name); group.put("description", description); group.put("ownerUid", uid);
        group.put("invitationCode", code); group.put("invitationCodeHash", codeHash);
        group.put("latitude", latitude); group.put("longitude", longitude); group.put("radiusMeters", radiusMeters);
        group.put("monitoringEnabled", true); group.put("configurationVersion", 1);
        group.put("createdAt", FieldValue.serverTimestamp()); group.put("updatedAt", FieldValue.serverTimestamp());

        Map<String,Object> member = new HashMap<>();
        member.put("uid", uid); member.put("role", "owner"); member.put("joinedAt", FieldValue.serverTimestamp());
        member.put("monitoringEnabled", true); member.put("notificationPreferences", preferences());

        Map<String,Object> membership = new HashMap<>();
        membership.put("groupId", groupId);
        membership.put("joinedAt", FieldValue.serverTimestamp());

        Map<String,Object> codeData = new HashMap<>();
        codeData.put("groupId", groupId);
        codeData.put("createdAt", FieldValue.serverTimestamp());

        WriteBatch batch = db.batch();
        batch.set(groupRef, group);
        batch.set(memberRef, member);
        batch.set(membershipRef, membership);
        batch.set(codeRef, codeData);
        batch.commit().addOnSuccessListener(v -> {
            getSharedPreferences("deleted_groups", MODE_PRIVATE).edit().remove("deleted_" + groupId).apply();
            toast("Group created. Invitation code: " + code);
            loadGroups();
        }).addOnFailureListener(e -> toast("Could not create group: " + e.getLocalizedMessage()));
    }

    private void joinGroupDirect(String code) {
        if (!code.matches("^[A-F0-9]{10}$")) { toast("Enter a valid 10-character invitation code."); return; }
        if (auth.getCurrentUser() == null) return;
        String uid = auth.getCurrentUser().getUid();
        String codeHash = sha256(code);
        DocumentReference codeRef = db.collection("invitationCodes").document(codeHash);
        codeRef.get().addOnSuccessListener(codeDoc -> {
            if (!codeDoc.exists()) { toast("Invitation code not found."); return; }
            String groupId = codeDoc.getString("groupId");
            if (groupId == null || groupId.isEmpty()) { toast("Invitation code is invalid."); return; }
            DocumentReference groupRef = db.collection("groups").document(groupId);
            DocumentReference memberRef = groupRef.collection("members").document(uid);
            DocumentReference membershipRef = db.collection("users").document(uid).collection("memberships").document(groupId);

            Map<String,Object> member = new HashMap<>();
            member.put("uid", uid);
            member.put("role", "member");
            member.put("joinedAt", FieldValue.serverTimestamp());
            member.put("monitoringEnabled", true);
            member.put("notificationPreferences", preferences());
            member.put("invitationCodeHash", codeHash);

            Map<String,Object> membership = new HashMap<>();
            membership.put("groupId", groupId);
            membership.put("joinedAt", FieldValue.serverTimestamp());
            membership.put("invitationCodeHash", codeHash);

            WriteBatch batch = db.batch();
            batch.set(memberRef, member);
            batch.set(membershipRef, membership);
            batch.commit().addOnSuccessListener(v -> {
                getSharedPreferences("deleted_groups", MODE_PRIVATE).edit().remove("deleted_" + groupId).apply();
                toast("Joined group.");
                loadGroups();
                checkAndRequestAllPermissions();
            }).addOnFailureListener(e -> toast("Could not join: " + e.getLocalizedMessage()));
        }).addOnFailureListener(e -> toast("Could not check invitation code: " + e.getLocalizedMessage()));
    }

    private void leaveGroupDirect(String groupId) {
        if (auth.getCurrentUser() == null) return;
        String uid = auth.getCurrentUser().getUid();

        // 1. Immediately persist departure in local storage so it NEVER reappears on refresh
        getSharedPreferences("deleted_groups", MODE_PRIVATE).edit().putBoolean("deleted_" + groupId, true).apply();
        GeofenceManager.remove(this, groupId);
        activeMonitoredGroups.remove(groupId);
        loadGroups();
        toast("Left group.");

        // 2. Remove member and membership documents from Firestore
        DocumentReference groupRef = db.collection("groups").document(groupId);
        DocumentReference memberRef = groupRef.collection("members").document(uid);
        DocumentReference membershipRef = db.collection("users").document(uid).collection("memberships").document(groupId);
        memberRef.delete();
        membershipRef.delete();
    }

    private void deleteGroupDirect(String groupId) {
        if (auth.getCurrentUser() == null) return;
        String uid = auth.getCurrentUser().getUid();

        // 1. Immediately persist deletion in local storage so it NEVER reappears on refresh
        getSharedPreferences("deleted_groups", MODE_PRIVATE).edit().putBoolean("deleted_" + groupId, true).apply();
        GeofenceManager.remove(this, groupId);
        activeMonitoredGroups.remove(groupId);
        loadGroups();
        toast("Group deleted.");

        DocumentReference groupRef = db.collection("groups").document(groupId);
        DocumentReference myMemberRef = groupRef.collection("members").document(uid);
        DocumentReference myMembershipRef = db.collection("users").document(uid).collection("memberships").document(groupId);

        // 2. Broadcast a DELETED event to other group members so their UI cleans up too
        Map<String, Object> delEvent = new HashMap<>();
        delEvent.put("eventType", "DELETED");
        delEvent.put("groupId", groupId);
        delEvent.put("memberUid", uid);
        delEvent.put("createdAt", FieldValue.serverTimestamp());
        groupRef.collection("events").add(delEvent);

        // 3. Clean up Firestore documents in background
        groupRef.get().addOnSuccessListener(groupSnap -> {
            String codeHash = groupSnap != null && groupSnap.exists() ? groupSnap.getString("invitationCodeHash") : null;
            if (codeHash != null && !codeHash.isEmpty()) {
                db.collection("invitationCodes").document(codeHash).delete();
            }

            groupRef.collection("members").get().addOnSuccessListener(membersSnap -> {
                if (membersSnap != null) {
                    for (DocumentSnapshot doc : membersSnap.getDocuments()) {
                        doc.getReference().delete();
                    }
                }
                myMemberRef.delete();
                myMembershipRef.delete();
                groupRef.delete();
            }).addOnFailureListener(e -> {
                myMemberRef.delete();
                myMembershipRef.delete();
                groupRef.delete();
            });
        }).addOnFailureListener(e -> {
            myMemberRef.delete();
            myMembershipRef.delete();
            groupRef.delete();
        });
    }

    private void confirmDeleteGroup(String groupId, String groupName) {
        new AlertDialog.Builder(this)
                .setTitle("Delete group")
                .setMessage("Are you sure you want to delete \"" + groupName + "\"? All members will be removed and geofencing will stop.")
                .setPositiveButton("Delete", (d, w) -> deleteGroupDirect(groupId))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmOwnerLeaveGroup(String groupId, String groupName) {
        DocumentReference groupRef = db.collection("groups").document(groupId);
        groupRef.collection("members").get().addOnSuccessListener(membersSnap -> {
            String uid = auth.getCurrentUser() != null ? auth.getCurrentUser().getUid() : "";
            List<DocumentSnapshot> otherMembers = new ArrayList<>();
            if (membersSnap != null) {
                for (DocumentSnapshot doc : membersSnap.getDocuments()) {
                    if (!doc.getId().equals(uid)) {
                        otherMembers.add(doc);
                    }
                }
            }

            if (otherMembers.isEmpty()) {
                // Owner is sole member: leaving deletes the group cleanly
                new AlertDialog.Builder(this)
                        .setTitle("Leave group")
                        .setMessage("You are the admin and only member of \"" + groupName + "\". Leaving will delete the group.")
                        .setPositiveButton("Delete & Leave", (d, w) -> deleteGroupDirect(groupId))
                        .setNegativeButton("Cancel", null)
                        .show();
            } else {
                // Other members exist: offer to transfer ownership or delete group
                new AlertDialog.Builder(this)
                        .setTitle("Admin departure")
                        .setMessage("You are the admin of \"" + groupName + "\". Would you like to transfer admin status to another member and leave, or delete the group?")
                        .setPositiveButton("Delete group", (d, w) -> deleteGroupDirect(groupId))
                        .setNeutralButton("Transfer & Leave", (d, w) -> {
                            String nextOwnerUid = otherMembers.get(0).getId();
                            WriteBatch batch = db.batch();
                            batch.update(groupRef, "ownerUid", nextOwnerUid, "updatedAt", FieldValue.serverTimestamp());
                            batch.update(groupRef.collection("members").document(nextOwnerUid), "role", "owner");
                            batch.delete(groupRef.collection("members").document(uid));
                            batch.delete(db.collection("users").document(uid).collection("memberships").document(groupId));
                            batch.commit().addOnSuccessListener(v -> {
                                GeofenceManager.remove(this, groupId);
                                activeMonitoredGroups.remove(groupId);
                                loadGroups();
                                toast("Transferred ownership and left group.");
                            }).addOnFailureListener(e -> toast("Failed to transfer: " + e.getLocalizedMessage()));
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        }).addOnFailureListener(e -> toast("Could not inspect group members: " + e.getLocalizedMessage()));
    }

    private Map<String,Object> preferences() {
        Map<String,Object> p = new HashMap<>(); p.put("enter", true); p.put("exit", true); return p;
    }
    private String newInvitationCode() {
        final char[] chars = "0123456789ABCDEF".toCharArray();
        java.security.SecureRandom random = new java.security.SecureRandom();
        StringBuilder b = new StringBuilder(10);
        for (int i=0; i<10; i++) b.append(chars[random.nextInt(chars.length)]);
        return b.toString();
    }
    private String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : digest) b.append(String.format(Locale.US, "%02x", x & 0xff));
            return b.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private void joinDialog() {
        EditText code = new EditText(this); code.setHint("Invitation code"); code.setSingleLine(true);
        new AlertDialog.Builder(this).setTitle("Join a group").setView(code)
                .setPositiveButton("Join", (d,w) -> joinGroupDirect(code.getText().toString().trim().toUpperCase(Locale.US)))
                .setNegativeButton("Cancel", null).show();
    }
    private LinearLayout formBox() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(24,8,24,8); return l; }
    private EditText edit(String hint, LinearLayout parent) { EditText e=new EditText(this); e.setHint(hint); parent.addView(e); return e; }

    private void loadGroups() {
        if (auth.getCurrentUser() == null) return;
        String uid = auth.getCurrentUser().getUid();
        groups.removeAllViews();
        clearGroupListeners();
        activeMonitoredGroups.clear();
        listenToPersonalNotifications();
        db.collection("users").document(uid).collection("memberships").get().addOnSuccessListener(snapshot -> {
            if (snapshot == null || snapshot.isEmpty()) {
                TextView empty = new TextView(this);
                empty.setText("No groups yet. Create a group or join with an invitation code.");
                empty.setPadding(8, 24, 8, 24);
                groups.addView(empty);
                return;
            }
            for (DocumentSnapshot m : snapshot.getDocuments()) {
                String gid = m.getId();
                android.content.SharedPreferences delPrefs = getSharedPreferences("deleted_groups", MODE_PRIVATE);
                if (delPrefs.getBoolean("deleted_" + gid, false)) {
                    // Stale or deleted group: purge server reference in background and do not render
                    m.getReference().delete();
                    GeofenceManager.remove(this, gid);
                    activeMonitoredGroups.remove(gid);
                    continue;
                }
                listenToGroupEvents(gid);
                db.collection("groups").document(gid).get().addOnSuccessListener(g -> {
                    if (!g.exists()) {
                        // Purge stale membership if group was deleted
                        m.getReference().delete();
                        GeofenceManager.remove(this, gid);
                        activeMonitoredGroups.remove(gid);
                        return;
                    }
                    View item = getLayoutInflater().inflate(R.layout.item_group, groups, false);
                    String gName = g.getString("name") != null ? g.getString("name") : "Group";
                    ((TextView) item.findViewById(R.id.groupName)).setText(gName);
                    ((TextView) item.findViewById(R.id.groupInfo)).setText((g.getString("description") != null ? g.getString("description") : "") + "\nRadius: " + g.getLong("radiusMeters") + " m");
                    item.findViewById(R.id.detailsButton).setOnClickListener(v -> showGroup(g));
                    groups.addView(item);

                    if (g.getDouble("latitude") != null && g.getDouble("longitude") != null && g.getLong("radiusMeters") != null) {
                        double lat = g.getDouble("latitude");
                        double lon = g.getDouble("longitude");
                        int radius = g.getLong("radiusMeters").intValue();
                        int version = g.getLong("configurationVersion") == null ? 1 : g.getLong("configurationVersion").intValue();

                        MonitoredGroup mg = new MonitoredGroup();
                        mg.id = gid;
                        mg.name = gName;
                        mg.lat = lat;
                        mg.lon = lon;
                        mg.radius = radius;
                        mg.version = version;
                        activeMonitoredGroups.put(gid, mg);

                        GeofenceManager.register(this, gid, gName, lat, lon, radius, version);
                    }
                });
            }
        }).addOnFailureListener(e -> toast("Could not load groups: " + e.getLocalizedMessage()));
    }

    private void showGroup(DocumentSnapshot g) {
        String gid = g.getId();
        String code = g.getString("invitationCode");
        String name = g.getString("name") != null ? g.getString("name") : "Group";
        String ownerUid = g.getString("ownerUid");
        String currentUid = auth.getCurrentUser() != null ? auth.getCurrentUser().getUid() : "";
        boolean isOwner = currentUid.equals(ownerUid);

        String details = name + "\n\n" +
                (g.getString("description") != null && !g.getString("description").isEmpty() ? g.getString("description") + "\n\n" : "") +
                "Centre: " + g.getDouble("latitude") + ", " + g.getDouble("longitude") +
                "\nRadius: " + g.getLong("radiusMeters") + " m\n" +
                "Role: " + (isOwner ? "Admin (Owner)" : "Member") + "\n\n" +
                "Invitation code: " + (code == null ? "Only owner can view code" : code);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Group details")
                .setMessage(details);

        if (isOwner) {
            builder.setPositiveButton("Delete group", (d, w) -> confirmDeleteGroup(gid, name));
            builder.setNeutralButton("Leave group", (d, w) -> confirmOwnerLeaveGroup(gid, name));
            builder.setNegativeButton("Close", null);
        } else {
            builder.setPositiveButton("Leave group", (d, w) -> leaveGroupDirect(gid));
            builder.setNegativeButton("Close", null);
        }

        builder.show();
    }

    private void showHistory() {
        if (auth.getCurrentUser() == null) return;
        String uid = auth.getCurrentUser().getUid();
        db.collection("users").document(uid).collection("notifications")
                .orderBy("createdAt", Query.Direction.DESCENDING).limit(50).get().addOnSuccessListener(s -> {
                    if (s != null && !s.isEmpty()) {
                        displayHistoryDialog(s.getDocuments());
                    } else {
                        loadHistoryFromGroupEvents();
                    }
                }).addOnFailureListener(e -> {
                    Log.w("MainActivity", "Failed querying notifications with orderBy: " + e.getMessage());
                    db.collection("users").document(uid).collection("notifications").limit(50).get()
                            .addOnSuccessListener(s2 -> {
                                if (s2 != null && !s2.isEmpty()) {
                                    displayHistoryDialog(s2.getDocuments());
                                } else {
                                    loadHistoryFromGroupEvents();
                                }
                            }).addOnFailureListener(e2 -> loadHistoryFromGroupEvents());
                });
    }

    private void displayHistoryDialog(List<DocumentSnapshot> docs) {
        StringBuilder b = new StringBuilder();
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MMM dd, yyyy HH:mm:ss", java.util.Locale.getDefault());
        List<DocumentSnapshot> sortedDocs = new ArrayList<>(docs);
        Collections.sort(sortedDocs, (d1, d2) -> {
            Date t1 = d1.getTimestamp("createdAt") != null ? d1.getTimestamp("createdAt").toDate() : d1.getDate("clientEventTime");
            Date t2 = d2.getTimestamp("createdAt") != null ? d2.getTimestamp("createdAt").toDate() : d2.getDate("clientEventTime");
            if (t1 == null && t2 == null) return 0;
            if (t1 == null) return 1;
            if (t2 == null) return -1;
            return t2.compareTo(t1);
        });

        String currentUid = auth.getCurrentUser() != null ? auth.getCurrentUser().getUid() : "";
        String myName = getSharedPreferences("user_profile", MODE_PRIVATE).getString("display_name", null);
        if (myName == null || myName.isEmpty()) {
            myName = auth.getCurrentUser() != null && auth.getCurrentUser().getDisplayName() != null ? auth.getCurrentUser().getDisplayName() : "You";
        }

        for (DocumentSnapshot d : sortedDocs) {
            String title = d.getString("title");
            String gName = d.getString("groupName");
            if (title == null || title.isEmpty()) {
                title = (gName != null ? gName : "Group") + " — Location Alert";
            }
            if (gName == null || gName.isEmpty()) {
                gName = "the group";
            }

            String memberUid = d.getString("memberUid");
            boolean isSelf = currentUid.equals(memberUid);
            String mName = d.getString("memberName");
            if (mName == null || mName.trim().isEmpty() || "A member".equals(mName)) {
                mName = isSelf ? myName : "A member";
            }

            String type = d.getString("eventType");
            String action = "EXIT".equals(type) ? "left " : "entered ";
            String body;
            if (isSelf) {
                body = mName + " (You) has " + action + gName + "'s designated area.";
            } else {
                body = mName + " has " + action + gName + "'s designated area.";
            }

            Date date = null;
            if (d.getTimestamp("createdAt") != null) {
                date = d.getTimestamp("createdAt").toDate();
            } else if (d.getDate("clientEventTime") != null) {
                date = d.getDate("clientEventTime");
            }
            String timeStr = date != null ? sdf.format(date) : "";

            b.append("• ").append(title).append("\n  ").append(body);
            if (!timeStr.isEmpty()) {
                b.append("\n  Time: ").append(timeStr);
            }
            b.append("\n\n");
        }
        new AlertDialog.Builder(this)
                .setTitle("Recent alerts")
                .setMessage(b.length() == 0 ? "No notifications yet." : b.toString().trim())
                .setPositiveButton("Close", null)
                .show();
    }

    private void loadHistoryFromGroupEvents() {
        if (auth.getCurrentUser() == null) return;
        String uid = auth.getCurrentUser().getUid();
        db.collection("users").document(uid).collection("memberships").get().addOnSuccessListener(snapshots -> {
            if (snapshots == null || snapshots.isEmpty()) {
                new AlertDialog.Builder(this)
                        .setTitle("Recent alerts")
                        .setMessage("No notifications yet.")
                        .setPositiveButton("Close", null)
                        .show();
                return;
            }
            List<String> groupIds = new ArrayList<>();
            for (DocumentSnapshot doc : snapshots.getDocuments()) {
                groupIds.add(doc.getId());
            }

            List<DocumentSnapshot> allEvents = new ArrayList<>();
            java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger(groupIds.size());

            for (String gid : groupIds) {
                db.collection("groups").document(gid).collection("events")
                        .limit(25).get().addOnCompleteListener(task -> {
                            if (task.isSuccessful() && task.getResult() != null) {
                                synchronized (allEvents) {
                                    allEvents.addAll(task.getResult().getDocuments());
                                }
                            }
                            if (pending.decrementAndGet() == 0) {
                                if (allEvents.isEmpty()) {
                                    new AlertDialog.Builder(this)
                                            .setTitle("Recent alerts")
                                            .setMessage("No notifications yet.")
                                            .setPositiveButton("Close", null)
                                            .show();
                                } else {
                                    Collections.sort(allEvents, (d1, d2) -> {
                                        Date t1 = d1.getDate("clientEventTime");
                                        Date t2 = d2.getDate("clientEventTime");
                                        if (t1 == null && d1.getTimestamp("createdAt") != null) t1 = d1.getTimestamp("createdAt").toDate();
                                        if (t2 == null && d2.getTimestamp("createdAt") != null) t2 = d2.getTimestamp("createdAt").toDate();
                                        if (t1 == null && t2 == null) return 0;
                                        if (t1 == null) return 1;
                                        if (t2 == null) return -1;
                                        return t2.compareTo(t1);
                                    });
                                    displayHistoryDialog(allEvents);
                                }
                            }
                        });
            }
        }).addOnFailureListener(e -> {
            new AlertDialog.Builder(this)
                    .setTitle("Recent alerts")
                    .setMessage("No notifications yet.")
                    .setPositiveButton("Close", null)
                    .show();
        });
    }

    private void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }

    private void listenToPersonalNotifications() {
        if (auth.getCurrentUser() == null) return;
        String uid = auth.getCurrentUser().getUid();
        android.content.SharedPreferences notifPrefs = getSharedPreferences("notified_events_cache", MODE_PRIVATE);

        ListenerRegistration reg = db.collection("users").document(uid).collection("notifications")
                .addSnapshotListener((snapshots, error) -> {
                    if (error != null || snapshots == null) return;

                    for (DocumentSnapshot d : snapshots.getDocuments()) {
                        String memberUid = d.getString("memberUid");
                        if (memberUid != null && memberUid.equals(uid)) continue;

                        String eventId = d.getString("eventId");
                        if (eventId == null || eventId.isEmpty()) eventId = d.getId();
                        if (notifPrefs.getBoolean("notified_" + eventId, false)) continue;

                        long eventTime = 0;
                        if (d.getTimestamp("createdAt") != null) {
                            eventTime = d.getTimestamp("createdAt").toDate().getTime();
                        } else if (d.getDate("clientEventTime") != null) {
                            eventTime = d.getDate("clientEventTime").getTime();
                        }
                        if (eventTime > 0 && (System.currentTimeMillis() - eventTime > 15 * 60 * 1000)) {
                            notifPrefs.edit().putBoolean("notified_" + eventId, true).apply();
                            continue;
                        }

                        String title = d.getString("title");
                        String body = d.getString("body");
                        String groupId = d.getString("groupId");

                        if (title == null || title.isEmpty()) title = "Location Alert";
                        if (body == null || body.isEmpty()) body = "A member entered or left the designated area.";

                        notifPrefs.edit().putBoolean("notified_" + eventId, true).apply();
                        NotificationHelper.showNotification(this, title, body, groupId, eventId);
                        final String displayTitle = title;
                        final String displayBody = body;
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, displayTitle + "\n" + displayBody, Toast.LENGTH_LONG).show());
                    }
                });
        eventListeners.add(reg);
    }

    private void listenToGroupEvents(String groupId) {
        if (auth.getCurrentUser() == null) return;
        String uid = auth.getCurrentUser().getUid();
        android.content.SharedPreferences notifPrefs = getSharedPreferences("notified_events_cache", MODE_PRIVATE);

        ListenerRegistration reg = db.collection("groups").document(groupId).collection("events")
                .addSnapshotListener((snapshots, error) -> {
                    if (error != null || snapshots == null) return;

                    for (DocumentSnapshot d : snapshots.getDocuments()) {
                        String type = d.getString("eventType");

                        // If admin deleted the group, automatically purge it locally
                        if ("DELETED".equals(type)) {
                            getSharedPreferences("deleted_groups", MODE_PRIVATE).edit().putBoolean("deleted_" + groupId, true).apply();
                            GeofenceManager.remove(this, groupId);
                            activeMonitoredGroups.remove(groupId);
                            runOnUiThread(() -> {
                                loadGroups();
                                Toast.makeText(MainActivity.this, "A group was deleted by the admin.", Toast.LENGTH_SHORT).show();
                            });
                            return;
                        }

                        String memberUid = d.getString("memberUid");
                        if (memberUid != null && !memberUid.equals(uid)) {
                            String eventId = d.getString("eventId");
                            if (eventId == null || eventId.isEmpty()) eventId = d.getId();
                            if (notifPrefs.getBoolean("notified_" + eventId, false)) continue;

                            long eventTime = 0;
                            if (d.getTimestamp("createdAt") != null) {
                                eventTime = d.getTimestamp("createdAt").toDate().getTime();
                            } else if (d.getDate("clientEventTime") != null) {
                                eventTime = d.getDate("clientEventTime").getTime();
                            }
                            if (eventTime > 0 && (System.currentTimeMillis() - eventTime > 15 * 60 * 1000)) {
                                notifPrefs.edit().putBoolean("notified_" + eventId, true).apply();
                                continue;
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
                            final String displayTitle = title;
                            final String displayBody = body;
                            runOnUiThread(() -> Toast.makeText(MainActivity.this, displayTitle + "\n" + displayBody, Toast.LENGTH_LONG).show());
                        }
                    }
                });
        eventListeners.add(reg);
    }

    private void startActiveLocationTracking() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (activeLocationCallback != null) {
            return;
        }

        // High accuracy location polling
        LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 6000)
                .setMinUpdateIntervalMillis(3000)
                .setMinUpdateDistanceMeters(5)
                .build();

        activeLocationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) return;
                Location location = locationResult.getLastLocation();
                if (location != null) {
                    evaluateVicinityBoundaries(location);
                }
            }
        };

        fusedLocationClient.requestLocationUpdates(request, activeLocationCallback, Looper.getMainLooper());
    }

    private void evaluateVicinityBoundaries(Location location) {
        if (activeMonitoredGroups.isEmpty()) return;
        if (location == null) return;
        // Ignore inaccurate location fixes (e.g. cellular drift/poor GPS fix) to prevent false alerts
        if (location.hasAccuracy() && location.getAccuracy() > 30.0f) {
            return;
        }

        android.content.SharedPreferences prefs = getSharedPreferences("vicinity_state_prefs", MODE_PRIVATE);

        for (MonitoredGroup g : activeMonitoredGroups.values()) {
            float[] results = new float[1];
            Location.distanceBetween(location.getLatitude(), location.getLongitude(), g.lat, g.lon, results);
            float dist = results[0];

            String stateKey = "active_state_" + g.id;
            String lastState = prefs.getString(stateKey, null);

            if (lastState == null) {
                // Initialize baseline location without triggering alert
                prefs.edit().putString(stateKey, dist <= g.radius ? "INSIDE" : "OUTSIDE").apply();
                continue;
            }

            // Hysteresis buffer of 15m to prevent false alarms due to GPS drift at the fence boundary
            if ("INSIDE".equals(lastState) && dist > (g.radius + 15)) {
                // Member actually walked outside the designated area
                prefs.edit().putString(stateKey, "OUTSIDE").apply();
                android.util.Log.i("MainActivity", "User EXITED group vicinity: " + g.name + " (" + dist + "m > " + (g.radius + 15) + "m)");
                new Thread(() -> VicinityNotifier.handleTransition(MainActivity.this, g.id, g.name, g.version, "EXIT")).start();
            } else if ("OUTSIDE".equals(lastState) && dist < Math.max(30, g.radius - 15)) {
                // Member actually walked inside the designated area
                prefs.edit().putString(stateKey, "INSIDE").apply();
                android.util.Log.i("MainActivity", "User ENTERED group vicinity: " + g.name + " (" + dist + "m < " + (g.radius - 15) + "m)");
                new Thread(() -> VicinityNotifier.handleTransition(MainActivity.this, g.id, g.name, g.version, "ENTER")).start();
            }
        }
    }

    private void stopActiveLocationTracking() {
        if (activeLocationCallback != null) {
            fusedLocationClient.removeLocationUpdates(activeLocationCallback);
            activeLocationCallback = null;
        }
    }

    private void clearGroupListeners() {
        for (ListenerRegistration reg : eventListeners) {
            if (reg != null) reg.remove();
        }
        eventListeners.clear();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopActiveLocationTracking();
        clearGroupListeners();
    }
}
