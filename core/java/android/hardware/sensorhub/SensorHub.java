package android.hardware.sensorhub;

import java.util.ArrayList;

import android.hardware.Sensor;
import android.hardware.usb.UsbManager;
import android.util.Log;

public class SensorHub {
	
	private static final String TAG = "SensorHub";
	public static final int COMM_DELAY = 2; //ms
	
	private FTDriver mSerial; // FTDriver
	
	public SensorHub(UsbManager usbManager) {
		
		// [FTDriver] Create Instance
		Log.d(TAG, "Create new FTDriver");
		mSerial = new FTDriver(usbManager);
		
		// [FTDriver] SetPermissionIntent() before begin()
//		PendingIntent permissionIntent = PendingIntent.getBroadcast(mContext, 0, new Intent(ACTION_USB_PERMISSION), 0);
//		mSerial.setPermissionIntent(permissionIntent);
	}
	
	public synchronized boolean open() {
		
		if (!mSerial.isConnected()) {
			if (mSerial.begin(FTDriver.BAUD115200)) {
				mSerial.writeToLog("Connect to sensor hub");
				Log.d(TAG, "Connect to sensor hub");
			} else {
				Log.d(TAG, "Failed open USB device");
				return false;
			}
		}
		Log.d(TAG, "Serial opens");
	
		return true;
	}
	
	public synchronized void close() {
		mSerial.end();
		Log.d(TAG, "Serial ends");
	}
	
	public synchronized void sendBuffer(TaskHeader header, char sensor, short delay, short num) {
		Log.d(TAG, "inside buffer");
		byte[] buf = Task.generateBufferTask(header, sensor, '0', delay, num);
		mSerial.write(buf);
	}
	
	public synchronized void sendCancel(TaskHeader header) {
		Log.d(TAG, "inside cancel");
		byte[] buf = Task.generateCancelTask(header);
		mSerial.write(buf);
	}
	
	public synchronized void sendReturn(TaskHeader header) {
		Log.d(TAG, "inside return");
		byte[] buf = Task.generateReturnTask(header);
		mSerial.write(buf);
	}
	
	public synchronized String read() {
		
		Log.d(TAG, "inside read");
		
		// [FTDriver] Create Read Buffer
        byte[] rbuffer = new byte[4096]; // 1byte <--slow-- [Transfer Speed] --fast--> 4096 byte

        // [FTDriver] Read from USB Serial
        int len = 0; 
        
        while (len == 0) {
        	len = mSerial.read(rbuffer);
        }
        
        mSerial.writeToLog("length of the string is " + len);
        /** If not, then look at the string **/
        String str = "";
        for (int i = 0; i < len; i++){
        	str += (char) rbuffer[i];
        }
        mSerial.writeToLog("read string " + str);
        
        return str;
	}
	
	public synchronized void log(String str) {
		mSerial.writeToLog(str);
	}
	
}
