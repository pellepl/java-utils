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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.util.ArrayList;
import java.util.List;

import com.pelleplutt.util.AppSystem;
import com.pelleplutt.util.Log;

/**
 * Manages the opening and closing of a serial port, returning inputstream and
 * outputstream to the resource. Also takes care of optional watchdog feature
 * would there be an I/O operation that times out. Designed for single thread
 * access to the port, i.e. having multiple threads calling read or write
 * simultaneously will cause weird effects. Different threads in different
 * points in time is ok.
 * 
 * @author petera
 */
public abstract class PortConnector {
  PushbackInputStream inputStream;
  OutputStream outputStream;
  long timeout = 0;
  long lastActivity;
  boolean connected;
  Watchdog watchdog;
  Monitor watchdogMonitor;

  /**
   * Returns the portconnector instance for this context. OS dependent.
   * 
   * @return
   */
  public static PortConnector getPortConnector() {
    // TODO PETER check if there is python3 and pyserial installed, else use native
    PortConnector pc = null;
    String os = System.getProperty("os.name");
    os = "alwaysusepython";
    if (System.getProperty(PySerialPortUARTSocket.PROP_PATH_BIN) == null) {
      System.setProperty(PySerialPortUARTSocket.PROP_PATH_BIN, System
          .getProperty("user.home")
          + File.separatorChar
          + System.getProperty(UARTSocket.PROP_PATH_APPNAME, UARTSocket.PATH_DEFAULT_APPNAME)
          + File.separatorChar + "py" + File.separatorChar + "pyuartsocket.py");
    }
    if (os.contains("Windows")) {
      if (System.getProperty(WinSerialPortUARTSocket.PROP_PATH_BIN) == null) {
        System.setProperty(WinSerialPortUARTSocket.PROP_PATH_BIN, System
            .getProperty("user.home")
            + File.separatorChar
            + System.getProperty(UARTSocket.PROP_PATH_APPNAME, UARTSocket.PATH_DEFAULT_APPNAME)
            + File.separatorChar + "bin");
      }
      if (System.getProperty(WinSerialPortUARTSocket.PROP_NAME) == null) {
        System.setProperty(WinSerialPortUARTSocket.PROP_NAME, "uartsocket");
      }
      pc = new WinPortConnector();
      // pc = new SocketPortConnector();
    } else if (os.contains("Linux")) {
      if (System.getProperty(LinuxSerialPortUARTSocket.PROP_PATH_BIN) == null) {
        System.setProperty(LinuxSerialPortUARTSocket.PROP_PATH_BIN, System
            .getProperty("user.home")
            + File.separatorChar
            + System.getProperty(UARTSocket.PROP_PATH_APPNAME, UARTSocket.PATH_DEFAULT_APPNAME)
            + File.separatorChar + "bin");
      }
      if (System.getProperty(LinuxSerialPortUARTSocket.PROP_PATH_SRC) == null) {
        System.setProperty(LinuxSerialPortUARTSocket.PROP_PATH_SRC, System
            .getProperty("user.home")
            + File.separatorChar
            + System.getProperty(UARTSocket.PROP_PATH_APPNAME, UARTSocket.PATH_DEFAULT_APPNAME)
            + File.separatorChar + "src");
      }
      if (System.getProperty(LinuxSerialPortUARTSocket.PROP_NAME) == null) {
        System.setProperty(LinuxSerialPortUARTSocket.PROP_NAME, "uartsocket");
      }
      pc = new LinuxPortConnector();
    } else {
      pc = new PyPortConnector();
    }
    return pc;
  }

  protected abstract void doConfigure(Port portSetting) throws IOException;

  protected abstract void doConnect(Port portSetting) throws Exception;

  protected abstract void doDisconnect() throws IOException;

  protected abstract void doSetTimeout(long timeout) throws IOException;

  /**
   * Returns a list of available devices
   * 
   * @return list of devices or null
   */
  public abstract String[] getDevices();

  /**
   * Sets RTS and DTR to given levels
   * 
   * @param rtshigh
   * @param dtrhigh
   * @throws IOException
   */
  public abstract void setRTSDTR(boolean rtshigh, boolean dtrhigh)
      throws IOException;

  /**
   * Sets timeout for streams given by this connection
   * 
   * @param timeout
   * @throws IOException
   */
  public void setTimeout(long timeout) throws IOException {
    doSetTimeout(timeout);
    if (watchdog != null) {
      synchronized (watchdogMonitor) {
        this.timeout = timeout;
        watchdogMonitor.notify();
      }
    } else {
      this.timeout = timeout;
    }
  }

  /**
   * Connects using given portsetting
   * 
   * @param portSetting
   * @throws Exception
   */
  public void connect(Port portSetting) throws Exception {
    doConnect(portSetting);
    // create new monitor for this new watchdog thread so the eventual old
    // one can RIP
    // watchdogMonitor = new Monitor();
    // synchronized (watchdogMonitor) {
    // watchdogMonitor.running = true;
    // watchdog = new Watchdog(watchdogMonitor);
    // new Thread(watchdog, "portwatchdog").start();
    // }
    try {
      setRTSDTR(true, true);
    } catch (Exception e) {
      Log.printStackTrace(e);
    }

  }

  public void configure(Port portSetting) throws IOException {
    doConfigure(portSetting);
  }

  /**
   * Disconnects and closes streams
   * 
   * @throws IOException
   */
  public void disconnect() throws IOException {
    doDisconnect();
    // synchronized (watchdogMonitor) {
    // watchdogMonitor.running = false;
    // watchdogMonitor.notifyAll();
    // }
    AppSystem.closeSilently(inputStream);
    AppSystem.closeSilently(outputStream);
  }

