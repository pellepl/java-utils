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
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.Arrays;

public class LinuxPortConnector extends PortConnector {
	LinuxSerialPortUARTSocket port;

	public String[] getDevices() {
		File f = new File("/dev/");
		String[] devices = f.list(new FilenameFilter() {
			@Override
			public boolean accept(File dir, String name) {
				return (name.startsWith("ttyUSB")||name.startsWith("ttyACM"));
			}
		});
		if (devices != null) {
			Arrays.sort(devices);
		}
		return devices;
	}

	public void doConnect(Port portSetting) throws Exception {
		UARTSocket linuxUartSocket = new LinuxSerialPortUARTSocket();
		port = (LinuxSerialPortUARTSocket)LinuxSerialPortUARTSocket.createServer(
		    portSetting.portName, true, linuxUartSocket);
		configure(portSetting);
		setInputStream(port.openInputStream());
		setOutputStream(port.openOutputStream());
	}

	public void doDisconnect() throws IOException {
		if (port != null) {
			port.close();
		}
	}

	@Override
	protected void doSetTimeout(long timeout) throws IOException {
		if (port != null) {
			port.configureTimeout(timeout != 0 ? (timeout + 100) : 0);
		}
	}

	@Override
	public void setRTSDTR(boolean rtshigh, boolean dtrhigh) throws IOException {
		port.setRTSDTR(rtshigh, dtrhigh);
	}

	@Override
	protected void doConfigure(Port portSetting) throws IOException {
		int baud = 0;
		switch (portSetting.baud) {
		case Port.BAUD_110:
			baud = 110;
			break;
		case Port.BAUD_300:
			baud = 300;
			break;
		case Port.BAUD_600:
			baud = 600;
			break;
		case Port.BAUD_1200:
			baud = 1200;
			break;
		case Port.BAUD_2400:
			baud = 2400;
			break;
		case Port.BAUD_4800:
			baud = 4800;
			break;
		case Port.BAUD_9600:
			baud = 9600;
			break;
		case Port.BAUD_14400:
			baud = 14400;
			break;
		case Port.BAUD_19200:
			baud = 19200;
			break;
		case Port.BAUD_38400:
			baud = 38400;
			break;
		case Port.BAUD_57600:
			baud = 57600;
			break;
		case Port.BAUD_115200:
			baud = 115200;
			break;
		case Port.BAUD_128000:
			baud = 128000;
			break;
		case Port.BAUD_230400:
			baud = 230400;
			break;
		case Port.BAUD_256000:
			baud = 256000;
			break;
		case Port.BAUD_460800:
			baud = 460800;
			break;
		case Port.BAUD_921600:
			baud = 921600;
			break;
		}
		int databits = portSetting.databits;
		int stopbits = 0;
		switch (portSetting.stopbits) {
		case Port.STOPBIT_ONE:
			stopbits = LinuxSerialPortUARTSocket.STOPBITS_1;
			break;
		case Port.STOPBIT_TWO:
			stopbits = LinuxSerialPortUARTSocket.STOPBITS_2;
			break;
		}
		int parity = 0;
		switch (portSetting.parity) {
		case Port.PARITY_NO:
			parity = LinuxSerialPortUARTSocket.PARITY_NONE;
			break;
		case Port.PARITY_EVEN:
			parity = LinuxSerialPortUARTSocket.PARITY_EVEN;
			break;
		case Port.PARITY_MARK:
			parity = LinuxSerialPortUARTSocket.PARITY_NONE;
			break;
		case Port.PARITY_ODD:
			parity = LinuxSerialPortUARTSocket.PARITY_ODD;
			break;
		case Port.PARITY_SPACE:
			parity = LinuxSerialPortUARTSocket.PARITY_NONE;
			break;
		}
		port.configure(baud, databits, parity, stopbits, portSetting.xonxoff, portSetting.rtscts, portSetting.dsrdtr,
				timeout != 0 ? (timeout + 1000) : 0);
	}

  @Override
  public void setRTS(boolean hi) throws IOException {
    port.setRTS(hi);
  }

  @Override
  public void setDTR(boolean hi) throws IOException {
    port.setDTR(hi);
  }

  @Override
  public int getCTS() throws IOException {
    return -1;
  }

  @Override
  public int getDSR() throws IOException {
    return -1;
  }

  @Override
  public int getRI() throws IOException {
    return -1;
  }

  @Override
  public int getCD() throws IOException {
    return -1;
  }
}
