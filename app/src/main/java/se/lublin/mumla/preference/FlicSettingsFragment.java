package se.lublin.mumla.preference;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import io.flic.flic2libandroid.Flic2Button;
import se.lublin.mumla.app.MumlaApplication;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;
import se.lublin.mumla.flic.FlicPttController;

/**
 * Settings fragment for Flic 2/Duo push-to-talk controller.
 * Allows users to select their active Flic device and configure Duo-specific settings.
 */
public class FlicSettingsFragment extends MumlaPreferenceFragment
        implements Preference.OnPreferenceChangeListener {

    private FlicPttController mFlicController;
    private ListPreference mActiveDevicePreference;
    private ListPreference mDuoInputPreference;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.settings_flic, rootKey);
        // Get Flic controller
        mFlicController = ((MumlaApplication) requireActivity().getApplication()).getFlicController();

        // Initialize preferences
        mActiveDevicePreference = findPreference(Settings.PREF_FLIC_ACTIVE_DEVICE);
        mDuoInputPreference = findPreference(Settings.PREF_FLIC_DUO_INPUT);

        if (mActiveDevicePreference != null) {
            mActiveDevicePreference.setOnPreferenceChangeListener(this);
        }

        if (mDuoInputPreference != null) {
            mDuoInputPreference.setOnPreferenceChangeListener(this);
        }

        // Set up unpair preferenceListpreference
        Preference unpairPreference = findPreference("flic_unpair_button");
        if (unpairPreference != null) {
            unpairPreference.setOnPreferenceClickListener(preference -> {
                showUnpairDialog();
                return true;
            });
        }

        // Update UI statepersistas
        updateDeviceDependentPreferences(mFlicController.getActiveDevice());
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        String key = preference.getKey();
        if (key.equals(Settings.PREF_FLIC_ACTIVE_DEVICE)) {
            int device = Integer.parseInt(newValue.toString());
            mFlicController.setActiveDevice(device);
            // Settings.getInstance(requireContext()).setFlicActiveDevice(device);
            updateDeviceDependentPreferences(device);
            updateActiveDeviceSummary(device);
            return true;
        } else if (key.equals(Settings.PREF_FLIC_DUO_INPUT)) {
            int input = Integer.parseInt(newValue.toString());
            mFlicController.setDuoInput(input);
//            Settings.getInstance(requireContext()).setFlicDuoInput(input);
            return true;
        }
        return false;
    }

    private void updateDeviceDependentPreferences(int activeDevice) {
        boolean isFlic2 = activeDevice == FlicPttController.ACTIVE_DEVICE_FIC_2;
        boolean isFlicDuo = activeDevice == FlicPttController.ACTIVE_DEVICE_FIC_DUO;
        boolean hasDevice = isFlic2 || isFlicDuo;

        // Update Duo input preference visibility
        if (mDuoInputPreference != null) {
            mDuoInputPreference.setVisible(isFlicDuo);
        }

        // Update unpair preference visibility
        Preference unpairPreference = findPreference("flic_unpair_button");
        if (unpairPreference != null) {
            unpairPreference.setVisible(hasDevice);
        }
    }

    private void updateActiveDeviceSummary(int activeDevice) {
        if (mActiveDevicePreference != null) {
            String activeDeviceName = getActiveDeviceName(activeDevice);
            mActiveDevicePreference.setSummary(
                    getString(R.string.flic_active_device_summary, activeDeviceName)
            );
        }
    }

    private String getActiveDeviceName(int activeDevice) {
        switch (activeDevice) {
            case FlicPttController.ACTIVE_DEVICE_FIC_2:
                return getString(R.string.flic_device_flic2);
            case FlicPttController.ACTIVE_DEVICE_FIC_DUO:
                return getString(R.string.flic_device_flicduo);
            default:
                return getString(R.string.flic_device_none);
        }
    }

    private void showUnpairDialog() {
        int activeDevice = mFlicController.getActiveDevice();

        // Get paired buttons from FlicController
        java.util.Map<String, Flic2Button> pairedButtons = mFlicController.getPairedButtons();

        if (activeDevice == FlicPttController.ACTIVE_DEVICE_FIC_2) {
            // Find first Flic 2 button
            for (Flic2Button b : pairedButtons.values()) {
                // Flic 2 is the classic round button, Duo has two buttons
                // We can identify by checking if this is a Flic 2 device
                // For simplicity, just use the first one if only one paired
                showUnpairConfirmDialog(b);
                return;
            }
        } else if (activeDevice == FlicPttController.ACTIVE_DEVICE_FIC_DUO) {
            // Find first Flic Duo button
            for (Flic2Button b : pairedButtons.values()) {
                showUnpairConfirmDialog(b);
                return;
            }
        }

        if (pairedButtons.isEmpty()) {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.flic_unpair_no_device)
                    .setMessage(R.string.flic_unpair_no_device_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
    }

    private void showUnpairConfirmDialog(final Flic2Button button) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.flic_unpair_confirm_title)
                .setMessage(getString(R.string.flic_unpair_confirm_message, button.getBdAddr()))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    mFlicController.onUnpairButton(button);
                    mFlicController.clearButton(button.getBdAddr());
                    // TODO: Call the FlicManager forget method when available
                    // flicManager.forgetButton(button);
                    updateDeviceDependentPreferences(mFlicController.getActiveDevice());
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
