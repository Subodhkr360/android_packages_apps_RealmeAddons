/*
 * Copyright (C) 2025 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lineageos.settings.device.charginganimation;

import android.app.KeyguardManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.preference.PreferenceManager;

/**
 * Service to monitor charging state and show/hide the charging animation overlay.
 * Animation is shown only on lockscreen when device is charging.
 */
public class ChargingMonitorService extends Service {

    private static final String TAG = "ChargingMonitorService";
    private static final String PREF_ENABLED = "charging_animation_enabled";

    private static final long RESHOW_DELAY_MS = 8000; // 8 seconds delay before re-showing

    private SharedPreferences mPrefs;
    private Handler mHandler;
    private KeyguardManager mKeyguardManager;
    private PowerManager mPowerManager;
    private boolean mIsCharging = false;
    private boolean mIsScreenOn = false;
    private boolean mTouchHidden = false; // Track if hidden due to touch
    private Runnable mReshowRunnable;

    private final BroadcastReceiver mPowerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            switch (action) {
                case Intent.ACTION_POWER_CONNECTED:
                    Log.d(TAG, "Power connected");
                    mIsCharging = true;
                    updateOverlayState();
                    break;

                case Intent.ACTION_POWER_DISCONNECTED:
                    Log.d(TAG, "Power disconnected");
                    mIsCharging = false;
                    hideOverlay();
                    break;

                case Intent.ACTION_BATTERY_CHANGED:
                    handleBatteryChanged(intent);
                    break;

                case Intent.ACTION_SCREEN_ON:
                    Log.d(TAG, "Screen on");
                    mIsScreenOn = true;
                    updateOverlayState();
                    break;

                case Intent.ACTION_SCREEN_OFF:
                    Log.d(TAG, "Screen off");
                    mIsScreenOn = false;
                    hideOverlay();
                    break;

                case Intent.ACTION_USER_PRESENT:
                    Log.d(TAG, "User present (unlocked)");
                    updateOverlayState();
                    break;
            }
        }
    };

    private final SharedPreferences.OnSharedPreferenceChangeListener mPrefListener =
            (prefs, key) -> {
                if (PREF_ENABLED.equals(key)) {
                    boolean enabled = prefs.getBoolean(PREF_ENABLED, false);
                    Log.d(TAG, "Preference changed: enabled=" + enabled);
                    if (enabled) {
                        updateOverlayState();
                    } else {
                        hideOverlay();
                    }
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");

        mHandler = new Handler(Looper.getMainLooper());
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mPrefs.registerOnSharedPreferenceChangeListener(mPrefListener);
        mKeyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        mPowerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);

        // Register power state and screen receiver
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(mPowerReceiver, filter);

        // Check initial state
        checkInitialState();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(mPowerReceiver);
        } catch (IllegalArgumentException e) {
            // Receiver not registered
        }
        // Clean up pending callbacks
        if (mReshowRunnable != null) {
            mHandler.removeCallbacks(mReshowRunnable);
            mReshowRunnable = null;
        }
        mPrefs.unregisterOnSharedPreferenceChangeListener(mPrefListener);
        Log.d(TAG, "Service destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void checkInitialState() {
        // Check screen state
        mIsScreenOn = mPowerManager != null && mPowerManager.isInteractive();

        // Check charging state
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = registerReceiver(null, filter);
        if (batteryStatus != null) {
            int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            mIsCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL;
        }

        Log.d(TAG, "Initial state - charging: " + mIsCharging + ", screenOn: " + mIsScreenOn);
        updateOverlayState();
    }

    private boolean isDeviceLocked() {
        return mKeyguardManager != null && mKeyguardManager.isKeyguardLocked();
    }

    private void updateOverlayState() {
        if (!isEnabled()) {
            Log.d(TAG, "Charging animation disabled");
            hideOverlay();
            return;
        }

        if (!mIsCharging) {
            Log.d(TAG, "Not charging, hiding overlay");
            hideOverlay();
            return;
        }

        boolean locked = isDeviceLocked();
        Log.d(TAG, "Updating overlay state - screenOn: " + mIsScreenOn + ", locked: " + locked);

        // Show only when screen is on AND device is locked (on lockscreen)
        if (mIsScreenOn && locked) {
            showOverlay();
        } else {
            hideOverlay();
        }
    }

    private void showOverlay() {
        mHandler.post(() -> {
            ChargingAnimationOverlay overlay = ChargingAnimationOverlay.getInstance(this);

            // Set up touch listener to handle temporary hide for PIN entry
            overlay.setOnTouchListener(() -> {
                Log.d(TAG, "Overlay touched, hiding temporarily for PIN entry");
                mTouchHidden = true;
                overlay.hide();  // Hide the overlay immediately

                // Cancel any pending re-show
                if (mReshowRunnable != null) {
                    mHandler.removeCallbacks(mReshowRunnable);
                }

                // Schedule re-show after delay (if still on lockscreen and charging)
                mReshowRunnable = () -> {
                    if (mIsCharging && mIsScreenOn && isDeviceLocked()) {
                        Log.d(TAG, "Re-showing overlay after touch delay");
                        mTouchHidden = false;
                        showOverlay();
                    } else {
                        mTouchHidden = false;
                    }
                };
                mHandler.postDelayed(mReshowRunnable, RESHOW_DELAY_MS);
            });

            if (!overlay.isShowing() && !mTouchHidden) {
                overlay.show();
                // Update battery level
                int level = getBatteryLevel();
                overlay.updateBatteryLevel(level);
            }
        });
    }

    private void hideOverlay() {
        mHandler.post(() -> {
            // Cancel any pending re-show when explicitly hiding
            if (mReshowRunnable != null) {
                mHandler.removeCallbacks(mReshowRunnable);
                mReshowRunnable = null;
            }
            mTouchHidden = false;

            ChargingAnimationOverlay overlay = ChargingAnimationOverlay.getInstance(this);
            overlay.hide();
        });
    }

    private void handleBatteryChanged(Intent intent) {
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean wasCharging = mIsCharging;
        mIsCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL;

        if (wasCharging != mIsCharging) {
            updateOverlayState();
        }

        if (!mIsCharging) return;

        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level >= 0 && scale > 0) {
            int batteryLevel = (level * 100) / scale;
            mHandler.post(() -> {
                ChargingAnimationOverlay overlay = ChargingAnimationOverlay.getInstance(this);
                if (overlay.isShowing()) {
                    overlay.updateBatteryLevel(batteryLevel);
                }
            });
        }
    }

    private int getBatteryLevel() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = registerReceiver(null, filter);
        if (batteryStatus != null) {
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (level >= 0 && scale > 0) {
                return (level * 100) / scale;
            }
        }
        return 0;
    }

    private boolean isEnabled() {
        return mPrefs.getBoolean(PREF_ENABLED, false);
    }
}
