/*
 Copyright (c) 2012, Peter Andersson pelleplutt1976@gmail.com

 Permission to use, copy, modify, and/or distribute this software for any
 purpose with or without fee is hereby granted, provided that the above
 copyright notice and this permission notice appear in all copies.

 THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH
 REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY
 AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT,
 INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM
 LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR
 OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR
 PERFORMANCE OF THIS SOFTWARE.
*/
package com.pelleplutt.util.io;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import com.pelleplutt.util.AppSystem;
import com.pelleplutt.util.Log;

public abstract class UARTSocket {
  public static int serverPort_g = 10000 + (int)(Math.random() * 10000);
  static Process serverProcess;
  static boolean shutdownHookAdded = false;
  
  public static final int PARITY_NONE = 0;
  public static final int PARITY_EVEN = 1;
  public static final int PARITY_ODD = 2;

  public static final int STOPBITS_1 = 1;
  public static final int STOPBITS_2 = 2;

  protected static final int RESULT_UNTIL_OK = -1;
  public static final String PROP_PATH_APPNAME = "portconnector.path";
  public static final String PATH_DEFAULT_APPNAME = ".uartsocket";
  
  public static int globalStarts = 0;
  public static int globalKills = 0;
  
  String serialport;
  volatile boolean isOpen = false;
  volatile boolean dataClientConnected = false;
  Socket sCtrl, sData;
  InputStream ctrlInStr;
  BufferedReader ctrlIn;
  DataOutputStream ctrlOut;
  BufferedReader dataCIn;
  DataOutputStream dataCOut;
  InputStream dataIn;
  OutputStream dataOut;
  
  String server = "localhost";
  int serverPort = serverPort_g;
  
  public int starts = 0;
  public int kills = 0;

  
  public static UARTSocket createServer(String serialport, boolean connectDataClient, UARTSocket uartsocket) throws IOException {
    Log.println("portname:" + serialport + " uartsocket:" + uartsocket.serialport + "@" + uartsocket.server + ":" + uartsocket.serverPort);
    serialport = uartsocket.preprocessPortName(serialport);
    uartsocket.serialport = serialport;
    
    // check if serial port is on url form, e.g. ttyUSB4@192.168.0.4:8000
    int portIx = serialport.indexOf(':');
    if (portIx > 0) {
      int serverPort = Integer.parseInt(serialport.substring(portIx+1));
      uartsocket.serverPort = serverPort;
      serialport = serialport.substring(0, portIx);
    }
    int serverNameIx = serialport.indexOf('@');
    if (serverNameIx > 0) {
      uartsocket.serialport = serialport.substring(0,serverNameIx);
      uartsocket.server = serialport.substring(serverNameIx+1);
    }
    
    try {
      boolean ok = false;
      int tries = 5;
      while (tries-- > 0 && !ok) {
        try {
          startServer(uartsocket);
          uartsocket.connectCtrlClient();
          if (connectDataClient) uartsocket.connectDataClient();
          ok = true;
        } catch (ConnectException e) {
          // port probably busy, server not opened
          Log.println("could not connect to port " + uartsocket.server + ":" + uartsocket.serverPort + " : " + e.getMessage());
          try {
            uartsocket.close();
          } catch (Throwable ignore) {}
          try {
            killServer(uartsocket.server, uartsocket.serverPort);
          } catch (Throwable ignore) {}
          uartsocket.serverPort = serverPort_g++;
        }
      }
      uartsocket.isOpen = ok;
    } catch (UnknownHostException e) {
      throw new IOException(e);
    }

    return uartsocket;
  }
  
  String preprocessPortName(String portname) {
    return portname;
  }
  
  void connectCtrlClient() throws UnknownHostException, IOException {
    // open control channel socket
    Log.println("open ctrl client against server " + server + ":" + serverPort);
    sCtrl = new Socket(server, serverPort);
    ctrlInStr = sCtrl.getInputStream();
    ctrlIn = new BufferedReader(new InputStreamReader(ctrlInStr));
    ctrlOut = new DataOutputStream(sCtrl.getOutputStream());
  }
  
