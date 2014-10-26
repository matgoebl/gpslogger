/*******************************************************************************
 * This file is part of GPSLogger for Android.
 *
 * GPSLogger for Android is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * GPSLogger for Android is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with GPSLogger for Android.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/

package com.mendhak.gpslogger.settings;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Toast;

import com.mendhak.gpslogger.GpsLoggingService;
import com.mendhak.gpslogger.GpsMainActivity;
import com.mendhak.gpslogger.R;
import com.mendhak.gpslogger.common.FileDialog.FileDialog;
import com.mendhak.gpslogger.common.Utilities;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * A {@link android.preference.PreferenceActivity} that presents a set of application settings. On
 * handset devices, settings are presented as a single list. On tablets,
 * settings are split by category, with category headers shown to the left of
 * the list of settings.
 * <p/>
 * See <a href="http://developer.android.com/design/patterns/settings.html">
 * Android Design: Settings</a> for design guidelines and the <a
 * href="http://developer.android.com/guide/topics/ui/settings.html">Settings
 * API Guide</a> for more information on developing a Settings UI.
 */
@SuppressWarnings("deprecation")
public class LoggingSettingsActivity extends PreferenceActivity implements Preference.OnPreferenceClickListener, Preference.OnPreferenceChangeListener {

    private static final org.slf4j.Logger tracer = LoggerFactory.getLogger(LoggingSettingsActivity.class.getSimpleName());
    SharedPreferences prefs;
    private final static int SELECT_FOLDER_DIALOG = 420;
    String btleDevice;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getActionBar().setDisplayHomeAsUpEnabled(true);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        if (prefs.getString("new_file_creation", "onceaday").equals("static")) {
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString("new_file_creation", "custom");
            editor.commit();

            ListPreference newFileCreation = (ListPreference) findPreference("new_file_creation");
            if(newFileCreation !=null){
                newFileCreation.setValue("custom");
            }

        }
    }


    @Override
    protected void onResume() {
        super.onResume();

        setPreferencesEnabledDisabled();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            // Respond to the action bar's Up/Home button
            case android.R.id.home:
                Intent intent = new Intent(this, GpsMainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);

                break;
        }
        return super.onOptionsItemSelected(item);
    }


    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        addPreferencesFromResource(R.xml.pref_logging);


        Preference gpsloggerFolder = (Preference) findPreference("gpslogger_folder");
        gpsloggerFolder.setOnPreferenceClickListener(this);
        gpsloggerFolder.setSummary(prefs.getString("gpslogger_folder", Environment.getExternalStorageDirectory() + "/GPSLogger"));

        CheckBoxPreference chkLog_opengts = (CheckBoxPreference) findPreference("log_opengts");
        chkLog_opengts.setOnPreferenceClickListener(this);

        Preference btleActivate = (Preference) findPreference("btle_activate");
        btleActivate.setOnPreferenceChangeListener(this);

        Preference btleScan = (Preference) findPreference("btle_scan");
        btleScan.setOnPreferenceClickListener(this);
        btleDevice = prefs.getString("btle_device", "no device yet");
        btleScan.setSummary(btleDevice);

        /**
         * Logging Details - New file creation
         */
        ListPreference newFilePref = (ListPreference) findPreference("new_file_creation");
        newFilePref.setOnPreferenceChangeListener(this);
        /* Trigger artificially the listener and perform validations. */
        newFilePref.getOnPreferenceChangeListener()
                .onPreferenceChange(newFilePref, newFilePref.getValue());

        CheckBoxPreference chkfile_prefix_serial = (CheckBoxPreference) findPreference("new_file_prefix_serial");
        if (Utilities.IsNullOrEmpty(Utilities.GetBuildSerial())) {
            chkfile_prefix_serial.setEnabled(false);
            chkfile_prefix_serial.setSummary("This option not available on older phones or if a serial id is not present");
        } else {
            chkfile_prefix_serial.setSummary(chkfile_prefix_serial.getSummary().toString() + "(" + Utilities.GetBuildSerial() + ")");
        }


    }

    @Override
    public boolean onPreferenceClick(Preference preference) {

        if (preference.getKey().equals("gpslogger_folder")) {
            Intent intent = new Intent(getBaseContext(), FileDialog.class);
            intent.putExtra(FileDialog.START_PATH, prefs.getString("gpslogger_folder",
                    Environment.getExternalStorageDirectory() + "/GPSLogger"));

            intent.putExtra(FileDialog.CAN_SELECT_DIR, true);
            startActivityForResult(intent, SELECT_FOLDER_DIALOG);
            return true;
        }

        if (preference.getKey().equals("log_opengts")) {
            CheckBoxPreference chkLog_opengts = (CheckBoxPreference) findPreference("log_opengts");

            if (chkLog_opengts.isChecked()) {
                startActivity(new Intent("com.mendhak.gpslogger.OPENGTS_SETUP"));
            }
            return true;
        }

        if (preference.getKey().equals("btle_scan")) {
            final Preference btleScan = (Preference) findPreference("btle_scan");
            btleScan.setSummary("Scanning...");
            btleDevice = "no device found";

            final BluetoothManager bluetoothManager;
            bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
            final BluetoothAdapter mBluetoothAdapter;
            mBluetoothAdapter = bluetoothManager.getAdapter();
            if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
                btleDevice="bluetooth is disabled";
            } else {
                final UUID CSC_SERVICE_UUID = UUID.fromString("00001816-0000-1000-8000-00805f9b34fb");
                UUID[] serviceUUIDs = new UUID[]{CSC_SERVICE_UUID};
                boolean mScanning;
                final long SCAN_PERIOD = 30000;
                final Handler handler = new Handler();
                final BluetoothAdapter.LeScanCallback mLeScanCallback =
                        new BluetoothAdapter.LeScanCallback() {
                            @Override
                            public void onLeScan(final BluetoothDevice device, final int rssi,
                                                 final byte[] scanRecord) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        //mBluetoothAdapter.stopLeScan(mLeScanCallback);
                                        //handler.removeCallbacksAndMessages(null);

                                        btleDevice = device.getAddress();
                                        String deviceInfo = String.format("%s %s (%d db)", device.getName(), btleDevice, rssi);
                                        //btleScan.setSummary(deviceInfo);

                                        Toast.makeText(getApplicationContext(), "Found " + deviceInfo, Toast.LENGTH_LONG).show();
                                        String dump = "";
                                        for (byte b : scanRecord)
                                            dump += String.format("%02x ", b);
                                        Log.d("BTLE_scanRecord",deviceInfo + " payload:" + dump);
                                    }
                                });
                            }
                        };

                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mBluetoothAdapter.stopLeScan(mLeScanCallback);
                        btleScan.setSummary(btleDevice);
                        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
                        SharedPreferences.Editor editor = prefs.edit();
                        editor.putString("btle_device", btleDevice);
                        editor.apply();
                    }
                }, SCAN_PERIOD);
                mBluetoothAdapter.startLeScan(serviceUUIDs, mLeScanCallback);
            }
            return true;
        }
        return false;
    }

    public synchronized void onActivityResult(final int requestCode, int resultCode, final Intent data) {

        if (requestCode == SELECT_FOLDER_DIALOG) {
            if (resultCode == Activity.RESULT_OK) {
                String filePath = data.getStringExtra(FileDialog.RESULT_PATH);
                tracer.debug("Folder path selected" + filePath);

                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString("gpslogger_folder", filePath);
                editor.commit();

                Preference gpsloggerFolder = (Preference) findPreference("gpslogger_folder");
                gpsloggerFolder.setSummary(filePath);

            } else if (resultCode == Activity.RESULT_CANCELED) {
                tracer.debug("No file selected");
            }
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {

        if (preference.getKey().equals("new_file_creation")) {

            Preference prefFileStaticName = (Preference) findPreference("new_file_custom_name");
            Preference prefSerialPrefix = (Preference) findPreference("new_file_prefix_serial");
            prefFileStaticName.setEnabled(newValue.equals("custom"));
            prefSerialPrefix.setEnabled(!newValue.equals("custom"));


            return true;
        }

        if (preference.getKey().equals("btle_activate")) {
            Intent serviceIntent = new Intent(getApplicationContext(), GpsLoggingService.class);
            if(newValue.equals(true)) {
                tracer.info("Starting BTLE on preference change");
                serviceIntent.putExtra("startbtle", true);
            } else {
                tracer.info("Stopping BTLE on preference change");
                serviceIntent.putExtra("stopbtle", true);
            }
            getApplicationContext().startService(serviceIntent);
            return true;
        }
        return false;
    }

    private void setPreferencesEnabledDisabled() {

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        Preference prefFileStaticName = (Preference) findPreference("new_file_custom_name");
        Preference prefSerialPrefix = (Preference) findPreference("new_file_prefix_serial");

        prefFileStaticName.setEnabled(prefs.getString("new_file_creation", "onceaday").equals("custom"));
        prefSerialPrefix.setEnabled(!prefs.getString("new_file_creation", "onceaday").equals("custom"));
    }
}
