/*
 * Copyright (C) 2014 Andrew Comminos
 * Copyright (C) 2024 Mumla Fork contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.flic;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

import io.flic.flic2libandroid.Flic2Button;
import io.flic.flic2libandroid.Flic2ButtonListener;
import io.flic.flic2libandroid.Flic2Manager;

/**
 * Controller for Flic 2 and Flic Duo buttons to provide push-to-talk functionality.
 * Only one device is active at a time, selected by user preference.
 * Flic Duo inputs are also selected by user preference.
 */
public class FlicPttController {
    private static final String TAG = "FlicPttController";

    public static final String PREF_ACTIVE_DEVICE = "flic_active_device";
    public static final String PREF_DUO_INPUT = "flic_duo_input";

    public static final int ACTIVE_DEVICE_NONE = 0;
    public static final int ACTIVE_DEVICE_FIC_2 = 1;
    public static final int ACTIVE_DEVICE_FIC_DUO = 2;

    public static final int DUO_INPUT_LEFT = 0;
    public static final int DUO_INPUT_RIGHT = 1;

    private final Flic2Manager mManager;
    private final Handler mMainHandler;
    private final SharedPreferences mPreferences;

    private final Map<String, Flic2Button> mPairedButtons = new HashMap<>();
    private @Nullable Flic2Button mFlic2Button;
    private @Nullable Flic2Button mFlicDuoButton;
    private int mActiveDevice = ACTIVE_DEVICE_NONE;
    private int mDuoInput = DUO_INPUT_LEFT;

    private final Flic2ButtonListener mListener = new Flic2ButtonListener() {
        @Override
        public void onButtonUpOrDown(Flic2Button button, boolean wasQueued, boolean lastQueued, long timestamp, boolean isUp, boolean isDown) {
            // Drop stale events
            if (wasQueued && button.getReadyTimestamp() - timestamp > 1000) {
                Log.d(TAG, "Dropping stale event from " + button.getBdAddr());
                return;
            }

            handleButtonEvent(button, timestamp, isUp, isDown);
        }

        @Override
        public void onButtonClickOrHold(Flic2Button button, boolean wasQueued, boolean lastQueued, long timestamp, boolean isClick, boolean isHold) {
            // Drop stale events
            if (wasQueued && button.getReadyTimestamp() - timestamp > 1000) {
                Log.d(TAG, "Dropping stale event from " + button.getBdAddr());
                return;
            }

            if (isClick) {
                handleButtonEvent(button, timestamp, true, false);
            } else if (isHold) {
                handleButtonEvent(button, timestamp, false, true);
            }
        }

        @Override
        public void onButtonSingleOrDoubleClick(Flic2Button button, boolean wasQueued, boolean lastQueued, long timestamp, boolean isSingle, boolean isDouble) {
            // Drop stale events
            if (wasQueued && button.getReadyTimestamp() - timestamp > 1000) {
                Log.d(TAG, "Dropping stale event from " + button.getBdAddr());
                return;
            }

            if (isSingle) {
                handleButtonEvent(button, timestamp, true, false);
            }
        }

        @Override
        public void onButtonSingleOrDoubleClickOrHold(Flic2Button button, boolean wasQueued, boolean lastQueued, long timestamp, boolean isSingle, boolean isDouble, boolean isHold) {
            // Drop stale events
            if (wasQueued && button.getReadyTimestamp() - timestamp > 1000) {
                Log.d(TAG, "Dropping stale event from " + button.getBdAddr());
                return;
            }

            if (isDouble) {
                handleButtonEvent(button, timestamp, true, false);
            } else if (isHold) {
                handleButtonEvent(button, timestamp, false, true);
            }
        }

        @Override
        public void onButtonEvent(Flic2Button button, io.flic.flic2libandroid.Flic2ButtonEvent event) {
            // Drop stale events
            if (button.getReadyTimestamp() - event.getTimestamp() > 1000) {
                Log.d(TAG, "Dropping stale event from " + button.getBdAddr());
                return;
            }

            // Determine if this is an up or down event based on the click type
            int clickType =  event.getEventType();
            boolean isUp = clickType == 1; // single click / release
            boolean isDown = clickType == 2; // hold / press

            handleButtonEvent(button, event.getTimestamp(), isUp, isDown);
        }

        @Override
        public void onReady(Flic2Button button, long timestamp) {
            Log.d(TAG, "Button ready: " + button.getBdAddr());
        }

        @Override
        public void onUnpaired(Flic2Button button) {
            Log.d(TAG, "Button unpaired: " + button.getBdAddr());
            handleUnpair(button);
        }

        @Override
        public void onDisconnect(Flic2Button button) {
            Log.d(TAG, "Button disconnected: " + button.getBdAddr());
            handleDisconnect(button);
        }
    };

