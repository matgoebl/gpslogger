/*
*    This file is part of GPSLogger for Android.
*
*    GPSLogger for Android is free software: you can redistribute it and/or modify
*    it under the terms of the GNU General Public License as published by
*    the Free Software Foundation, either version 2 of the License, or
*    (at your option) any later version.
*
*    GPSLogger for Android is distributed in the hope that it will be useful,
*    but WITHOUT ANY WARRANTY; without even the implied warranty of
*    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
*    GNU General Public License for more details.
*
*    You should have received a copy of the GNU General Public License
*    along with GPSLogger for Android.  If not, see <http://www.gnu.org/licenses/>.
*/

//TODO: Simplify email logic (too many methods)
//TODO: Allow messages in IActionListener callback methods

package com.mendhak.gpslogger;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.TaskStackBuilder;
import android.util.Log;

import com.mendhak.gpslogger.common.AppSettings;
import com.mendhak.gpslogger.common.IActionListener;
import com.mendhak.gpslogger.common.Session;
import com.mendhak.gpslogger.common.Utilities;
import com.mendhak.gpslogger.loggers.FileLoggerFactory;
import com.mendhak.gpslogger.loggers.IFileLogger;
import com.mendhak.gpslogger.loggers.nmea.NmeaFileLogger;
import com.mendhak.gpslogger.senders.AlarmReceiver;
import com.mendhak.gpslogger.senders.FileSenderFactory;
import org.slf4j.LoggerFactory;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public class GpsLoggingService extends Service implements IActionListener {
    private static NotificationManager notificationManager;
    private static int NOTIFICATION_ID = 8675309;
    private static IGpsLoggerServiceClient mainServiceClient;
    private final IBinder binder = new GpsLoggingBinder();
    AlarmManager nextPointAlarmManager;
    private NotificationCompat.Builder nfc = null;
    private BluetoothManager bluetooth;

    private org.slf4j.Logger tracer;
    // ---------------------------------------------------
    // Helpers and managers
    // ---------------------------------------------------
    protected LocationManager gpsLocationManager;
    private LocationManager passiveLocationManager;
    private LocationManager towerLocationManager;
    private GeneralLocationListener gpsLocationListener;
    private GeneralLocationListener towerLocationListener;
    private GeneralLocationListener passiveLocationListener;
    private Intent alarmIntent;
    private Handler handler = new Handler();
    private long firstRetryTimeStamp;
    // ---------------------------------------------------

    /**
     * Sets the activity form for this service. The activity form needs to
     * implement IGpsLoggerServiceClient.
     *
     * @param mainForm The calling client
     */
    protected static void SetServiceClient(IGpsLoggerServiceClient mainForm) {
        mainServiceClient = mainForm;
    }

    @Override
    public IBinder onBind(Intent arg0) {
        tracer.debug(".");
        return binder;
    }

    @Override
    public void onCreate() {
        Utilities.ConfigureLogbackDirectly(getApplicationContext());
        tracer = LoggerFactory.getLogger(GpsLoggingService.class.getSimpleName());

        tracer.debug(".");
        nextPointAlarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
        bluetooth = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        tracer.debug(".");
        HandleIntent(intent);
        return START_REDELIVER_INTENT;
    }

    @Override
    public void onDestroy() {
        tracer.warn("GpsLoggingService is being destroyed by Android OS.");
        mainServiceClient = null;
        super.onDestroy();
    }

    @Override
    public void onLowMemory() {
        tracer.warn("Android is low on memory.");
        super.onLowMemory();
    }

    private void HandleIntent(Intent intent) {

        tracer.debug(".");
        GetPreferences();

        if (intent != null) {
            Bundle bundle = intent.getExtras();

            if (bundle != null) {
                boolean needToStartGpsManager = false;

                boolean stopRightNow = bundle.getBoolean("immediatestop");
                boolean startRightNow = bundle.getBoolean("immediatestart");
                boolean sendEmailNow = bundle.getBoolean("emailAlarm");
                boolean getNextPoint = bundle.getBoolean("getnextpoint");
                boolean startBTLE = bundle.getBoolean("startbtle");
                boolean stopBTLE = bundle.getBoolean("stopbtle");
                boolean updateBicycling = bundle.getBoolean("updatebicycling");
                boolean setstatus = bundle.getBoolean("setstatus");

                tracer.debug("stopRightNow - " + String.valueOf(stopRightNow) + ", startRightNow - "
                        + String.valueOf(startRightNow) + ", sendEmailNow - " + String.valueOf(sendEmailNow)
                        + ", getNextPoint - " + String.valueOf(getNextPoint)
                        + ", startBTLE - " + String.valueOf(startBTLE) + ", stopBTLE - " + String.valueOf(stopBTLE));

                if (updateBicycling) {
                    tracer.info("Intent received - Update Bicycling Now");
                    if (bundle.containsKey("speed")) Session.setBicyclingSpeed(bundle.getDouble("speed",0));
                    if (bundle.containsKey("distance")) Session.setBicyclingDistance(bundle.getDouble("distance",0));
                    if (bundle.containsKey("cadence")) Session.setBicyclingCadence(bundle.getDouble("cadence",0));
                    if (bundle.containsKey("rssi")) Session.setBicyclingRssi(bundle.getInt("rssi",0));
                    if (IsMainFormVisible()) {
                        mainServiceClient.OnLocationUpdate(null);
                    }
                }

                if (setstatus) {
                    tracer.info("Intent received - Set Status Now");
                    String status = bundle.getString("status","");
                    SetStatus(status);
                }

                if (startRightNow) {
                    tracer.info("Intent received - Start Logging Now");
                    String btlestatus = bundle.getString("btlestatus","");
                    if (btlestatus.length() > 0) {
                        SetStatus(btlestatus);
                    }
                    StartLogging();
                }

                if (stopRightNow) {
                    tracer.info("Intent received - Stop logging now");
                    StopLogging();
                }

                if (sendEmailNow) {

                    tracer.debug("Intent received - Send Email Now");

                    Session.setReadyToBeAutoSent(true);
                    AutoSendLogFile();
                }

                if (getNextPoint) {
                    tracer.debug("Intent received - Get Next Point");
                    needToStartGpsManager = true;
                }

                if (startBTLE) {
                    tracer.info("Intent received - Start BTLE Now");
                    StartBTLE();
                }

                if (stopBTLE) {
                    tracer.info("Intent received - Stop BTLE now");
                    StopBTLE();
                    StopLogging();
                }

                String setNextPointDescription = bundle.getString("setnextpointdescription");
                if (setNextPointDescription != null) {
                    tracer.debug("Intent received - Set Next Point Description: " + setNextPointDescription);

                    final String desc = Utilities.CleanDescription(setNextPointDescription);
                    if (desc.length() == 0) {
                        tracer.debug("Clearing annotation");
                        Session.clearDescription();
                    } else {
                        tracer.debug("Setting annotation: " + desc);
                        Session.setDescription(desc);
                    }
                    needToStartGpsManager = true;
                }

                SharedPreferences prefs = PreferenceManager
                        .getDefaultSharedPreferences(getApplicationContext());

                if (bundle.get("setprefercelltower") != null) {
                    boolean preferCellTower = bundle.getBoolean("setprefercelltower");
                    tracer.debug("Intent received - Set Prefer Cell Tower: " + String.valueOf(preferCellTower));
                    prefs.edit().putBoolean("prefer_celltower", preferCellTower).commit();
                    needToStartGpsManager = true;
                }

                if (bundle.get("settimebeforelogging") != null) {
                    int timeBeforeLogging = bundle.getInt("settimebeforelogging");
                    tracer.debug("Intent received - Set Time Before Logging: " + String.valueOf(timeBeforeLogging));
                    prefs.edit().putString("time_before_logging", String.valueOf(timeBeforeLogging)).commit();
                    needToStartGpsManager = true;
                }

                if (bundle.get("setdistancebeforelogging") != null) {
                    int distanceBeforeLogging = bundle.getInt("setdistancebeforelogging");
                    tracer.debug("Intent received - Set Distance Before Logging: " + String.valueOf(distanceBeforeLogging));
                    prefs.edit().putString("distance_before_logging", String.valueOf(distanceBeforeLogging)).commit();
                    needToStartGpsManager = true;
                }

                if (bundle.get("setkeepbetweenfix") != null) {
                    boolean keepBetweenFix = bundle.getBoolean("setkeepbetweenfix");
                    tracer.debug("Intent received - Set Keep Between Fix: " + String.valueOf(keepBetweenFix));
                    prefs.edit().putBoolean("keep_fix", keepBetweenFix).commit();
                    needToStartGpsManager = true;
                }

                if (bundle.get("setretrytime") != null) {
                    int retryTime = bundle.getInt("setretrytime");
                    tracer.debug("Intent received - Set Retry Time: " + String.valueOf(retryTime));
                    prefs.edit().putString("retry_time", String.valueOf(retryTime)).commit();
                    needToStartGpsManager = true;
                }

                if (bundle.get("setabsolutetimeout") != null) {
                    int absolumeTimeOut = bundle.getInt("setabsolutetimeout");
                    tracer.debug("Intent received - Set Retry Time: " + String.valueOf(absolumeTimeOut));
                    prefs.edit().putString("absolute_timeout", String.valueOf(absolumeTimeOut)).commit();
                    needToStartGpsManager = true;
                }

                if(bundle.get("logonce") != null){
                    boolean logOnceIntent = bundle.getBoolean("logonce");
                    tracer.debug("Intent received - Log Once: " + String.valueOf(logOnceIntent));
                    needToStartGpsManager = false;
                    LogOnce();
                }

                if (needToStartGpsManager && Session.isStarted()) {
                    StartGpsManager();
                }
            }
        } else {
            // A null intent is passed in if the service has been killed and
            // restarted.
            tracer.debug("Service restarted with null intent. Start logging.");
            StartLogging();

            SharedPreferences prefs = PreferenceManager
                    .getDefaultSharedPreferences(getApplicationContext());
            boolean startBTLE = prefs.getBoolean("btle_activate", false);
            if (startBTLE) {
               StartBTLE();
            }
        }
    }

    @Override
    public void OnComplete() {
        Utilities.HideProgress();
    }

    @Override
    public void OnFailure() {
        Utilities.HideProgress();
    }

    /**
     * Sets up the auto email timers based on user preferences.
     */
    public void SetupAutoSendTimers() {
        tracer.debug("Setting up autosend timers. Auto Send Enabled - " + String.valueOf(AppSettings.isAutoSendEnabled())
                + ", Auto Send Delay - " + String.valueOf(Session.getAutoSendDelay()));

        if (AppSettings.isAutoSendEnabled() && Session.getAutoSendDelay() > 0) {
            tracer.debug("Setting up autosend alarm");
            long triggerTime = System.currentTimeMillis()
                    + (long) (Session.getAutoSendDelay() * 60 * 1000);

            alarmIntent = new Intent(getApplicationContext(), AlarmReceiver.class);
            CancelAlarm();

            PendingIntent sender = PendingIntent.getBroadcast(this, 0, alarmIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            am.set(AlarmManager.RTC_WAKEUP, triggerTime, sender);
            tracer.debug("Autosend alarm has been set");

        } else {
            if (alarmIntent != null) {
                tracer.debug("alarmIntent was null, canceling alarm");
                CancelAlarm();
            }
        }
    }


    protected void ForceAutoSendNow() {

        tracer.debug(".");
        if (AppSettings.isAutoSendEnabled() && Session.getCurrentFileName() != null && Session.getCurrentFileName().length() > 0) {
            if (IsMainFormVisible()) {
                SetStatus(R.string.autosend_sending);
            }

            tracer.info("Force emailing Log File");
            FileSenderFactory.SendFiles(getApplicationContext(), this);
        }
    }


    public void LogOnce() {
        tracer.debug(".");

        Session.setSinglePointMode(true);

        if (Session.isStarted()) {
            StartGpsManager();
        } else {
            StartLogging();
        }
    }

    private void CancelAlarm() {
        tracer.debug(".");

        if (alarmIntent != null) {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            PendingIntent sender = PendingIntent.getBroadcast(this, 0, alarmIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            tracer.debug("Pending alarm intent was null? " + String.valueOf(sender == null));
            am.cancel(sender);
        }
    }

    /**
     * Method to be called if user has chosen to auto email log files when he
     * stops logging
     */
    private void AutoSendLogFileOnStop() {
        tracer.debug("Auto send on stop enabled: " + String.valueOf(AppSettings.shouldAutoSendWhenIPressStop()));

        if (AppSettings.isAutoSendEnabled() && AppSettings.shouldAutoSendWhenIPressStop()) {
            Session.setReadyToBeAutoSent(true);
            AutoSendLogFile();
        }
    }

    /**
     * Calls the Auto Email Helper which processes the file and sends it.
     */
    private void AutoSendLogFile() {

        tracer.debug("isReadyToBeAutoSent - " + Session.isReadyToBeAutoSent());

        // Check that auto emailing is enabled, there's a valid location and
        // file name.
        if (Session.getCurrentFileName() != null && Session.getCurrentFileName().length() > 0
                && Session.isReadyToBeAutoSent() && Session.hasValidLocation()) {

            //Don't show a progress bar when auto-sending
            tracer.info("Sending Log File");

            FileSenderFactory.SendFiles(getApplicationContext(), this);
            Session.setReadyToBeAutoSent(true);
            SetupAutoSendTimers();

        }
    }

    /**
     * Gets preferences chosen by the user and populates the AppSettings object.
     * Also sets up email timers if required.
     */
    private void GetPreferences() {
        tracer.debug(".");
        Utilities.PopulateAppSettings(getApplicationContext());

        if (Session.getAutoSendDelay() != AppSettings.getAutoSendDelay()) {
            tracer.debug("Old autoSendDelay - " + String.valueOf(Session.getAutoSendDelay())
                    + "; New -" + String.valueOf(AppSettings.getAutoSendDelay()));
            Session.setAutoSendDelay(AppSettings.getAutoSendDelay());
            SetupAutoSendTimers();
        }

    }

    /**
     * Resets the form, resets file name if required, reobtains preferences
     */
    protected void StartLogging() {
        tracer.debug(".");
        Session.setAddNewTrackSegment(true);

        if (Session.isStarted()) {
            tracer.debug("Session already started, ignoring");
            return;
        }


        try {
            tracer.info("Starting GpsLoggingService in foreground");
            startForeground(NOTIFICATION_ID, new Notification());
        } catch (Exception ex) {
            tracer.error("Could not start GPSLoggingService in foreground. ", ex);
        }


        Session.setStarted(true);

        GetPreferences();
        ShowNotification();
        ResetCurrentFileName(true);
        NotifyClientStarted();
        StartPassiveManager();
        StartGpsManager();

    }

    /**
     * Asks the main service client to clear its form.
     */
    private void NotifyClientStarted() {
        if (IsMainFormVisible()) {
            mainServiceClient.OnStartLogging();
        }
    }

    /**
     * Stops logging, removes notification, stops GPS manager, stops email timer
     */
    public void StopLogging() {
        tracer.debug("GpsLoggingService.StopLogging");
        Session.setAddNewTrackSegment(true);

        Session.setStarted(false);
        stopAbsoluteTimer();
        // Email log file before setting location info to null
        AutoSendLogFileOnStop();
        CancelAlarm();
        Session.setCurrentLocationInfo(null);
        Session.setSinglePointMode(false);
        stopForeground(true);

        RemoveNotification();
        StopAlarm();
        StopGpsManager();
        StopPassiveManager();
        NotifyClientStopped();
    }

    /**
     * Hides the notification icon in the status bar if it's visible.
     */
    private void RemoveNotification() {
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notificationManager.cancelAll();
    }

    /**
     * Shows a notification icon in the status bar for GPS Logger
     */
    private void ShowNotification() {
        tracer.debug("GpsLoggingService.ShowNotification");
        // What happens when the notification item is clicked
        Intent contentIntent = new Intent(this, GpsMainActivity.class);

        TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
        stackBuilder.addNextIntent(contentIntent);

        PendingIntent pending = stackBuilder.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT);


        NumberFormat nf = new DecimalFormat("###.#####");

        String contentText = getString(R.string.gpslogger_still_running);
        long notificationTime = System.currentTimeMillis();

        if (Session.hasValidLocation()) {
            contentText = getString(R.string.txt_latitude_short) + ": " + nf.format(Session.getCurrentLatitude()) + ", "
                    + getString(R.string.txt_longitude_short) + ": " + nf.format(Session.getCurrentLongitude());

            notificationTime = Session.getCurrentLocationInfo().getTime();
        }

        if (nfc == null) {
            nfc = new NotificationCompat.Builder(getApplicationContext())
                    .setSmallIcon(R.drawable.gpsloggericon2)
                    .setContentTitle(getString(R.string.gpslogger_still_running))
                    .setOngoing(true)
                    .setContentIntent(pending);
        }

        nfc.setContentText(contentText);
        nfc.setWhen(notificationTime);

        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID, nfc.build());
    }

    private void StartPassiveManager() {
        if(AppSettings.getChosenListeners().contains("passive")){
            tracer.debug("Starting passive location listener");
            if(passiveLocationListener== null){
                passiveLocationListener = new GeneralLocationListener(this, "PASSIVE");
            }
            passiveLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            passiveLocationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 1000, 0, passiveLocationListener);
        }
    }

    /**
     * Starts the location manager. There are two location managers - GPS and
     * Cell Tower. This code determines which manager to request updates from
     * based on user preference and whichever is enabled. If GPS is enabled on
     * the phone, that is used. But if the user has also specified that they
     * prefer cell towers, then cell towers are used. If neither is enabled,
     * then nothing is requested.
     */
    private void StartGpsManager() {
        tracer.debug("GpsLoggingService.StartGpsManager");

        GetPreferences();

        if (gpsLocationListener == null) {
            gpsLocationListener = new GeneralLocationListener(this, "GPS");
        }

        if (towerLocationListener == null) {
            towerLocationListener = new GeneralLocationListener(this, "CELL");
        }

        gpsLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        towerLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        CheckTowerAndGpsStatus();

        if (Session.isGpsEnabled() && AppSettings.getChosenListeners().contains("gps")) {
            tracer.info("Requesting GPS location updates");
            // gps satellite based
            gpsLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                    1000, 0,
                    gpsLocationListener);

            gpsLocationManager.addGpsStatusListener(gpsLocationListener);
            gpsLocationManager.addNmeaListener(gpsLocationListener);

            Session.setUsingGps(true);
            startAbsoluteTimer();

        }

        if (Session.isTowerEnabled() &&  ( AppSettings.getChosenListeners().contains("network")  || !Session.isGpsEnabled() ) ) {
            tracer.info("Requesting tower location updates");
            Session.setUsingGps(false);
            // Cell tower and wifi based
            towerLocationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                    1000, 0,
                    towerLocationListener);

            startAbsoluteTimer();

        }

        if(!Session.isTowerEnabled() && !Session.isGpsEnabled()) {
            tracer.info("No provider available");
            Session.setUsingGps(false);
            SetStatus(R.string.gpsprovider_unavailable);
            SetFatalMessage(R.string.gpsprovider_unavailable);
            StopLogging();
            return;
        }

        if (mainServiceClient != null) {
            mainServiceClient.OnWaitingForLocation(true);
            Session.setWaitingForLocation(true);
        }

        SetStatus(R.string.started);
    }

    private void startAbsoluteTimer() {
        if (AppSettings.getAbsoluteTimeout() >= 1) {
            tracer.debug("Starting absolute timer");
            handler.postDelayed(stopManagerRunnable, AppSettings.getAbsoluteTimeout() * 1000);
        }
    }

    private Runnable stopManagerRunnable = new Runnable() {
        @Override
        public void run() {
            tracer.warn("Absolute timeout reached, giving up on this point");
            StopManagerAndResetAlarm();
        }
    };

    private void stopAbsoluteTimer() {
        tracer.debug("Stopping absolute timer");
        handler.removeCallbacks(stopManagerRunnable);
    }

    /**
     * This method is called periodically to determine whether the cell tower /
     * gps providers have been enabled, and sets class level variables to those
     * values.
     */
    private void CheckTowerAndGpsStatus() {
        Session.setTowerEnabled(towerLocationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
        Session.setGpsEnabled(gpsLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER));
    }

    /**
     * Stops the location managers
     */
    private void StopGpsManager() {

        tracer.debug("GpsLoggingService.StopGpsManager");

        if (towerLocationListener != null) {
            tracer.debug("Removing towerLocationManager updates");
            towerLocationManager.removeUpdates(towerLocationListener);
        }

        if (gpsLocationListener != null) {
            tracer.debug("Removing gpsLocationManager updates");
            gpsLocationManager.removeUpdates(gpsLocationListener);
            gpsLocationManager.removeGpsStatusListener(gpsLocationListener);
        }


        if (mainServiceClient != null) {
            Session.setWaitingForLocation(false);
            mainServiceClient.OnWaitingForLocation(false);
        }
        SetStatus(getString(R.string.stopped));
    }

    private void StopPassiveManager(){
        if(passiveLocationManager!=null){
            tracer.debug("Removing passiveLocationManager updates");
            passiveLocationManager.removeUpdates(passiveLocationListener);
        }
    }

    /**
     * Sets the current file name based on user preference.
     */
    private void ResetCurrentFileName(boolean newLogEachStart) {

        tracer.debug(".");

        /* Pick up saved settings, if any. (Saved static file) */
        String newFileName = Session.getCurrentFileName();

        /* Update the file name, if required. (New day, Re-start service) */
        if (AppSettings.isCustomFile()) {
            newFileName = AppSettings.getCustomFileName();
            Session.setCurrentFileName(AppSettings.getCustomFileName());
        } else if (AppSettings.shouldCreateNewFileOnceADay()) {
            // 20100114.gpx
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
            newFileName = sdf.format(new Date());
            Session.setCurrentFileName(newFileName);
        } else if (newLogEachStart) {
            // 20100114183329.gpx
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
            newFileName = sdf.format(new Date());
            Session.setCurrentFileName(newFileName);
        }

        if (IsMainFormVisible()) {
            tracer.info("File name: " + newFileName);
            mainServiceClient.onFileName(newFileName);
        }
    }

    /**
     * Gives a status message to the main service client to display
     *
     * @param status The status message
     */
    void SetStatus(String status) {
        tracer.info(status);
        if (IsMainFormVisible()) {
            mainServiceClient.OnStatusMessage(status);
        }
    }

    /**
     * Gives an error message to the main service client to display
     *
     * @param messageId ID of string to lookup
     */
    void SetFatalMessage(int messageId) {
        tracer.error(getString(messageId));
        if (IsMainFormVisible()) {
            mainServiceClient.OnFatalMessage(getString(messageId));
        }
    }

    /**
     * Gets string from given resource ID, passes to SetStatus(String)
     *
     * @param stringId ID of string to lookup
     */
    private void SetStatus(int stringId) {
        String s = getString(stringId);
        SetStatus(s);
    }

    /**
     * Notifies main form that logging has stopped
     */
    void NotifyClientStopped() {
        if (IsMainFormVisible()) {
            mainServiceClient.OnStopLogging();
        }
    }

    /**
     * Stops location manager, then starts it.
     */
    void RestartGpsManagers() {
        tracer.debug("GpsLoggingService.RestartGpsManagers");
        StopGpsManager();
        StartGpsManager();
    }

    /**
     * This event is raised when the GeneralLocationListener has a new location.
     * This method in turn updates notification, writes to file, reobtains
     * preferences, notifies main service client and resets location managers.
     *
     * @param loc Location object
     */
    void OnLocationChanged(Location loc) {

        if (!Session.isStarted()) {
            tracer.debug("OnLocationChanged called, but Session.isStarted is false");
            StopLogging();
            return;
        }

        long currentTimeStamp = System.currentTimeMillis();

        // Don't log a point until the user-defined time has elapsed
        // However, if user has set an annotation, just log the point, disregard any filters
        if (!Session.hasDescription() && !Session.isSinglePointMode() && (currentTimeStamp - Session.getLatestTimeStamp()) < (AppSettings.getMinimumSeconds() * 1000)) {
            return;
        }

        if(!isFromValidListener(loc)){
            return;
        }

        tracer.debug("GpsLoggingService.OnLocationChanged");
        boolean isPassiveLocation = loc.getExtras().getBoolean("PASSIVE");

        // Don't do anything until the user-defined accuracy is reached
        // However, if user has set an annotation, just log the point, disregard any filters
        if (!Session.hasDescription() &&  AppSettings.getMinimumAccuracyInMeters() > 0) {

            //Don't apply the retry interval to passive locations
            if (!isPassiveLocation && AppSettings.getMinimumAccuracyInMeters() < Math.abs(loc.getAccuracy())) {

                if (this.firstRetryTimeStamp == 0) {
                    this.firstRetryTimeStamp = System.currentTimeMillis();
                }

                if (currentTimeStamp - this.firstRetryTimeStamp <= AppSettings.getRetryInterval() * 1000) {
                    tracer.warn("Only accuracy of " + String.valueOf(Math.floor(loc.getAccuracy())) + " m. Point discarded.");
                    SetStatus("Inaccurate point discarded.");
                    //return and keep trying
                    return;
                }

                if (currentTimeStamp - this.firstRetryTimeStamp > AppSettings.getRetryInterval() * 1000) {
                    tracer.warn("Only accuracy of " + String.valueOf(Math.floor(loc.getAccuracy())) + " m and timeout reached");
                    SetStatus("Inaccurate points discarded and retries timed out.");
                    //Give up for now
                    StopManagerAndResetAlarm();

                    //reset timestamp for next time.
                    this.firstRetryTimeStamp = 0;
                    return;
                }

                //Success, reset timestamp for next time.
                this.firstRetryTimeStamp = 0;
            }
        }

        //Don't do anything until the user-defined distance has been traversed
        // However, if user has set an annotation, just log the point, disregard any filters
        if (!Session.hasDescription() && !Session.isSinglePointMode() && AppSettings.getMinimumDistanceInMeters() > 0 && Session.hasValidLocation()) {

            double distanceTraveled = Utilities.CalculateDistance(loc.getLatitude(), loc.getLongitude(),
                    Session.getCurrentLatitude(), Session.getCurrentLongitude());

            if (AppSettings.getMinimumDistanceInMeters() > distanceTraveled) {
                SetStatus("Only " + String.valueOf(Math.floor(distanceTraveled)) + " m traveled. Point discarded.");
                tracer.warn("Only " + String.valueOf(Math.floor(distanceTraveled)) + " m traveled. Point discarded.");
                StopManagerAndResetAlarm();
                return;
            }
        }


        tracer.info("Location to update: " + String.valueOf(loc.getLatitude()) + "," + String.valueOf(loc.getLongitude()));
        ResetCurrentFileName(false);
        Session.setLatestTimeStamp(System.currentTimeMillis());
        Session.setCurrentLocationInfo(loc);
        SetDistanceTraveled(loc);
        ShowNotification();

        if(isPassiveLocation){
            tracer.debug("Logging passive location to file");
        }

        if (Session.getBicyclingDistance()>0) {
            Bundle locExtras = loc.getExtras();
            locExtras.putDouble("bicyclingspeed",Session.getBicyclingSpeed());
            locExtras.putDouble("bicyclingdistance",Session.getBicyclingDistance());
            locExtras.putDouble("bicyclingcadence",Session.getBicyclingCadence());
            loc.setExtras(locExtras);
        }

        WriteToFile(loc);
        GetPreferences();
        StopManagerAndResetAlarm();

        if (IsMainFormVisible()) {

            mainServiceClient.OnLocationUpdate(loc);
        }

        if (Session.isSinglePointMode()) {
            tracer.debug("Single point mode - stopping logging now");
            StopLogging();
        }
    }

    private boolean isFromValidListener(Location loc) {

        if(!AppSettings.getChosenListeners().contains("gps") && !AppSettings.getChosenListeners().contains("network")){
            return true;
        }

        if(!AppSettings.getChosenListeners().contains("network")){
            return loc.getProvider().equalsIgnoreCase("gps");
        }

        if(!AppSettings.getChosenListeners().contains("gps")){
            return !loc.getProvider().equalsIgnoreCase("gps");
        }

        return true;
    }

    private void SetDistanceTraveled(Location loc) {
        // Distance
        if (Session.getPreviousLocationInfo() == null) {
            Session.setPreviousLocationInfo(loc);
        }
        // Calculate this location and the previous location location and add to the current running total distance.
        // NOTE: Should be used in conjunction with 'distance required before logging' for more realistic values.
        double distance = Utilities.CalculateDistance(
                Session.getPreviousLatitude(),
                Session.getPreviousLongitude(),
                loc.getLatitude(),
                loc.getLongitude());
        Session.setPreviousLocationInfo(loc);
        Session.setTotalTravelled(Session.getTotalTravelled() + distance);
    }

    protected void StopManagerAndResetAlarm() {
        tracer.debug("GpsLoggingService.StopManagerAndResetAlarm");
        if (!AppSettings.shouldkeepFix()) {
            StopGpsManager();
        }

        stopAbsoluteTimer();
        SetAlarmForNextPoint();
    }


    private void StopAlarm() {
        tracer.debug("GpsLoggingService.StopAlarm");
        Intent i = new Intent(this, GpsLoggingService.class);
        i.putExtra("getnextpoint", true);
        PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
        nextPointAlarmManager.cancel(pi);
    }

    private void SetAlarmForNextPoint() {

        tracer.debug("GpsLoggingService.SetAlarmForNextPoint");

        Intent i = new Intent(this, GpsLoggingService.class);

        i.putExtra("getnextpoint", true);

        PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
        nextPointAlarmManager.cancel(pi);

        nextPointAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + AppSettings.getMinimumSeconds() * 1000, pi);

    }


    /**
     * Calls file helper to write a given location to a file.
     *
     * @param loc Location object
     */
    private void WriteToFile(Location loc) {
        tracer.debug("GpsLoggingService.WriteToFile");
        List<IFileLogger> loggers = FileLoggerFactory.GetFileLoggers(getApplicationContext());
        Session.setAddNewTrackSegment(false);
        boolean atLeastOneAnnotationSuccess = false;

        for (IFileLogger logger : loggers) {
            try {
                logger.Write(loc);
                if (Session.hasDescription()) {
                    logger.Annotate(Session.getDescription(), loc);
                    atLeastOneAnnotationSuccess = true;
                }
            } catch (Exception e) {
                SetStatus(R.string.could_not_write_to_file);
            }
        }

        if (atLeastOneAnnotationSuccess) {
            Session.clearDescription();
            if (IsMainFormVisible()) {
                mainServiceClient.OnClearAnnotation();
            }
        }
    }

    /**
     * Informs the main service client of the number of visible satellites.
     *
     * @param count Number of Satellites
     */
    void SetSatelliteInfo(int count) {
        Session.setSatelliteCount(count);
        if (IsMainFormVisible()) {
            mainServiceClient.OnSatelliteCount(count);
        }
    }

    private boolean IsMainFormVisible() {
        return mainServiceClient != null;
    }

    public void OnNmeaSentence(long timestamp, String nmeaSentence) {

        if (AppSettings.shouldLogToNmea()) {
            NmeaFileLogger nmeaLogger = new NmeaFileLogger(Session.getCurrentFileName());
            nmeaLogger.Write(timestamp, nmeaSentence);
        }

        if (IsMainFormVisible()) {
            mainServiceClient.OnNmeaSentence(timestamp, nmeaSentence);
        }
    }

    /**
     * Can be used from calling classes as the go-between for methods and
     * properties.
     */
    public class GpsLoggingBinder extends Binder {
        public GpsLoggingService getService() {
            tracer.debug("GpsLoggingBinder.getService");
            return GpsLoggingService.this;
        }
    }

    /**
     * BTLE activation
     */

    BluetoothAdapter adapter;
    BluetoothDevice device;
    private final double wheelSizeDefault = 2.17;
    double wheelSize = wheelSizeDefault;
    boolean beepBTLE = false;

    void StartBTLE() {
        SharedPreferences prefs = PreferenceManager
                .getDefaultSharedPreferences(getApplicationContext());
        wheelSize = Double.parseDouble(prefs.getString("wheel_size","0"));
        if (wheelSize==0) wheelSize=wheelSizeDefault;
        beepBTLE = prefs.getBoolean("btle_beep", false);
        String btleDevice = prefs.getString("btle_device", "no device yet");
        tracer.debug("Starting BTLE waiting for '"+btleDevice+"'.");
        if (connectedGatt != null) {
            connectedGatt.disconnect();
            connectedGatt.close();
        }
        adapter = bluetooth.getAdapter();
        device = adapter.getRemoteDevice(btleDevice);
        device.connectGatt(getBaseContext(), true, bluetoothGattCallback);
    }

    protected void StopBTLE() {
        tracer.debug("Stopping BTLE.");
        if (connectedGatt != null) {
            connectedGatt.disconnect();
            connectedGatt.close();
        }
        connectedGatt = null;
    }



    private static final UUID CSC_SERVICE_UUID = UUID.fromString("00001816-0000-1000-8000-00805f9b34fb");
    private static final UUID CSC_CHARACTERISTIC_UUID = UUID.fromString("00002a5b-0000-1000-8000-00805f9b34fb");
    private static final UUID BTLE_NOTIFICATION_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private BluetoothGatt connectedGatt = null;
    private boolean connectingToGatt;

    private final Object connectingToGattMonitor = new Object();

    public static final int NOT_SET = Integer.MIN_VALUE;


    private BluetoothGattCallback bluetoothGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int state) {
            connectingToGatt = false;
            super.onConnectionStateChange(gatt, status, state);
            tracer.debug("onConnectionStateChange gatt:" + gattToString(gatt) + " status:" + statusToString(status) + " state:" + connectionStateToString(state));

            switch (state) {
                case BluetoothGatt.STATE_CONNECTED: {
                    connectedGatt = gatt;
                    gatt.discoverServices();

                    Intent serviceIntent = new Intent(getBaseContext(), GpsLoggingService.class);
                    serviceIntent.putExtra("immediatestart", true);
                    serviceIntent.putExtra("btlestatus", gattToString(gatt));
                    getApplicationContext().startService(serviceIntent);
                    break;
                }
                case BluetoothGatt.STATE_DISCONNECTED: {
                    Intent serviceIntent = new Intent(getBaseContext(), GpsLoggingService.class);
                    serviceIntent.putExtra("immediatestop", true);
                    getApplicationContext().startService(serviceIntent);
                }
                case BluetoothGatt.GATT_FAILURE: {
                    connectedGatt = null;
                    break;
                }
            }
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            super.onReadRemoteRssi(gatt, rssi, status);
            tracer.debug("onReadRemoteRssi rssi:" + rssi);

            Intent serviceIntent = new Intent(getBaseContext(), GpsLoggingService.class);
            serviceIntent.putExtra("updatebicycling", true);
            serviceIntent.putExtra("rssi", rssi);
            getApplicationContext().startService(serviceIntent);
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);
            tracer.debug("onServicesDiscovered status:" + statusToString(status));
            BluetoothGattCharacteristic valueCharacteristic = gatt.getService(CSC_SERVICE_UUID).getCharacteristic(CSC_CHARACTERISTIC_UUID);
            boolean notificationSet = gatt.setCharacteristicNotification(valueCharacteristic, true);
            tracer.debug("registered for updates " + (notificationSet ? "successfully" : "unsuccessfully"));

            BluetoothGattDescriptor descriptor = valueCharacteristic.getDescriptor(BTLE_NOTIFICATION_DESCRIPTOR_UUID);
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            boolean writeDescriptorSuccess = gatt.writeDescriptor(descriptor);
            tracer.debug("wrote Descriptor for updates " + (writeDescriptorSuccess ? "successfully" : "unsuccessfully") );
        }

        double lastWheelTime = NOT_SET;
        long lastWheelCount = NOT_SET;
        double lastCrankTime = NOT_SET;
        long lastCrankCount = NOT_SET;

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);
            byte[] value = characteristic.getValue();

            final long cumulativeWheelRevolutions       = (value[1] & 0xff) | ((value[2] & 0xff) << 8) | ((value[3] & 0xff) << 16) | ((value[4] & 0xff) << 24);
            final int lastWheelEventReadValue           = (value[5] & 0xff) | ((value[6] & 0xff) << 8);
            final long cumulativeCrankRevolutions        = (value[7] & 0xff) | ((value[8] & 0xff) << 8);
            final int lastCrankEventReadValue           = (value[9] & 0xff) | ((value[10] & 0xff) << 8);

            double lastWheelEventTime = lastWheelEventReadValue / 1024.0;
            double lastCrankEventTime = lastCrankEventReadValue / 1024.0;

            tracer.debug("onCharacteristicChanged " + cumulativeWheelRevolutions + ":" + lastWheelEventReadValue + ":" + cumulativeCrankRevolutions + ":" + lastCrankEventReadValue);

            if (lastWheelTime == NOT_SET){
                lastWheelTime = lastWheelEventTime;
            }
            if (lastWheelCount == NOT_SET){
                lastWheelCount = cumulativeWheelRevolutions;
            }

            if (lastCrankTime == NOT_SET){
                lastCrankTime = lastCrankEventTime;
            }
            if (lastCrankCount == NOT_SET){
                lastCrankCount = cumulativeCrankRevolutions;
            }

            Intent serviceIntent = new Intent(getBaseContext(), GpsLoggingService.class);
            serviceIntent.putExtra("updatebicycling", true);
            //serviceIntent.putExtra("status", String.format( "Speed: %.1f Dist: %.3f C: %d", speedInKilometersPerHour, distanceinKilometers, numberOfCrankRevolutions));


            double distanceinKilometers = cumulativeWheelRevolutions * wheelSize / 1000;
            tracer.debug("distance:" + distanceinKilometers);
            serviceIntent.putExtra("distance", distanceinKilometers);


            long numberOfWheelRevolutions = cumulativeWheelRevolutions - lastWheelCount;

            if (lastWheelTime  != lastWheelEventTime && numberOfWheelRevolutions > 0){
                double timeDiff = lastWheelEventTime - lastWheelTime;

                double speedinMetersPerSeconds = (wheelSize * numberOfWheelRevolutions) / timeDiff;
                double speedInKilometersPerHour = speedinMetersPerSeconds * 3.6;

                tracer.debug("speed:" + speedInKilometersPerHour);

                serviceIntent.putExtra("speed", speedInKilometersPerHour);

                lastWheelCount = cumulativeWheelRevolutions;
                lastWheelTime = lastWheelEventTime;

                if (beepBTLE) {
                    ToneGenerator toneG = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
                    toneG.startTone(ToneGenerator.TONE_DTMF_1, 200);
                }
            }


            long numberOfCrankRevolutions = cumulativeCrankRevolutions - lastCrankCount;

            if (lastCrankTime  != lastCrankEventTime && numberOfCrankRevolutions > 0){
                double timeDiff = lastCrankEventTime - lastCrankTime;

                double cadenceinRevolutionsPerMinute = numberOfCrankRevolutions * 60 / timeDiff;

                tracer.debug("cadence:" + cadenceinRevolutionsPerMinute);

                serviceIntent.putExtra("cadence", cadenceinRevolutionsPerMinute);

                lastCrankCount = cumulativeCrankRevolutions;
                lastCrankTime = lastCrankEventTime;

                if (beepBTLE) {
                    ToneGenerator toneG = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
                    toneG.startTone(ToneGenerator.TONE_DTMF_9, 200);
                }
            }

            getApplicationContext().startService(serviceIntent);

            gatt.readRemoteRssi();
        }
    };
    public static String connectionStateToString(int state) {
        switch (state) {
            case BluetoothProfile.STATE_CONNECTED:
                return "STATE_CONNECTED";
            case BluetoothProfile.STATE_DISCONNECTED:
                return "STATE_DISCONNECTED";
            case BluetoothProfile.STATE_CONNECTING:
                return "STATE_CONNECTING";
            case BluetoothProfile.STATE_DISCONNECTING:
                return "STATE_DISCONNECTING";
            default:
                return "unknown state:" + state;
        }
    }

    public static String statusToString(int status) {
        switch (status){
            case BluetoothGatt.GATT_SUCCESS:
                return "GATT_SUCCESS";
            case BluetoothGatt.GATT_READ_NOT_PERMITTED:
                return "GATT_READ_NOT_PERMITTED";
            case BluetoothGatt.GATT_WRITE_NOT_PERMITTED:
                return "GATT_WRITE_NOT_PERMITTED";
            case BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED:
                return "GATT_REQUEST_NOT_SUPPORTED";
            case BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION:
                return "GATT_INSUFFICIENT_AUTHENTICATION";
            case BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION:
                return "GATT_INSUFFICIENT_ENCRYPTION";
            case BluetoothGatt.GATT_INVALID_OFFSET:
                return "GATT_INVALID_OFFSET";
            case BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH:
                return "GATT_INVALID_ATTRIBUTE_LENGTH";
            case BluetoothGatt.GATT_FAILURE:
                return "GATT_FAILURE";
            default:
                return "unknown state:" + status;
        }
    }

    public static String gattToString(BluetoothGatt gatt) {
        if (gatt == null){
            return "null";
        }
        return gatt.getDevice().getName();
    }

}
