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
import android.telephony.SmsManager;
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

import java.util.Calendar;

public class MainActivity extends AppCompatActivity
        implements OnMapReadyCallback, SensorEventListener {

    private static final int PERMISSION_REQ_CODE = 101;
    private static final long COOLDOWN_TIME_MS = 60_000;

    // Map & location
    private GoogleMap mMap;
    private FusedLocationProviderClient fusedLocationClient;

    // Zone
    private String currentZoneType = "Green";

    // Sensors
    private SensorManager sensorManager;
    private boolean isRunning = false;

    // 🔊 Scream detection
    private MediaRecorder recorder;
    private boolean isScreaming = false;
    private int screamCounter = 0;
    private static final int SCREAM_THRESHOLD = 15000;
    private static final int SCREAM_REQUIRED_COUNT = 4;

    // Time & overrides
    private boolean isNight = false;
    private boolean assumeScream = false;

    // UI
    private TextView dangerScoreText, detectorDetails;
    private TextView runStatusText, screamStatusText, countdownText;
    private ToggleButton toggleNight, toggleScream;
    private View sosOverlay;

    // SOS
    private CountDownTimer sosTimer;
    private boolean isSOSActive = false;
    private boolean isCooldownActive = false;

    private Handler handler = new Handler(Looper.getMainLooper());

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

    // ---------------- PERMISSIONS ----------------

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

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startScreamDetection();
        }
    }

    // ---------------- MAIN LOOP ----------------

    private void startMainLoop() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkTime();
                calculateDangerScore();
                handler.postDelayed(this, 300);
            }
        }, 300);
    }

    // ---------------- SCORE ----------------

    private void calculateDangerScore() {
        if (isCooldownActive || isSOSActive) return;

        int score = 0;

        if (currentZoneType.equals("Red")) score += 40;
        else if (currentZoneType.equals("Yellow")) score += 30;

        if (isNight) score += 10;
        if (isRunning) score += 10;
        if (assumeScream || isScreaming) score += 20;

        dangerScoreText.setText("Danger Score: " + score + "%");
        runStatusText.setText(isRunning ? "🏃 RUNNING DETECTED" : "🏃 Not running");
        screamStatusText.setText(
                (assumeScream || isScreaming) ? "🎤 SCREAM DETECTED" : "🎤 No scream");

        detectorDetails.setText("Zone: " + currentZoneType + " | Night: " + isNight);

        if (score >= 70) triggerSOS();
    }

    // ---------------- SOS ----------------

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

    // ---------------- COOLDOWN ----------------

    private void pauseDetectionForCooldown() {
        isCooldownActive = true;
        Toast.makeText(this, "Detection paused for 1 minute", Toast.LENGTH_SHORT).show();

        handler.postDelayed(() -> {
            isCooldownActive = false;
            Toast.makeText(this, "Detection resumed", Toast.LENGTH_SHORT).show();
        }, COOLDOWN_TIME_MS);
    }

    // ---------------- 🎤 SCREAM DETECTION ----------------

    private void startScreamDetection() {
        try {
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            recorder.setOutputFile("/dev/null");

            recorder.prepare();
            recorder.start();

            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (recorder != null && !isCooldownActive && !isSOSActive) {
                        int amp = recorder.getMaxAmplitude();

                        if (amp > SCREAM_THRESHOLD) {
                            screamCounter++;
                            if (screamCounter >= SCREAM_REQUIRED_COUNT) {
                                isScreaming = true;
                            }
                        } else {
                            screamCounter = 0;
                            isScreaming = false;
                        }
                    }
                    handler.postDelayed(this, 500);
                }
            }, 500);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ---------------- SENSORS ----------------

    @Override
    public void onSensorChanged(SensorEvent e) {
        double mag = Math.sqrt(
                e.values[0] * e.values[0] +
                        e.values[1] * e.values[1] +
                        e.values[2] * e.values[2]);
        isRunning = mag > 15;
    }

    @Override public void onAccuracyChanged(Sensor s, int a) {}

    // ---------------- TIME ----------------

    private void checkTime() {
        if (!toggleNight.isChecked()) {
            int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            isNight = (h >= 20 || h <= 5);
        }
    }

    // ---------------- MAP ----------------

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

    @Override
    protected void onPause() {
        super.onPause();
        if (recorder != null) {
            recorder.release();
            recorder = null;
        }
    }
}