  /**
   * Disconnects and closes streams silently
   */
  public void disconnectSilently() {
    try {
      doDisconnect();
    } catch (IOException e) {
    }
    // synchronized (watchdogMonitor) {
    // watchdogMonitor.running = false;
    // watchdogMonitor.notifyAll();
    // }
    AppSystem.closeSilently(inputStream);
    AppSystem.closeSilently(outputStream);
  }

  /**
   * Returns the inputstream from this connection
   * 
   * @return
   */
  public PushbackInputStream getInputStream() {
    return inputStream;
  }

  protected void setInputStream(InputStream inputStream) {
    this.inputStream = new PushbackInputStream(
    /* new WatchedInputStream */(inputStream), 1);
  }

  /**
   * Returns the outputstream to this connection
   * 
   * @return
   */
  public OutputStream getOutputStream() {
    return outputStream;
  }

  protected void setOutputStream(OutputStream outputStream) {
    this.outputStream = /* new WatchedOutputStream */(outputStream);
  }

  /**
   * Watchdog runner
   */
  class Watchdog implements Runnable {
    Monitor monitor;
    long timeoutSetting;
    boolean active;
    List<Thread> waiters = new ArrayList<Thread>();

    public Watchdog(Monitor watchdogMonitor) {
      this.monitor = watchdogMonitor;
      this.timeoutSetting = PortConnector.this.timeout;
    }

    public void run() {
      synchronized (monitor) {
        while (monitor.running) {
          try {
            long then = System.currentTimeMillis();
            if (!active || timeoutSetting == 0) {
              monitor.wait();
            } else {
              monitor.wait(timeoutSetting);
            }
            long now = System.currentTimeMillis();
            if (Thread.interrupted()) {
              throw new InterruptedException();
            } else if (!monitor.running) {
              // signal because of disconnection, loop will exit
            } else if (timeoutSetting != PortConnector.this.timeout) {
              // change in timeout, simply set new and rerun
              timeoutSetting = PortConnector.this.timeout;
            } else if (active && timeoutSetting > 0
                && now - then >= timeoutSetting - 10) {
              // timed out!
              for (Thread waiter : waiters) {
                // StackTraceElement[] stes = waiter.getStackTrace();
                // for (StackTraceElement stackTraceElement : stes) {
                // System.out.println(stackTraceElement);
                // }
                waiter.interrupt();
              }
            } else {
              // spin
            }
          } catch (InterruptedException ie) {
            // TODO:What to do?
          }
        }
        waiters.clear();
      }
    }

    public void start() {
      synchronized (monitor) {
        active = true;
        waiters.add(Thread.currentThread());
        monitor.notify();
      }
    }

    public void stop() {
      synchronized (monitor) {
        active = false;
        waiters.remove(Thread.currentThread());
        monitor.notify();
      }
    }
  }

  /**
   * Inputstream snooper
   * 
   * @author petera
   */
  class WatchedInputStream extends InputStream {
    InputStream delegate;

    public int available() throws IOException {
      watchdog.start();
      int a = delegate.available();
      watchdog.stop();
      return a;
    }

    public void close() throws IOException {
      delegate.close();
    }

    public boolean equals(Object obj) {
      return delegate.equals(obj);
    }

    public int hashCode() {
      return delegate.hashCode();
    }

    public void mark(int readlimit) {
      watchdog.start();
      delegate.mark(readlimit);
      watchdog.stop();
    }

    public boolean markSupported() {
      return delegate.markSupported();
    }

    public int read() throws IOException {
      watchdog.start();
      int r = delegate.read();
      watchdog.stop();
      return r;
    }

    public int read(byte[] b, int off, int len) throws IOException {
      watchdog.start();
      int n = delegate.read(b, off, len);
      watchdog.stop();
      return n;
    }

    public int read(byte[] b) throws IOException {
      watchdog.start();
      int n = delegate.read(b);
      watchdog.stop();
      return n;
    }

    public void reset() throws IOException {
      delegate.reset();
    }

    public long skip(long n) throws IOException {
      watchdog.start();
      long nn = delegate.skip(n);
      watchdog.stop();
      return nn;
    }

    public String toString() {
      return delegate.toString();
    }

    public WatchedInputStream(InputStream delegate) {
      this.delegate = delegate;
    }
  }

  /**
   * Outputstream snooper
   * 
   * @author petera
   */
  class WatchedOutputStream extends OutputStream {
    OutputStream delegate;

    public void close() throws IOException {
      delegate.close();
    }

    public boolean equals(Object obj) {
      return delegate.equals(obj);
    }

    public void flush() throws IOException {
      watchdog.start();
      delegate.flush();
      watchdog.stop();
    }

    public int hashCode() {
      return delegate.hashCode();
    }

    public String toString() {
      return delegate.toString();
    }

    public void write(byte[] arg0, int arg1, int arg2) throws IOException {
      watchdog.start();
      delegate.write(arg0, arg1, arg2);
      watchdog.stop();
    }

    public void write(byte[] b) throws IOException {
      watchdog.start();
      delegate.write(b);
      watchdog.stop();
    }

    public void write(int arg0) throws IOException {
      watchdog.start();
      delegate.write(arg0);
      watchdog.stop();
    }

    public WatchedOutputStream(OutputStream delegate) {
      this.delegate = delegate;
    }
  }

  class Monitor {
    boolean running;
  }
}
