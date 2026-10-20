package com.limelight.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.input.InputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.util.AttributeSet;
import android.view.InputDevice;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;

// CLUE: launcher status area (controller, clock, Wi-Fi, battery). The clock is a TextClock;
// everything else listens to system callbacks while attached, so nothing polls.
public class LauncherStatusView extends LinearLayout implements InputManager.InputDeviceListener {
    private ImageView gamepadView;
    private ImageView wifiView;
    private BatteryIconView batteryView;
    private TextView batteryText;

    private ConnectivityManager.NetworkCallback networkCallback;

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateBattery(intent);
        }
    };

    public LauncherStatusView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        gamepadView = findViewById(R.id.status_gamepad);
        wifiView = findViewById(R.id.status_wifi);
        batteryView = findViewById(R.id.status_battery);
        batteryText = findViewById(R.id.status_battery_text);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (isInEditMode()) {
            return;
        }

        Context context = getContext();

        updateBattery(context.registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));

        InputManager inputManager = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
        inputManager.registerInputDeviceListener(this, null);
        updateGamepad();

        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    final boolean wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                    post(new Runnable() {
                        @Override
                        public void run() {
                            setWifi(wifi);
                        }
                    });
                }

                @Override
                public void onLost(Network network) {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            setWifi(false);
                        }
                    });
                }
            };
            setWifi(false);
            cm.registerDefaultNetworkCallback(networkCallback);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (isInEditMode()) {
            return;
        }

        Context context = getContext();
        try {
            context.unregisterReceiver(batteryReceiver);
        } catch (IllegalArgumentException ignored) {}

        ((InputManager) context.getSystemService(Context.INPUT_SERVICE)).unregisterInputDeviceListener(this);

        if (networkCallback != null) {
            try {
                ((ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE))
                        .unregisterNetworkCallback(networkCallback);
            } catch (IllegalArgumentException ignored) {}
            networkCallback = null;
        }
    }

    private void setWifi(boolean wifi) {
        wifiView.setImageResource(wifi ? R.drawable.ic_launcher_wifi : R.drawable.ic_launcher_wifi_off);
        wifiView.setAlpha(wifi ? 1.0f : 0.5f);
    }

    private void updateBattery(Intent intent) {
        if (intent == null) {
            batteryView.setVisibility(GONE);
            batteryText.setVisibility(GONE);
            return;
        }

        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        boolean present = intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true);
        if (!present || level < 0 || scale <= 0) {
            // TV boxes and the like
            batteryView.setVisibility(GONE);
            batteryText.setVisibility(GONE);
            return;
        }

        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
        int percent = Math.round(level * 100f / scale);

        batteryView.setVisibility(VISIBLE);
        batteryText.setVisibility(VISIBLE);
        batteryView.setState(percent, charging);
        batteryText.setText(percent + "%");
    }

    private void updateGamepad() {
        boolean connected = false;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice dev = InputDevice.getDevice(id);
            if (dev == null || dev.isVirtual()) {
                continue;
            }
            int sources = dev.getSources();
            if ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                    (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                connected = true;
                break;
            }
        }
        gamepadView.setAlpha(connected ? 1.0f : 0.35f);
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        updateGamepad();
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        updateGamepad();
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        updateGamepad();
    }
}