  void connectDataClient() throws IOException {
    String[] res;
    Log.println("open data client against server " + server + ":" + serverPort + ", " + serialport);
    // open device
    res = controlCommand(true, "O " + serialport, 0);
    // get control channel index
    res = controlCommand(true, "I", 1);
    int ctrlIndex = Integer.parseInt(res[0]);
    Log.println("serial " + serialport + " is ctrl client index " + ctrlIndex);
    
    // open data channel socket
    sData = new Socket(server, serverPort);
    //sData.setSendBufferSize(128);
    //sData.setReceiveBufferSize(128);
    dataIn = sData.getInputStream();
    dataOut = sData.getOutputStream();
    dataCIn = new BufferedReader(new InputStreamReader(dataIn));
    dataCOut = new DataOutputStream(dataOut);
    // attach channel to control channel, make data channel
    res = controlCommand(false, "A " + ctrlIndex, 0);
    dataClientConnected = true;
  }

  public void configure(int baud, int databits, int parity, int stopbits,
      boolean hardwareHandshake, boolean xonxoff, boolean modemControl, long timeout)
      throws IOException {
    if (!dataClientConnected) connectDataClient();
    sData.setSoTimeout((int)timeout + ((timeout > 0) ? 100 : 0));
    timeout /= 100;
    String command = "U"
      + " B" + baud 
      + " D" + databits
      + " S" + stopbits
      + " P" + (parity == PARITY_NONE ? 'n' : (parity == PARITY_EVEN ? 'e' : 'o'))
      + " T" + timeout
      + " M" + (timeout == 0 ? '1' : '0');
    controlCommand(true, command, 0);
  }
  
  public String[] getDevices() throws IOException {
    Log.println("getting devices @ " + server + ":" + serverPort);
    return controlCommand(true, "L", RESULT_UNTIL_OK);
  }
  

  public void configureTimeout(long timeout)
      throws IOException {
    sData.setSoTimeout((int)timeout + ((timeout > 0) ? 100 : 0));
    controlCommand(true, "U T" + timeout
      + " M" + (timeout == 0 ? '1' : '0'), 0);
  }
  
  public void setRTSDTR(boolean rtshigh, boolean dtrhigh) throws IOException {
    controlCommand(true, "U r" + (rtshigh ? '1' : '0')
        + " d" + (dtrhigh ? '1' : '0'), 0);
  }

  String[] controlCommand(boolean ctrl, String s, int result) throws IOException {
    DataOutputStream out = ctrl ? ctrlOut : dataCOut;
    out.writeBytes(s);
    out.write('\n');
    out.flush();
    String[] res;
    if (result != RESULT_UNTIL_OK) {
      res = new String[result];
      for (int i = 0; i < result; i++) {
        res[i] = controlRead(ctrl);
      }
      String q = controlRead(ctrl);
      if ((q == null) || !(q.equals("OK"))) {
        throw new IOException("Expected OK but got " + q); 
      }
    } else {
      String q;
      List<String> l = new ArrayList<String>();
      while (!((q = controlRead(ctrl)).equals("OK"))) {
        l.add(q);
      }
      res = (String[])l.toArray(new String[l.size()]);
    }
    return res;
  }
  
  String controlRead(boolean ctrl) throws IOException {
    String s = ctrl ? ctrlIn.readLine() : dataCIn.readLine();
    if ((s == null) || s.startsWith("ERROR")) {
      throw new IOException("Read failed, read: " + s);
    }
    return s;
  }
  

  public InputStream openInputStream() throws IOException {
    return dataIn;
  }

  public OutputStream openOutputStream() throws IOException {
    return dataOut;
  }

