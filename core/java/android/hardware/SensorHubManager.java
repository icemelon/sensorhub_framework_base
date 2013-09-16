package android.hardware;

import java.util.ArrayList;
import java.util.List;

import android.content.Context;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.ServiceManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.hardware.sensorhub.SensorHub;
import android.hardware.sensorhub.Task;
import android.hardware.sensorhub.TaskHeader;
import android.hardware.usb.IUsbManager;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.util.Log;
import android.util.SparseArray;

// now only support accelerometer 
public class SensorHubManager {

	private static final String TAG = "SensorHubManager";

	private static boolean sInitialized = false;
	private static SensorHub sSensorHub;
	private static SensorHubThread sSensorHubThread;
	private static ArrayList<Sensor> sSensorsList = new ArrayList<Sensor>();
	private static SparseArray<Sensor> sSensorsMap = new SparseArray<Sensor>();
	private static final ArrayList<ListenerDelegate> sListeners = new ArrayList<ListenerDelegate>();
	
	private Looper mMainLooper;
	
	private class SensorHubThread {
		
		private boolean running = false;
		private Thread mThread = null;
		
		private short sampleDelay;
		private short numSamples;
		private TaskHeader mHeader = null;
		
		public SensorHubThread() {
		}
		
		public boolean startLocked(short delay, short num) {
			try {
				if (mThread == null) {
					mThread = new Thread();
					running = false;
					mHeader = new TaskHeader('0'); // appID always '0'
					sampleDelay = delay;
					numSamples = num;
					
					SensorThreadRunnable runnable = new SensorThreadRunnable();
					Thread thread = new Thread(runnable, SensorHubThread.class.getName());
					thread.start();
					synchronized (runnable) {
						while (running == false) {
							runnable.wait();
						}
					}
				} else {
					// TODO
					synchronized (sSensorHub) {
						if (delay < sampleDelay)
							sampleDelay = delay;
						if (num < numSamples)
							numSamples = num;
					}
				}
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			
			return mThread == null ? false : true;
		}
		
		private class SensorThreadRunnable implements Runnable {
			
			// return: num of samples; -1 if appID or notificationID doesn't match
			private int parseHeader(String str) {
				Log.d(TAG, "header: " + str);
				String[] headers = str.split(",");
				char appID = headers[0].charAt(0);
				char notificationID = headers[1].charAt(0);
				
				if (appID != mHeader.appID || notificationID != mHeader.notificationID) {
					Log.d(TAG, "appID or notificationID doesn't match (" + appID + ", " + notificationID + ")");
					return -1;
				}
				
				return Integer.parseInt(headers[2]);
			}
			
			private void readSensorData(List<SensorEvent> sensorEventList) throws InterruptedException {
				int sampleTotal = 0;
				int sampleToRead = 0;
				
				sSensorHub.sendReturn(mHeader);
				//Log.d(TAG, "sleep " + SensorHub.COMM_DELAY + " ms");
				Thread.sleep(SensorHub.COMM_DELAY);
				
				while (sampleTotal == 0 || sampleToRead > 0) {
					String str = sSensorHub.read();
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
							sSensorHub.sendReturn(mHeader);
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
						sensorEventList.add(event);
						//Log.d(TAG, "data " + i + ": " + event.values[0] + ", " + event.values[1] + ", " + event.values[2]);
					}
					sampleToRead -= (end - start);
					//Log.d(TAG, "sleep " + SensorHub.COMM_DELAY + " ms");
					Thread.sleep(SensorHub.COMM_DELAY);
				}
			}

			@Override
			public void run() {
				Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
				List<SensorEvent> sensorEventList = new ArrayList<SensorEvent>();
				if (!sSensorHub.open()) {
					return;
				}
				synchronized (this) {
					running = true;
					this.notify();
				}
				Log.d(TAG, "Pooling thread starts");
				
				try {
					while (true) {
						// because of accessing sampleDelay & numSamples
						synchronized (sSensorHub) {
							sSensorHub.sendBuffer(mHeader, Task.sensorAccel, sampleDelay, numSamples);
						}
						
						long timeToWait = sampleDelay * numSamples;
						Log.d(TAG, "start to sleep " + timeToWait + " ms");
						Thread.sleep(timeToWait);
						
						readSensorData(sensorEventList);
						
						synchronized (sListeners) {
							if (sListeners.isEmpty())
								break;
							if (!sensorEventList.isEmpty()) {
								for (ListenerDelegate l: sListeners)
									l.onSensorChangedLocked(sensorEventList);
								sensorEventList.clear();
							}
						}
						
						mHeader.increaseNotification();
					}
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				
				sSensorHub.close();
				mThread = null;
				Log.d(TAG, "Thread ends");
			}
		}
	}
	
	private class ListenerDelegate {
		
		private SensorEventListener mListener;
		private short sampleDelay;
		private short numSamples;
		private Handler mHandler;
		
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
	public SensorHubManager(Looper looper) {

		mMainLooper = looper;

		synchronized (sListeners) {
			
			if (!sInitialized) {
				IBinder b = ServiceManager.getService(Context.USB_SERVICE);
				UsbManager usbManager = new UsbManager(null, IUsbManager.Stub.asInterface(b));
				sSensorHub = new SensorHub(usbManager);
				sSensorHubThread = new SensorHubThread();
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
			
			sSensorHubThread.startLocked(delay, num);
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
