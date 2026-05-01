package com.example.womensafetyapp;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.MediaRecorder;
import android.os.*;
import org.tensorflow.lite.task.audio.classifier.AudioClassifier;
import org.tensorflow.lite.task.audio.classifier.Classifications;
import org.tensorflow.lite.support.label.Category;
import org.tensorflow.lite.support.audio.TensorAudio;
import android.media.AudioRecord;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import android.graphics.Color;
import android.location.Location;
import android.telephony.SmsManager;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.*;
import com.google.android.gms.maps.*;
import com.google.android.gms.maps.model.*;

import java.io.File;
import java.util.Calendar;

public class MainActivity extends AppCompatActivity
        implements OnMapReadyCallback, SensorEventListener {

    private static final int PERMISSION_REQ_CODE = 101;
    private static final long COOLDOWN_TIME_MS = 60_000;

    // ---------------- MAP & LOCATION ----------------
    private GoogleMap mMap;
    private FusedLocationProviderClient fusedLocationClient;
    private Circle zoneCircle;

    // ---------------- ZONE ----------------
    private String currentZoneType = "Green";

    // ---------------- SENSORS ----------------
    private SensorManager sensorManager;
    private boolean isRunning = false;

    // ---------------- 🎤 YAMNET SCREAM DETECTION ----------------
    private AudioClassifier audioClassifier;
    private TensorAudio tensorAudio;
    private AudioRecord audioRecord;
    private ScheduledThreadPoolExecutor executor;
    private boolean isScreaming = false;
    private long lastScreamTime = 0;
    private String currentDetectedSound = "None";

    // ---------------- TIME & OVERRIDES ----------------
    private boolean isNight = false;
    private boolean assumeScream = false;

    // ---------------- UI ----------------
    private TextView dangerScoreText, detectorDetails;
    private TextView runStatusText, screamStatusText, countdownText;
    private ToggleButton toggleNight, toggleScream;
    private View sosOverlay;

    // ---------------- SOS ----------------
    private CountDownTimer sosTimer;
    private boolean isSOSActive = false;
    private boolean isCooldownActive = false;

    private Handler handler = new Handler(Looper.getMainLooper());

    // ================= LIFECYCLE =================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

        ((SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map)).getMapAsync(this);

        setupButtons();
        requestAllPermissions();
        startMainLoop();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {

            handler.postDelayed(this::startScreamDetection, 500);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopScreamDetection();
    }

    // ================= UI =================

    private void bindViews() {
        dangerScoreText = findViewById(R.id.dangerScoreText);
        detectorDetails = findViewById(R.id.detectorDetails);
        runStatusText = findViewById(R.id.runStatusText);
        screamStatusText = findViewById(R.id.screamStatusText);
        countdownText = findViewById(R.id.countdownText);
        sosOverlay = findViewById(R.id.sosOverlay);
        toggleNight = findViewById(R.id.toggleNight);
        toggleScream = findViewById(R.id.toggleScream);
    }

    private void setupButtons() {
        findViewById(R.id.btnRed).setOnClickListener(v -> currentZoneType = "Red");
        findViewById(R.id.btnYellow).setOnClickListener(v -> currentZoneType = "Yellow");
        findViewById(R.id.btnGreen).setOnClickListener(v -> currentZoneType = "Green");
        findViewById(R.id.btnCancelSOS).setOnClickListener(v -> cancelSOS());

        toggleNight.setOnCheckedChangeListener((b, c) -> isNight = c);
        toggleScream.setOnCheckedChangeListener((b, c) -> assumeScream = c);
    }

    // ================= PERMISSIONS =================

    private void requestAllPermissions() {
        ActivityCompat.requestPermissions(
                this,
                new String[]{
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.SEND_SMS
                },
                PERMISSION_REQ_CODE
        );
    }

    // ================= 🎤 YAMNET AUDIO CLASSIFICATION =================

    private void startScreamDetection() {
        if (audioClassifier != null) return;

        try {
            audioClassifier = AudioClassifier.createFromFile(this, "yamnet.tflite");
            tensorAudio = audioClassifier.createInputTensorAudio();
            audioRecord = audioClassifier.createAudioRecord();
            audioRecord.startRecording();

            executor = new ScheduledThreadPoolExecutor(1);
            executor.scheduleAtFixedRate(this::classifyAudio, 0, 500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Log.e("YAMNET_ERROR", "Failed to start audio classifier", e);
            stopScreamDetection();
        }
    }

    private void classifyAudio() {
        if (audioRecord == null || audioClassifier == null || isCooldownActive || isSOSActive) return;

        tensorAudio.load(audioRecord);
        List<Classifications> output = audioClassifier.classify(tensorAudio);

        boolean screamDetectedNow = false;

        if (!output.isEmpty() && output.get(0).getCategories() != null && !output.get(0).getCategories().isEmpty()) {
            Category topCategory = output.get(0).getCategories().get(0);
            currentDetectedSound = topCategory.getLabel();

            for (Category category : output.get(0).getCategories()) {
                if (category.getScore() > 0.3f) {
                    String label = category.getLabel().toLowerCase();
                    if (label.contains("scream") || label.contains("yell") || label.contains("shriek")) {
                        screamDetectedNow = true;
                        Log.d("YAMNET", "Scream detected! " + label + " : " + category.getScore());
                        
                        final String match = category.getLabel();
                        handler.post(() -> Toast.makeText(MainActivity.this, "YAMNET: " + match, Toast.LENGTH_SHORT).show());
                        break;
                    }
                }
            }
        }

        if (screamDetectedNow) {
            isScreaming = true;
            lastScreamTime = System.currentTimeMillis();
        } else {
            isScreaming = false; 
        }
    }

    private void stopScreamDetection() {
        try {
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
            }
            if (audioClassifier != null) {
                audioClassifier.close();
                audioClassifier = null;
            }
        } catch (Exception ignored) {}
    }

    // ================= MAIN LOOP =================

    private void startMainLoop() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkTime();
                updateMapZoneCircle();
                calculateDangerScore();
                handler.postDelayed(this, 300);
            }
        }, 300);
    }

    private void updateMapZoneCircle() {
        if (mMap == null) return;
        
        try {
            Location loc = mMap.getMyLocation();
            if (loc == null) return;

            LatLng currentLatLng = new LatLng(loc.getLatitude(), loc.getLongitude());

            int color;
            switch (currentZoneType) {
                case "Red": color = Color.argb(64, 255, 0, 0); break;
                case "Yellow": color = Color.argb(64, 255, 255, 0); break;
                default: color = Color.argb(64, 0, 255, 0); break;
            }

            if (zoneCircle == null) {
                zoneCircle = mMap.addCircle(new CircleOptions()
                        .center(currentLatLng)
                        .radius(500)
                        .strokeWidth(2)
                        .strokeColor(color)
                        .fillColor(color));
            } else {
                zoneCircle.setCenter(currentLatLng);
                zoneCircle.setFillColor(color);
                zoneCircle.setStrokeColor(color);
            }
        } catch (SecurityException e) {
            // Location permission not granted yet
        }
    }

    // ================= SCORE =================

    private void calculateDangerScore() {
        if (isCooldownActive || isSOSActive) return;

        int score = 0;

        if (currentZoneType.equals("Red")) score += 40;
        else if (currentZoneType.equals("Yellow")) score += 30;

        if (isNight) score += 10;
        if (isRunning) score += 10;
        
        boolean recentScream = isScreaming || (System.currentTimeMillis() - lastScreamTime < 5000);
        
        if (assumeScream || recentScream) score += 20;

        dangerScoreText.setText("Danger Score: " + score + "%");
        runStatusText.setText(isRunning ? "🏃 RUNNING DETECTED" : "🏃 Not running");
        screamStatusText.setText(
                (assumeScream || recentScream) ? "🎤 YAMNET SCREAM DETECTED" : "🎤 No scream");

        detectorDetails.setText("Zone: " + currentZoneType + " | Night: " + isNight + "\nSound: " + currentDetectedSound);

        if (score >= 70) triggerSOS();
    }

    // ================= SOS =================

    private void triggerSOS() {
        isSOSActive = true;
        sosOverlay.setVisibility(View.VISIBLE);

        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) v.vibrate(1000);

        sosTimer = new CountDownTimer(10000, 1000) {
            @Override
            public void onTick(long ms) {
                countdownText.setText("" + (ms / 1000));
            }

            @Override
            public void onFinish() {
                sendSOSMessage();
                stopSOSAndCooldown();
            }
        }.start();
    }

    private void sendSOSMessage() {
        if (mMap == null || mMap.getMyLocation() == null) return;

        String msg = "EMERGENCY! I am in danger. Location: http://maps.google.com/maps?q="
                + mMap.getMyLocation().getLatitude() + ","
                + mMap.getMyLocation().getLongitude();

        SmsManager.getDefault().sendTextMessage(
                "8692854124", null, msg, null, null);
    }

    private void cancelSOS() {
        Toast.makeText(this, "You are safe", Toast.LENGTH_SHORT).show();
        stopSOSAndCooldown();
    }

    private void stopSOSAndCooldown() {
        if (sosTimer != null) {
            sosTimer.cancel();
            sosTimer = null;
        }

        sosOverlay.setVisibility(View.GONE);
        isSOSActive = false;
        pauseDetectionForCooldown();
    }

    private void pauseDetectionForCooldown() {
        isCooldownActive = true;
        Toast.makeText(this, "Detection paused for 1 minute", Toast.LENGTH_SHORT).show();

        handler.postDelayed(() -> {
            isCooldownActive = false;
            Toast.makeText(this, "Detection resumed", Toast.LENGTH_SHORT).show();
        }, COOLDOWN_TIME_MS);
    }

    // ================= SENSORS =================

    @Override
    public void onSensorChanged(SensorEvent e) {
        double mag = Math.sqrt(
                e.values[0]*e.values[0] +
                        e.values[1]*e.values[1] +
                        e.values[2]*e.values[2]);
        isRunning = mag > 15;
    }

    @Override public void onAccuracyChanged(Sensor s, int a) {}

    // ================= TIME =================

    private void checkTime() {
        if (!toggleNight.isChecked()) {
            int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            isNight = (h >= 20 || h <= 5);
        }
    }

    // ================= MAP =================

    @Override
    public void onMapReady(@NonNull GoogleMap g) {
        mMap = g;

        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            mMap.setMyLocationEnabled(true);
        }

        sensorManager.registerListener(
                this,
                sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                SensorManager.SENSOR_DELAY_NORMAL
        );
    }
}