package android.hardware.sensorhub;

public final class Task {
	
	//TASK IDs
	public static final char TaskReturn = '0';
	public static final char TaskCancel = '1';
	public static final char TaskBuffer = '2';
	
	public static final char sensorAccel = '0';
	public static final char sensorMicrophone = '1';
	
	Task() {
	}
	
	public static byte[] generateBufferTask(TaskHeader header, char sensorID, char persistent, short sampleDelay, short numSamples) {
		byte[] output = new byte[15];
		header.convertToByte(output);
		output[2] = (byte) Task.TaskBuffer;
		output[3] = (byte) sensorID;
		output[4] = (byte) persistent;
		output[5] = (byte) (48 + sampleDelay / 100000 % 10);
		output[6] = (byte) (48 + sampleDelay / 10000 % 10);
		output[7] = (byte) (48 + sampleDelay / 1000 % 10);
		output[8] = (byte) (48 + sampleDelay / 100 % 10);
		output[9] = (byte) (48 + sampleDelay / 10 % 10);
		output[10] = (byte) (48 + sampleDelay % 10);
		output[11] = (byte) (48 + numSamples / 1000 % 10);
		output[12] = (byte) (48 + numSamples / 100 % 10);
		output[13] = (byte) (48 + numSamples / 10 % 10);
		output[14] = (byte) (48 + numSamples % 10);
		return output;
	}
	
	public static byte[] generateReturnTask(TaskHeader header) {
		byte[] output = new byte[3];
		header.convertToByte(output);
		output[2] = (byte) Task.TaskReturn;
		return output;
	}
	
	public static byte[] generateCancelTask(TaskHeader header) {
		byte[] output = new byte[3];
		header.convertToByte(output);
		output[2] = (byte) Task.TaskCancel;
		return output;
	}
	
}
