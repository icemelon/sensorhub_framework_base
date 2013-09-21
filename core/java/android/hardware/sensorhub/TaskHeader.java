package android.hardware.sensorhub;

public class TaskHeader {
	
	public final char appID;
	public char notificationID;
//	public char taskID;
	
	public TaskHeader(char appID) {
		this.appID = appID;
		this.notificationID = '0';
	}

	public void reset() {
		notificationID = '0';
	}
	
	/*public void setTask(char taskID) {
		this.taskID = taskID;
	}*/
	
	public void increaseNotification() {
		if (notificationID == '9')
			notificationID = '0';
		else
			notificationID ++;
	}
	
	public void convertToByte(byte[] output) {
		output[0] = (byte) appID;
		output[1] = (byte) notificationID;
//		output[2] = (byte) taskID;
	}
}
