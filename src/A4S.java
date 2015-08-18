// A4S.java
// Copyright (c) MIT Media Laboratory, 2013
//
// Helper app that runs an HTTP server allowing Scratch to communicate with
// Arduino boards running the Firmata firmware (StandardFirmata example).
//
// Note: the Scratch extension mechanism is a work-in-progress and still
// evolving. This code will need updates to work with future version of Scratch.
//
// Based on HTTPExtensionExample by John Maloney. Adapted for Arduino and
// Firmata by David Mellis.
//
// Inspired by Tom Lauwers Finch/Hummingbird server and Conner Hudson's Snap extensions.

import java.io.*;
import java.net.*;
import java.util.*;

import gnu.io.CommPort;
import gnu.io.CommPortIdentifier;
import gnu.io.SerialPort;
import gnu.io.SerialPortEvent;
import gnu.io.SerialPortEventListener;

import org.firmata.Firmata;

public class A4S {
	private static int[] firmataPinModes = { Firmata.INPUT, Firmata.OUTPUT,
			Firmata.ANALOG, Firmata.PWM, Firmata.SERVO };
	private static String[] a4sPinModes = { "Digital%20Input",
			"Digital%20Output", "Analog%20Input", "Analog%20Output%28PWM%29",
			"Servo" };

	static final int A0 = 14;
	static final int A1 = 15;
	static final int A2 = 16;
	static final int A3 = 17;
	static final int A4 = 18;
	static final int A5 = 19;

	private static final int PORT = 12345; // set to your extension's port
											// number
	// private static int volume = 8; // replace with your extension's data, if
	// any

	private static InputStream sockIn;
	private static OutputStream sockOut;

	private static SerialPort serialPort;
	private static Firmata arduino;

	private static SerialReader reader;
	private static ServerSocket serverSock;

	public static class SerialReader implements SerialPortEventListener {
		public void serialEvent(SerialPortEvent e) {
			try {
				while (serialPort.getInputStream().available() > 0) {
					int n = serialPort.getInputStream().read();
					// System.out.println(">" + n);
					arduino.processInput(n);
				}
			} catch (IOException err) {
				System.err.println(err);
			}
		}
	}

	public static class FirmataWriter implements Firmata.Writer {
		public void write(int val) {
			try {
				// System.out.println("<" + val);
				serialPort.getOutputStream().write(val);
			} catch (IOException err) {
				System.err.println(err);
			}
		}
	}

	public static void main(String[] args) throws IOException {
		// 1.初始化串口,监听串口
		try {
			if (args.length < 1) {
				System.err
						.println("Please specify serial port on command line.");
				return;
			}
			CommPortIdentifier portIdentifier = CommPortIdentifier
					.getPortIdentifier(args[0]);
			CommPort commPort = portIdentifier.open("sogworks.cn", 2000);
			if (commPort instanceof SerialPort) {
				serialPort = (SerialPort) commPort;
				serialPort.setSerialPortParams(115200, SerialPort.DATABITS_8,
						SerialPort.STOPBITS_1, SerialPort.PARITY_NONE);

				arduino = new Firmata(new FirmataWriter());
				reader = new SerialReader();

				serialPort.addEventListener(reader);
				serialPort.notifyOnDataAvailable(true);

				try {
					Thread.sleep(3000); // let bootloader timeout
				} catch (InterruptedException e) {
				}

				arduino.init();
			} else {
				System.out
						.println("Error: Only serial ports are handled by this example.");
				return;
			}
		} catch (Exception e) {
			System.err.println(e);
			return;
		}

		InetAddress addr = InetAddress.getLocalHost();
		System.out.println("HTTPExtensionExample helper app started on "
				+ addr.toString());

		serverSock = new ServerSocket(PORT);
		while (true) {
			// 只能当connected的时候才能往下走
			Socket sock = serverSock.accept();
			sockIn = sock.getInputStream();
			sockOut = sock.getOutputStream();
			try {
				handleRequest();
			} catch (Exception e) {
				e.printStackTrace(System.err);
				sendResponse("unknown server error");
			}
			sock.close();
		}
	}

	private static void handleRequest() throws IOException {
		String httpBuf = "";
		int i;

		// read data until the first HTTP header line is complete (i.e. a '\n'
		// is seen)
		while ((i = httpBuf.indexOf('\n')) < 0) {
			byte[] buf = new byte[5000];
			int bytes_read = sockIn.read(buf, 0, buf.length);
			if (bytes_read < 0) {
				System.out.println("Socket closed; no HTTP header.");
				return;
			}
			httpBuf += new String(Arrays.copyOf(buf, bytes_read));
		}

		String header = httpBuf.substring(0, i);
		if (header.indexOf("GET ") != 0) {
			System.out.println("This server only handles HTTP GET requests.");
			return;
		}
		i = header.indexOf("HTTP/1");
		if (i < 0) {
			System.out.println("Bad HTTP GET header.");
			return;
		}
		header = header.substring(5, i - 1);
		if (header.equals("favicon.ico"))
			return; // igore browser favicon.ico requests
		else if (header.equals("crossdomain.xml"))
			sendPolicyFile();
		else if (header.length() == 0)
			doHelp();
		else
			doCommand(header);
	}

