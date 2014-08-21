package org.droidplanner.android.notifications;

import java.util.Locale;
import java.util.Map;

import org.droidplanner.R;
import org.droidplanner.android.DroidPlannerApp;
import org.droidplanner.android.utils.prefs.DroidPlannerPrefs;
import org.droidplanner.core.drone.Drone;
import org.droidplanner.core.drone.DroneInterfaces.DroneEventsType;
import org.droidplanner.core.drone.variables.Calibration;
import org.droidplanner.core.drone.DroneInterfaces.Handler;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.preference.PreferenceManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeech.OnInitListener;
import android.util.Log;
import android.widget.Toast;

import com.MAVLink.Messages.ApmModes;

/**
 * Implements DroidPlanner audible notifications.
 */
public class TTSNotificationProvider implements OnInitListener,
		NotificationHandler.NotificationProvider {

    private static final String TAG = TTSNotificationProvider.class.getSimpleName();

	private static final double BATTERY_DISCHARGE_NOTIFICATION_EVERY_PERCENT = 10;

	TextToSpeech tts;
	private SharedPreferences prefs;
	private int lastBatteryDischargeNotification;

	private Context context;
	private Handler handler;
	private int statusInterval;
	private class Watchdog implements Runnable{
		private Drone drone;
		public void run() {
			speakPeriodic(drone);
		}

		public void setDrone(Drone drone){
			this.drone = drone;
		}
	}
	public Watchdog watchdogCallback = new Watchdog();

	TTSNotificationProvider(Context context, Handler handler) {
		this.context = context;
		tts = new TextToSpeech(context, this);
		this.prefs = PreferenceManager.getDefaultSharedPreferences(context);
		this.handler = handler;
	}

	@Override
	public void onInit(int status) {
        if(status == TextToSpeech.SUCCESS) {
            //TODO: check if the language is available
            Locale ttsLanguage;
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                ttsLanguage = tts.getDefaultLanguage();
            }
            else{
                ttsLanguage = tts.getLanguage();
            }

            if(ttsLanguage == null){
                ttsLanguage = Locale.US;
            }

            int supportStatus = tts.setLanguage(ttsLanguage);
            switch(supportStatus){
                case TextToSpeech.LANG_MISSING_DATA:
                case TextToSpeech.LANG_NOT_SUPPORTED:
                    tts.shutdown();
                    tts = null;

                    Log.e(TAG, "TTS Language data is not available.");
                    Toast.makeText(context, "Unable to set 'Text to Speech' language!",
                            Toast.LENGTH_LONG).show();
                    break;
            }
        }
        else{
            //Notify the user that the tts engine is not available.
            Log.e(TAG, "TextToSpeech initialization failed.");
            Toast.makeText(context, "Please make sure 'Text to Speech' is enabled in the " +
                            "system accessibility settings.", Toast.LENGTH_LONG).show();
        }
	}

	private void speak(String string) {
		if (tts != null) {
			if (shouldEnableTTS()) {
				tts.speak(string, TextToSpeech.QUEUE_FLUSH, null);
			}
		}
	}

	private boolean shouldEnableTTS() {
		return prefs.getBoolean("pref_enable_tts", false);
	}

	/**
	 * Warn the user if needed via the TTSNotificationProvider module
	 */
	@Override
	public void onDroneEvent(DroneEventsType event, Drone drone) {
		if (tts != null) {
			switch (event) {
			case INVALID_POLYGON:
				Toast.makeText(context, R.string.exception_draw_polygon, Toast.LENGTH_SHORT).show();
				break;
			case ARMING:
				speakArmedState(drone.state.isArmed());
				break;
			case ARMING_STARTED:
				speak("Arming the vehicle, please standby");
				break;
			case BATTERY:
				batteryDischargeNotification(drone.battery.getBattRemain());
				break;
			case MODE:
				speakMode(drone.state.getMode());
				break;
			case MISSION_SENT:
				Toast.makeText(context, "Waypoints sent", Toast.LENGTH_SHORT).show();
				speak("Waypoints saved to Drone");
				break;
			case GPS_FIX:
				speakGpsMode(drone.GPS.getFixTypeNumeric());
				break;
			case MISSION_RECEIVED:
				Toast.makeText(context, "Waypoints received from Drone", Toast.LENGTH_SHORT).show();
				speak("Waypoints received");
				break;
			case HEARTBEAT_FIRST:
				statusInterval = new DroidPlannerPrefs(context).getSpokenStatusInterval();
				setupPeriodicSpeechOutput(statusInterval, drone);
				speak("Connected");
				break;
			case HEARTBEAT_TIMEOUT:
				if (!Calibration.isCalibrating()) {
					speak("Data link lost, check connection.");
					handler.removeCallbacks(watchdogCallback);
				}
				break;
			case HEARTBEAT_RESTORED:
				statusInterval = new DroidPlannerPrefs(context).getSpokenStatusInterval();
				setupPeriodicSpeechOutput(statusInterval, drone);
				speak("Data link restored");
				break;
			case DISCONNECTED:
				handler.removeCallbacks(watchdogCallback);
				break;
			case MISSION_WP_UPDATE:
				speak("Going for waypoint " + drone.missionStats.getCurrentWP());
				break;
			case FOLLOW_START:
				speak("Following");
				break;
			case PERIODIC_SPEECH:

				break;
			case FAILSAFE:
				String failsafe = drone.state.getFailsafe();
				if(drone.state.isFailsafe()){
					speak(failsafe);
				}
			default:
				break;
			}
		}
	}

	private void speakArmedState(boolean armed) {
		if (armed) {
			speak("Armed");
		} else {
			speak("Disarmed");
		}
	}

	private void batteryDischargeNotification(double battRemain) {
		if (lastBatteryDischargeNotification > (int) ((battRemain - 1) / BATTERY_DISCHARGE_NOTIFICATION_EVERY_PERCENT)
				|| lastBatteryDischargeNotification + 1 < (int) ((battRemain - 1) / BATTERY_DISCHARGE_NOTIFICATION_EVERY_PERCENT)) {
			lastBatteryDischargeNotification = (int) ((battRemain - 1) / BATTERY_DISCHARGE_NOTIFICATION_EVERY_PERCENT);
			speak("Battery at" + (int) battRemain + "%");
		}
	}

	private void speakMode(ApmModes mode) {
		String modeString = "Mode ";
		switch (mode) {
		case FIXED_WING_FLY_BY_WIRE_A:
			modeString += "Fly by wire A";
			break;
		case FIXED_WING_FLY_BY_WIRE_B:
			modeString += "Fly by wire B";
			break;
		case ROTOR_ACRO:
			modeString += "Acrobatic";
			break;
		case ROTOR_ALT_HOLD:
			modeString += "Altitude hold";
			break;
		case ROTOR_POSITION:
			modeString += "Position hold";
			break;
		case FIXED_WING_RTL:
		case ROTOR_RTL:
			modeString += "Return to home";
			break;
		default:
			modeString += mode.getName();
			break;
		}
		speak(modeString);
	}

	private void speakGpsMode(int fix) {
		switch (fix) {
		case 2:
			speak("GPS 2D Lock");
			break;
		case 3:
			speak("GPS 3D Lock");
			break;
		default:
			speak("Lost GPS Lock");
			break;
		}
	}

	private void speakPeriodic(Drone drone){
		DroidPlannerPrefs preferences = new DroidPlannerPrefs(context);
		Map<String,Boolean> speechPrefs = preferences.getPeriodicSpeechPrefs();
		StringBuilder message = new StringBuilder();
		if(speechPrefs.get("battery voltage")){
			message.append("battery " + drone.battery.getBattVolt() + " volts. ");
		}
		if(speechPrefs.get("altitude")){
			message.append("altitude, " + (int)(drone.altitude.getAltitude()*10.0)/10.0 + " meters. ");
		}
		if(speechPrefs.get("airspeed")){
			message.append("airspeed, " + (int)(drone.speed.getAirSpeed().valueInMetersPerSecond()*10.0)/10.0 + " meters per second. ");
		}
		if(speechPrefs.get("rssi")){
			message.append("r s s i, " + drone.radio.getRssi() + " decibels");
		}
		speak(message.toString());
		if(preferences.getSpokenStatusInterval() != 0) {
			handler.postDelayed(watchdogCallback, statusInterval * 1000);
		}else{
			handler.removeCallbacks(watchdogCallback);
		}
	}

	public void setupPeriodicSpeechOutput(int interval, Drone drone){
		watchdogCallback.setDrone(drone);
		if(interval == 0){
			handler.removeCallbacks(watchdogCallback);
		}else{
			statusInterval = interval;
			handler.removeCallbacks(watchdogCallback);
			handler.postDelayed(watchdogCallback,interval*1000);
		}
	}

	@Override
	public void quickNotify(String feedback) {
		speak(feedback);
	}
}