  public void close() throws IOException {
    isOpen = false;
    try {
      String[] res = controlCommand(true, "I", 1);
      int ctrlIndex = Integer.parseInt(res[0]);
      Log.println("closing ctrl client index " + ctrlIndex);
    } catch (Throwable ignore) {}
    try {
      controlCommand(true, "C", 0);
    } catch (Throwable ignore) {}
    AppSystem.closeSilently(dataIn);
    AppSystem.closeSilently(dataOut);
    AppSystem.closeSilently(ctrlInStr);
    AppSystem.closeSilently(ctrlOut);
    if (sCtrl != null) sCtrl.close();
    dataClientConnected = false;
    if (sData != null) sData.close();
  }
  
  protected boolean isOpen() {
    return isOpen;
  }

  public void dispose() {
    kills++;
    killServer(this.server, this.serverPort);
  }
  
  protected static void startServer(UARTSocket uartSocket) {
    final int serverPort = uartSocket.serverPort;
    Log.println("uartsocket:" + uartSocket.serialport + "@" + uartSocket.server + ":" + uartSocket.serverPort);
    if (!shutdownHookAdded) {
      Log.println("adding shutdowhook for " + uartSocket.serverPort);
      Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
        public void run() {
          killServer(serverPort);
        }
      }));
      shutdownHookAdded = true;
    }
    try {
      uartSocket.checkBinary(uartSocket.getBinFile(), 
          uartSocket.getVersionFile(), 
          uartSocket.getVersion());
      if (serverProcess == null || !validateRunningProcess(serverProcess)) {
        String cmd = uartSocket.getExecCommand(serverPort);
        Log.println("starting server at " + serverPort + " (" + cmd + ")");
        uartSocket.preExec();
        serverProcess = Runtime.getRuntime().exec(cmd);
        uartSocket.postExec();
        validateRunningProcess(serverProcess);
        Log.println("server start validated " + serverPort + " (" + cmd + ")");
        globalStarts++;
        uartSocket.starts++;
      } else {
        Log.println("server already started");
      }
    } catch (IOException ignore) {
    } catch (InterruptedException ignore) {
    }
  }

  protected void preExec() {
  }
  protected void postExec() {
  }
  protected String getExecCommand(int serverPort) {
    return getBinFile().getAbsolutePath() + " " + serverPort;
  }

  static boolean validateRunningProcess(Process p) {
    try {
      int e = p.exitValue(); // throws ex if running
      Log.println("process ended: exit code " + e);
      return false;
    } catch (IllegalThreadStateException itse) {
      // it is running 
      return true;
    }
  }
  static void killServer(int serverPort) {
    killServer("localhost", serverPort);
  }
  static void killServer(String server, int serverPort) {
    globalKills++;
    Log.println("server kill @ " + server + ":" + serverPort);
    Socket sCtrl = null;
    try {
      try {
        //Log.println("issuing server close");
        sCtrl = new Socket(server, serverPort);
        OutputStream out = sCtrl.getOutputStream();
        out.write("X\n".getBytes());
        out.flush();
        out.close();
      } catch (UnknownHostException ignore) {
      } catch (IOException ignore) {}
      if (serverProcess != null) {
        //Log.println("give server some time to die");
        AppSystem.sleep(400);
        if (validateRunningProcess(serverProcess)) {
          //Log.println("server not yet closed, killing");
          serverProcess.destroy();
          AppSystem.sleep(100);
        }
        //Log.println("server dead: " + !validateRunningProcess(serverProcess));
      }
    } finally {
      if (sCtrl != null) {
        try {
          sCtrl.close();
        } catch (Throwable ignore) {}
      }
    }
    if (serverProcess != null) {
      serverProcess.destroy();
      serverProcess = null;
    }
  }
  
  abstract void checkBinary(File exe, File verFile, int ver) throws IOException, InterruptedException;

  abstract File getBinFile();
  abstract File getVersionFile();
  abstract int getVersion();
}