    public FlicPttController(Flic2Manager manager, SharedPreferences preferences) {
        mManager = manager;
        mPreferences = preferences;
        mMainHandler = new Handler(Looper.getMainLooper());

        // Load saved preferences

        mActiveDevice = readIntPref(PREF_ACTIVE_DEVICE, ACTIVE_DEVICE_NONE);
        mDuoInput = readIntPref(PREF_DUO_INPUT, DUO_INPUT_LEFT);
    }

    /** Reads a value stored either as a String (ListPreference) or a legacy Integer. */
    private int readIntPref(String key, int def) {
        Object v = mPreferences.getAll().get(key);
        if (v instanceof Integer) {
            // Migrate legacy int to string so ListPreference can persist over it
            mPreferences.edit().putString(key, String.valueOf(v)).apply();
            return (Integer) v;
        }
        if (v instanceof String) {
            try {
                return Integer.parseInt((String) v);
            } catch (NumberFormatException ignored) { }
        }
        return def;
    }

    /**
     * Initialize the controller - connect to already paired buttons.
     */
    @MainThread
    public void initialize() {
        Log.d(TAG, "Initializing Flic controller");
        for (Flic2Button button : mManager.getButtons()) {
            String bdAddr = button.getBdAddr();
            mPairedButtons.put(bdAddr, button);
            button.addListener(mListener);
            button.connect();

            // Set button reference if not already set
            // We identify Flic 2 vs Duo by checking which one was set via preferences
            // For now, just set the first button we find for each type
            if (mFlic2Button == null) {
                mFlic2Button = button;
            }
            if (mFlicDuoButton == null) {
                mFlicDuoButton = button;
            }
        }
        connectActiveDevice();
    }

    /**
     * Connect the active device. Call this when the service starts.
     */
    @MainThread
    public void connectActiveDevice() {
        Log.d(TAG, "Connecting active device: " + mActiveDevice);
        switch (mActiveDevice) {
            case ACTIVE_DEVICE_FIC_2:
                if (mFlic2Button != null) {
                    mFlic2Button.connect();
                }
                break;
            case ACTIVE_DEVICE_FIC_DUO:
                if (mFlicDuoButton != null) {
                    mFlicDuoButton.connect();
                }
                break;
        }
    }

    /**
     * Disconnect all devices. Call this when the service stops.
     */
    @MainThread
    public void disconnectAll() {
        Log.d(TAG, "Disconnecting all devices");
        for (Flic2Button button : mPairedButtons.values()) {
            button.disconnectOrAbortPendingConnection();
        }
    }

    /**
     * Handle button events and update PTT state.
     * Returns true if the event was processed and PTT state changed.
     */
    private void handleButtonEvent(Flic2Button button, long timestamp, boolean isUp, boolean isDown) {
        // Only process events from the active device
        String bdAddr = button.getBdAddr();
        if (!isButtonActive(bdAddr)) {
            Log.d(TAG, "Ignoring event from non-active device: " + bdAddr);
            return;
        }

        // For Flic Duo, check the input
        if (mActiveDevice == ACTIVE_DEVICE_FIC_DUO && !isInputActive(button)) {
            Log.d(TAG, "Ignoring event from non-active Duo input");
            return;
        }

        if (isDown) {
            handleButtonDown();
        } else if (isUp) {
            handleButtonUp();
        }
    }

    private boolean isButtonActive(String bdAddr) {
        if (mActiveDevice == ACTIVE_DEVICE_NONE) {
            return false;
        }
        if (mActiveDevice == ACTIVE_DEVICE_FIC_2 && mFlic2Button != null) {
            return mFlic2Button.getBdAddr().equals(bdAddr);
        }
        if (mActiveDevice == ACTIVE_DEVICE_FIC_DUO && mFlicDuoButton != null) {
            return mFlicDuoButton.getBdAddr().equals(bdAddr);
        }
        return false;
    }

    private boolean isInputActive(Flic2Button button) {
        // For now, treat both inputs the same. Can be refined when we have the SDK docs.
        // The clickType parameter can distinguish between left/right on Duo.
        return true;
    }

    private void handleButtonDown() {
        if (mServiceCallback != null && mServiceCallback.isPushToTalkMode()) {
            mServiceCallback.onTalkKeyDown();
        }
    }

    private void handleButtonUp() {
        if (mServiceCallback != null && mServiceCallback.isPushToTalkMode()) {
            mServiceCallback.onTalkKeyUp();
        }
    }