	private static void sendPolicyFile() {
		// Send a Flash null-teriminated cross-domain policy file.
		String policyFile = "<cross-domain-policy>\n"
				+ "  <allow-access-from domain=\"*\" to-ports=\"" + PORT
				+ "\"/>\n" + "</cross-domain-policy>\n\0";
		sendResponse(policyFile);
	}

	private static void sendResponse(String s) {
		String crlf = "\r\n";
		String httpResponse = "HTTP/1.1 200 OK" + crlf;
		httpResponse += "Content-Type: text/html; charset=ISO-8859-1" + crlf;
		httpResponse += "Access-Control-Allow-Origin: *" + crlf;
		httpResponse += crlf;
		httpResponse += s + crlf;
		try {
			byte[] outBuf = httpResponse.getBytes();
			sockOut.write(outBuf, 0, outBuf.length);
		} catch (Exception ignored) {
			System.out.println("sendResponse failed");
		}
	}

	private static void doCommand(String cmdAndArgs)
			throws UnsupportedEncodingException {
		// Essential: handle commands understood by this server
		String response = "okay";
		cmdAndArgs = URLDecoder.decode(cmdAndArgs, "UTF-8");
		String[] parts = cmdAndArgs.split("/");
		String cmd = parts[0];

		// System.out.print(cmdAndArgs);
		// GET /pinMode/2/Digital%20Input HTTP/1.1,
		// 其中part[0]=pinMode;part[1]=2;part[3]=Digital%20Input;
		if (cmd.equals("pinOutput")) {
			arduino.pinMode(Integer.parseInt(parts[1]), Firmata.OUTPUT);
		} else if (cmd.equals("pinInput")) {
			arduino.pinMode(Integer.parseInt(parts[1]), Firmata.INPUT);
		} else if (cmd.equals("pinHigh")) {
			arduino.digitalWrite(Integer.parseInt(parts[1]), Firmata.HIGH);
		} else if (cmd.equals("pinLow")) {
			arduino.digitalWrite(Integer.parseInt(parts[1]), Firmata.LOW);
		} else if (cmd.equals("pinMode")) {
			arduino.pinMode(Integer.parseInt(parts[1]),
					getFirmataPinMode(parts[2]));
		} else if (cmd.equals("digitalWrite")) {
			arduino.digitalWrite(Integer.parseInt(parts[1]),
					"high".equals(parts[2]) ? Firmata.HIGH : Firmata.LOW);
		} else if (cmd.equals("analogWrite")) {
			arduino.analogWrite(Integer.parseInt(parts[1]),
					Integer.parseInt(parts[2]));
		} else if (cmd.equals("servoWrite")) {
			arduino.servoWrite(Integer.parseInt(parts[1]),
					Integer.parseInt(parts[2]));
		} else if (cmd.equals("poll")) {
			// set response to a collection of sensor, value pairs, one pair per
			// line
			// in this example there is only one sensor, "volume"
			// response = "volume " + volume + "\n";
			response = "";
			for (int i = 2; i <= 13; i++) {
				response += "digitalRead/"
						+ i
						+ " "
						+ (arduino.digitalRead(i) == Firmata.HIGH ? "true"
								: "false") + "\n";
			}
			for (int i = 0; i <= 5; i++) {
				response += "analogRead/" + i + " " + (arduino.analogRead(i))
						+ "\n";
			}
			for (int i = 0; i < 255; i++) {
				response += "i2cRead"+"/" + i + " " + 0 + "\n";
				if (arduino.mapS_I2c.containsKey(Integer.toString(i))) {
					response += "i2cRead"+"/" + arduino.mapS_I2c.get(Integer.toString(i)).address+ " " + arduino.mapS_I2c.get(Integer.toString(i)).data[0] + "\n";
				}
			}
		} else if (cmd.equals("servo")) {
			for (int i = 0; i < parts.length; i++) {
				System.out.println(parts[i]);
			}
			arduino.servoWrite(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
		} else if (cmd.equals("i2cRead")) {
			System.out.println("i2cRead");
			//arduino.i2cRead(0x23,32,2);//改成0x23试试
			arduino.i2cRead(Integer.parseInt(parts[1]),Integer.parseInt(parts[2]),Integer.parseInt(parts[3]));//改成0x23试试
		}else if (cmd.equals("sendString")) {
			System.out.println("sendString");
			arduino.sendString("111");
		}else if (cmd.equals("queryAnalogMapping")) {
			System.out.println("queryAnalogMapping");
			arduino.queryAnalogMapping();
		}else {
			response = "unknown command: " + cmd;
		}
		//System.out.println(" " + response);
		sendResponse(response);
	}

	private static int getFirmataPinMode(String a4sPinMode) {
		int idx = 0;
		while (idx < a4sPinModes.length - 1
				&& (!a4sPinMode.equals(a4sPinModes[idx])))
			idx++;
		if (!a4sPinMode.equals(a4sPinModes[idx]))
			idx = 0;
		return firmataPinModes[idx];
	}

	private static void doHelp() {
		// Optional: return a list of commands understood by this server
		String help = "HTTP Extension Example Server<br><br>";
		sendResponse(help);
	}

}