package android.hardware;

import java.util.ArrayList;
import java.util.List;

import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.sensorhub.SensorHub;
import android.hardware.sensorhub.Task;
import android.hardware.sensorhub.TaskHeader;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.util.Log;
import android.util.SparseArray;

// now only support accelerometer 
public class SensorHubManager {

	private static final String TAG = "SensorHubManager";
	private static final String ACTION_USB_PERMISSION = "android.hardware.sensorhub.USB_PERMISSION";

	private static boolean sInitialized = false;
	private static SensorHubDriver sSensorHub;
	private static ArrayList<Sensor> sSensorsList = new ArrayList<Sensor>();
	private static SparseArray<Sensor> sSensorsMap = new SparseArray<Sensor>();
	private static final ArrayList<ListenerDelegate> sListeners = new ArrayList<ListenerDelegate>();
	
	//private Context mContext;
	private Looper mMainLooper;
	
	class SensorHubDriver {
		private SensorHub mSensorHub;
		private short sampleDelay;
		private short numSamples;
		private long interval;
		private TaskHeader mHeader = null;
		private List<SensorEvent> mSensorEventList;
		
		private Context mContext;
		private AlarmManager mAlarmManager;
		private PendingIntent reader = null;
		
		private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				if (intent.getAction().equals(Intent.ACTION_READ_SENSOR)) {
					readSensorData();
				}
			}
		};
		
		public SensorHubDriver(Context ctx) {
			mContext = ctx;
			UsbManager usbManager = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
			mSensorHub = new SensorHub(usbManager);
			mHeader = new TaskHeader('0'); // appID always '0'
			mAlarmManager = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
			mSensorEventList = new ArrayList<SensorEvent>();
			
			// Register broadcast for ACTION_RUN
			IntentFilter filter = new IntentFilter();
			filter.addAction("android.intent.action.READ_SENSOR");
			ctx.registerReceiver(mReceiver, filter);
		}
		
		public void startLocked(short delay, short num) {
			if (reader == null) {
				if (!mSensorHub.open())
					return;
				mHeader.reset();
				sampleDelay = delay;
				numSamples = num;
				interval = delay * num;
				Log.d(TAG, "delay=" + delay +", num=" + num + ", interval=" + interval);
				
				mSensorHub.sendBuffer(mHeader, Task.sensorAccel, sampleDelay, numSamples);
				
				Intent intent = new Intent(Intent.ACTION_READ_SENSOR);
				reader = PendingIntent.getBroadcast(mContext, 0, intent, 0);
				mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + interval, reader);
				Log.d(TAG, "Set up AlarmManager");
				
			} else {
				// TODO
				synchronized (mSensorHub) {
					if (delay < sampleDelay)
						sampleDelay = delay;
					if (num < numSamples)
						numSamples = num;
				}
			}
		}
		
		// return: num of samples; -1 if appID or notificationID doesn't match
		private int parseHeader(String str) {
			Log.d(TAG, "header: " + str);
			String[] headers = str.split(",");
			char appID = headers[0].charAt(0);
			char notificationID = headers[1].charAt(0);
			
			if (appID != mHeader.appID || notificationID != mHeader.notificationID) {
				Log.d(TAG, "appID or notificationID doesn't match (" + appID + ", " + notificationID);
				return -1;
			}
			
			return Integer.parseInt(headers[2]);
		}
		
		public void readSensorData() {
			int sampleTotal = 0;
			int sampleToRead = 0;
			
			try {
				mSensorHub.sendReturn(mHeader);
				//Log.d(TAG, "sleep " + SensorHub.COMM_DELAY + " ms");
				Thread.sleep(SensorHub.COMM_DELAY);
				
				while (sampleTotal == 0 || sampleToRead > 0) {
					String str = mSensorHub.read();
					String[] strList = str.split("\n");
					int start = 0;
					int length = strList.length; 
					
					if (sampleTotal == 0) {
						int num = parseHeader(strList[0]);
						if (num < 0)
							break;
						if (num == 0) {
							Log.d(TAG, "no data, sleep 10 ms");
							Thread.sleep(10);
							mSensorHub.sendReturn(mHeader);
							//Log.d(TAG, "sleep " + SensorHub.COMM_DELAY + " ms");
							Thread.sleep(SensorHub.COMM_DELAY);
							continue;
						}
						sampleTotal = num;
						sampleToRead = num;
						start = 1;
					}
					
					int end = (length > start + sampleToRead) ? (start + sampleToRead) : length;
					int i;
					for (i = start; i < end; i++) {
						String[] data = strList[i].split(",");
						SensorEvent event = new SensorEvent(3);
						event.values[0] = Integer.parseInt(data[0]);
						event.values[1] = Integer.parseInt(data[1]);
						event.values[2] = Integer.parseInt(data[2]);
						event.sensor = sSensorsMap.get(Sensor.TYPE_ACCELEROMETER);
						mSensorEventList.add(event);
						//Log.d(TAG, "data " + i + ": " + event.values[0] + ", " + event.values[1] + ", " + event.values[2]);
					}
					sampleToRead -= (end - start);
					//Log.d(TAG, "sleep " + SensorHub.COMM_DELAY + " ms");
					Thread.sleep(SensorHub.COMM_DELAY);
				}
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			
			if (!mSensorEventList.isEmpty())
				sendSensorData();
		}
		
		private void sendSensorData() {
			synchronized (sListeners) {
				Log.d(TAG, "start to send back sensor data");
				if (sListeners.isEmpty()) {
					mSensorHub.close();
					mAlarmManager.cancel(reader);
					reader = null;
					return;
				}
				for (ListenerDelegate l: sListeners)
					l.onSensorChangedLocked(mSensorEventList);
			}
			
			mSensorEventList.clear();
			mHeader.increaseNotification();
			synchronized (mSensorHub) {
				mSensorHub.sendBuffer(mHeader, Task.sensorAccel, sampleDelay, numSamples);
				mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + interval, reader);
			}
		}
	}
	
	private class ListenerDelegate {
		
		private SensorEventListener mListener;
		private short sampleDelay;
		private short numSamples;
		private Handler mHandler; // current unsupported
		
		public ListenerDelegate(SensorEventListener listener, short sampleDelay, short numSamples, Handler handler) {
			this.mListener = listener;
			this.sampleDelay = sampleDelay;
			this.numSamples = numSamples;
			
			Looper looper = (handler != null) ? handler.getLooper() : mMainLooper;  
			mHandler = new Handler(looper) {
				@Override
				public void handleMessage(Message msg) {
					SensorEvent event = (SensorEvent) msg.obj;
					mListener.onSensorChanged(event);
				}
			};
		}
		
		public SensorEventListener getListener() {
			return mListener;
		}
		
		public void onSensorChangedLocked(List<SensorEvent> eventList) {
			for (SensorEvent event: eventList) {
				Message msg = Message.obtain();
				msg.what = 0;
				msg.obj = event;
				msg.setAsynchronous(true);
				mHandler.sendMessage(msg);
			}
		}
	}

	/**
	 * @hide
	 */
	public SensorHubManager(Context context) {

		mMainLooper = context.getMainLooper();

		synchronized (sListeners) {
			if (!sInitialized) {
				sSensorHub = new SensorHubDriver(context);
				sInitialized = true;
				
				Sensor sensor = new Sensor("Accelerometer", "AVR XMega", Sensor.TYPE_ACCELEROMETER);
				sSensorsList.add(sensor);
				sSensorsMap.append(Sensor.TYPE_ACCELEROMETER, sensor);
			}
		}
	}
	
	public List<Sensor> getSensorList() {
		return sSensorsList;
	}
	
	public void registerListener(SensorEventListener listener, int sensor, int rate, int numToBuffer) {
		
		if (sensor != Sensor.TYPE_ACCELEROMETER)
			return;
		
		short num = (short)numToBuffer;
		short delay = -1; // ms
		switch (rate) {
		case SensorManager.SENSOR_DELAY_FASTEST:
			delay = 15;
			break;
		case SensorManager.SENSOR_DELAY_GAME:
			delay = 30;
			break;
		case SensorManager.SENSOR_DELAY_UI:
			delay  = 67;
			break;
		case SensorManager.SENSOR_DELAY_NORMAL:
			delay = 200;
			break;
		default:
			delay = (short) rate;
			break;
		}
		
		synchronized (sListeners) {
			ListenerDelegate l = null;
			for (ListenerDelegate delegate: sListeners) {
				if (delegate.getListener() == listener) {
					l = delegate;
					break;
				}
			}
			
			if (l == null) {
				l = new ListenerDelegate(listener, delay, num, null);
				sListeners.add(l);
			}
			
			sSensorHub.startLocked(delay, num);
		}
	}
	
	public void unregisterListener(SensorEventListener listener, int sensor) {
		if (sensor != Sensor.TYPE_ACCELEROMETER)
			return;
		
		synchronized (sListeners) {
			final int size = sListeners.size();
			for (int i = 0; i < size; i++) {
				ListenerDelegate l = sListeners.get(i);
				if (l.getListener() == listener) {
					sListeners.remove(i);
					break;
				}
			}
		}
	}
}
