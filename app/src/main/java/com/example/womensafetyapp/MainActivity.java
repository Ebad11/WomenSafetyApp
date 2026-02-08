package com.example.womensafetyapp;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;

public class MainActivity extends AppCompatActivity implements OnMapReadyCallback {

    private GoogleMap mMap;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;

    private boolean isFirstFix = true;
    private static final int PERMISSION_CODE = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        checkPermissionsAndStart();
    }

    private void checkPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            mMap.setMyLocationEnabled(true);
            requestLiveUpdates();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_CODE);
        }
    }

    private void requestLiveUpdates() {
        // ✅ The NEW way to build LocationRequest (prevents deprecation warnings)
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                .setMinUpdateIntervalMillis(500) // The fastest the app can handle updates
                .setWaitForAccurateLocation(false)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                for (Location location : locationResult.getLocations()) {
                    if (location != null) {
                        updateUIWithLocation(location);
                    }
                }
            }
        };

        // Permission check required by Android Studio
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
        }
    }

    private void updateUIWithLocation(Location location) {
        LatLng currentPos = new LatLng(location.getLatitude(), location.getLongitude());

        if (isFirstFix) {
            // Instant move and high zoom for the very first time
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(currentPos, 17f));
            drawSafetyZones(currentPos);
            isFirstFix = false;
        } else {
            // ✅ Smoothly glide the camera instead of snapping
            mMap.animateCamera(CameraUpdateFactory.newLatLng(currentPos));
        }
    }

    private void drawSafetyZones(LatLng center) {
        // Red - High Risk
        mMap.addCircle(new CircleOptions()
                .center(new LatLng(center.latitude + 0.0005, center.longitude + 0.0005))
                .radius(70)
                .fillColor(0x44FF0000) // Transparent Red
                .strokeColor(0xFFFF0000)
                .strokeWidth(2));

        // Add other zones here...
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Stop tracking when app is in background to save battery
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Resume tracking when user returns
        if (!isFirstFix) requestLiveUpdates();
    }
}