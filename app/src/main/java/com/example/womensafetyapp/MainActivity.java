package com.example.womensafetyapp;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.telephony.SmsManager;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;


import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;



import com.google.android.gms.location.*;

import com.google.android.gms.maps.*;

import com.google.android.gms.maps.model.*;



import java.util.Calendar;



public class MainActivity extends AppCompatActivity implements OnMapReadyCallback, SensorEventListener {



// Map & Location

    private GoogleMap mMap;

    private FusedLocationProviderClient fusedLocationClient;

    private Circle currentCircle;

    private String currentZoneType = "Green";



// Sensors & Detectors

    private SensorManager sensorManager;

    private MediaRecorder recorder;

    private boolean isRunning = false;

    private boolean isScreaming = false;

    private boolean isNight = false;



// UI Elements

    private TextView dangerScoreText, detectorDetails, countdownText;

    private View sosOverlay;

    private CountDownTimer sosTimer;



    @Override

    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);



// Initialize UI

        dangerScoreText = findViewById(R.id.dangerScoreText);

        detectorDetails = findViewById(R.id.detectorDetails);

        sosOverlay = findViewById(R.id.sosOverlay);

        countdownText = findViewById(R.id.countdownText);



        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);



// Map Setup

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map);

        mapFragment.getMapAsync(this);



        setupButtons();

        startScreamDetection();

    }



    private void setupButtons() {

        findViewById(R.id.btnRed).setOnClickListener(v -> currentZoneType = "Red");

        findViewById(R.id.btnYellow).setOnClickListener(v -> currentZoneType = "Yellow");

        findViewById(R.id.btnGreen).setOnClickListener(v -> currentZoneType = "Green");

        findViewById(R.id.btnCancelSOS).setOnClickListener(v -> cancelSOS());

    }



// --- SENSOR LOGIC ---



    @Override

    public void onSensorChanged(SensorEvent event) {

        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {

            double magnitude = Math.sqrt(event.values[0]*event.values[0] + event.values[1]*event.values[1] + event.values[2]*event.values[2]);

// Simple logic: if acceleration is high, they are likely running

            isRunning = magnitude > 15;

        }

    }



    private void startScreamDetection() {

// In a real app, use a background thread to check recorder.getMaxAmplitude()

// For demo: we'll simulate a scream trigger or use a simple handler

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {

            @Override

            public void run() {

                checkTime();

                calculateDangerScore();

                new Handler(Looper.getMainLooper()).postDelayed(this, 2000);

            }

        }, 2000);

    }



    private void checkTime() {

        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);

        isNight = (hour >= 20 || hour <= 5); // 8 PM to 5 AM

    }



// --- DANGER CALCULATION ---



    private void calculateDangerScore() {

        int score = 0;



        if (currentZoneType.equals("Red")) score += 40;

        else if (currentZoneType.equals("Yellow")) score += 20;



        if (isNight) score += 20;

        if (isScreaming) score += 30;

        if (isRunning) score += 30;





        dangerScoreText.setText("Danger Score: " + score + "%");

        detectorDetails.setText(String.format("Running: %b | Zone: %s | Night: %b | Scream: %b",

                isRunning, currentZoneType, isNight, isScreaming));



        if (score >= 70 && sosOverlay.getVisibility() == View.GONE) {

            triggerSOS();

        }

    }



    private void triggerSOS() {

        sosOverlay.setVisibility(View.VISIBLE);

        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        v.vibrate(1000);



        sosTimer = new CountDownTimer(10000, 1000) {

            @Override

            public void onTick(long millisUntilFinished) {

                countdownText.setText("" + (millisUntilFinished / 1000));

            }



            @Override

            public void onFinish() {

                sendSOSMessage();

                sosOverlay.setVisibility(View.GONE);

            }

        }.start();

    }



    private void sendSOSMessage() {

        String phoneNo = "8692854124"; // Hardcoded

        String message = "EMERGENCY! I am in danger. My location: http://maps.google.com/maps?q="

                + mMap.getMyLocation().getLatitude() + "," + mMap.getMyLocation().getLongitude();



        try {

            SmsManager smsManager = SmsManager.getDefault();

            smsManager.sendTextMessage(phoneNo, null, message, null, null);

            Toast.makeText(this, "SOS Sent!", Toast.LENGTH_LONG).show();

        } catch (Exception e) {

            Toast.makeText(this, "SMS Failed", Toast.LENGTH_SHORT).show();

        }

    }



    private void cancelSOS() {

        if (sosTimer != null) sosTimer.cancel();

        sosOverlay.setVisibility(View.GONE);

        Toast.makeText(this, "SOS Cancelled", Toast.LENGTH_SHORT).show();

    }



// --- MAP LOGIC ---



    @Override

    public void onMapReady(@NonNull GoogleMap googleMap) {

        mMap = googleMap;

        enableLocation();



        sensorManager.registerListener(this,

                sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),

                SensorManager.SENSOR_DELAY_NORMAL);

    }



    private void enableLocation() {

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {

            mMap.setMyLocationEnabled(true);

            fusedLocationClient.requestLocationUpdates(

                    new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000).build(),

                    new LocationCallback() {

                        @Override

                        public void onLocationResult(@NonNull LocationResult result) {

                            Location loc = result.getLastLocation();

                            if (loc != null) {

                                LatLng latLng = new LatLng(loc.getLatitude(), loc.getLongitude());

                                updateZoneCircle(latLng);

                                if (isFirstFix) {

                                    mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 17f));

                                    isFirstFix = false;

                                }

                            }

                        }

                    }, Looper.getMainLooper());

        } else {

            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.SEND_SMS}, 101);

        }

    }



    private void updateZoneCircle(LatLng center) {

        if (currentCircle != null) currentCircle.remove();



        int color;

        if (currentZoneType.equals("Red")) color = 0x55FF0000;

        else if (currentZoneType.equals("Yellow")) color = 0x55FFFF00;

        else color = 0x5500FF00;



        currentCircle = mMap.addCircle(new CircleOptions()

                .center(center)

                .radius(100)

                .fillColor(color)

                .strokeWidth(0));

    }



    private boolean isFirstFix = true;

    @Override public void onAccuracyChanged(Sensor sensor, int i) {}

}