    private void handleDisconnect(Flic2Button button) {
        if (mServiceCallback != null && mServiceCallback.isPushToTalkMode()) {
            mServiceCallback.onPttRelease();
        }
    }

    private void handleUnpair(Flic2Button button) {
        String bdAddr = button.getBdAddr();
        mPairedButtons.remove(bdAddr);

        if (mActiveDevice == ACTIVE_DEVICE_FIC_2 && mFlic2Button != null && mFlic2Button.getBdAddr().equals(bdAddr)) {
            mFlic2Button = null;
            setActiveDevice(ACTIVE_DEVICE_NONE);
        } else if (mActiveDevice == ACTIVE_DEVICE_FIC_DUO && mFlicDuoButton != null && mFlicDuoButton.getBdAddr().equals(bdAddr)) {
            mFlicDuoButton = null;
            setActiveDevice(ACTIVE_DEVICE_NONE);
        }
    }

    /**
     * Callback interface for service integration.
     */
    public interface ServiceCallback {
        void onTalkKeyDown();
        void onTalkKeyUp();
        void onPttRelease();
        boolean isConnected();
        String getInputMethod();
        boolean isPushToTalkMode();
    }

    private @Nullable ServiceCallback mServiceCallback;

    public void setServiceCallback(ServiceCallback callback) {
        mServiceCallback = callback;
    }

    // Button management methods
    public void onButtonPaired(Flic2Button button) {
        String bdAddr = button.getBdAddr();
        mPairedButtons.put(bdAddr, button);
        button.addListener(mListener);
        // Button is already connected when paired
    }

    public void onUnpairButton(Flic2Button button) {
        button.disconnectOrAbortPendingConnection();
        // TODO: Call the SDK forget/unpair method when available
        // The Flic2Manager API should have a forgetButton() method
        // button.forgetButton() or mManager.forgetButton(button)
    }

    public void setActiveDevice(int device) {
        if (mActiveDevice == device) {
            return;
        }

        // Release PTT if switching away from an active device
        if (mActiveDevice != ACTIVE_DEVICE_NONE && mServiceCallback != null) {
            mServiceCallback.onPttRelease();
        }

        int oldDevice = mActiveDevice;
        mActiveDevice = device;
        mPreferences.edit().putString(PREF_ACTIVE_DEVICE, String.valueOf(device)).apply();
        // Connect/disconnect as needed
        switch (device) {
            case ACTIVE_DEVICE_FIC_2:
                if (mFlic2Button != null) {
                    mFlic2Button.connect();
                }
                if (oldDevice == ACTIVE_DEVICE_FIC_DUO && mFlicDuoButton != null) {
                    mFlicDuoButton.disconnectOrAbortPendingConnection();
                }
                break;
            case ACTIVE_DEVICE_FIC_DUO:
                if (mFlicDuoButton != null) {
                    mFlicDuoButton.connect();
                }
                if (oldDevice == ACTIVE_DEVICE_FIC_2 && mFlic2Button != null) {
                    mFlic2Button.disconnectOrAbortPendingConnection();
                }
                break;
            default:
                // Disconnect all
                if (mFlic2Button != null) {
                    mFlic2Button.disconnectOrAbortPendingConnection();
                }
                if (mFlicDuoButton != null) {
                    mFlicDuoButton.disconnectOrAbortPendingConnection();
                }
                break;
        }
    }

    public void setDuoInput(int input) {
        mDuoInput = input;
        mPreferences.edit().putString(PREF_DUO_INPUT, String.valueOf(input)).apply();
    }

    // Getters
    public int getActiveDevice() {
        return mActiveDevice;
    }

    public int getDuoInput() {
        return mDuoInput;
    }

    public Map<String, Flic2Button> getPairedButtons() {
        return new HashMap<>(mPairedButtons);
    }

    public boolean isFlic2Paired() {
        return mFlic2Button != null;
    }

    public boolean isFlicDuoPaired() {
        return mFlicDuoButton != null;
    }

    public void setFlic2Button(Flic2Button button) {
        mFlic2Button = button;
    }

    public void setFlicDuoButton(Flic2Button button) {
        mFlicDuoButton = button;
    }

    public void clearButton(String bdAddr) {
        mPairedButtons.remove(bdAddr);
        if (mFlic2Button != null && mFlic2Button.getBdAddr().equals(bdAddr)) {
            mFlic2Button = null;
        }
        if (mFlicDuoButton != null && mFlicDuoButton.getBdAddr().equals(bdAddr)) {
            mFlicDuoButton = null;
        }
    }
}
