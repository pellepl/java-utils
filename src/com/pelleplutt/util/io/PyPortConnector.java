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

import java.io.IOException;

import com.pelleplutt.util.Log;

public class PyPortConnector extends PortConnector {
	volatile PySerialPortUARTSocket uartSocketServer;

  public String[] getDevices() {
    try {
      if (uartSocketServer == null) {
        uartSocketServer = (PySerialPortUARTSocket)PySerialPortUARTSocket.createServer(
            "devlister", false, new PySerialPortUARTSocket());
        Log.println("new server " + uartSocketServer);
      }
      return uartSocketServer.getDevices();
    } catch (Throwable t) {
      Log.println("nulling server");
      t.printStackTrace();
      uartSocketServer = null;
      return null;
    }
  }


	public void doConnect(Port portSetting) throws Exception {
    if (uartSocketServer == null) {
      UARTSocket pyUartSocket = new PySerialPortUARTSocket();
      uartSocketServer = (PySerialPortUARTSocket)PySerialPortUARTSocket.createServer(
		    portSetting.portName, true, pyUartSocket);
      Log.println("new server " + uartSocketServer);
    }
		configure(portSetting);
		setInputStream(uartSocketServer.openInputStream());
		setOutputStream(uartSocketServer.openOutputStream());
	}

	public void doDisconnect() throws IOException {
		if (uartSocketServer != null) {
      Log.println("closing " + uartSocketServer);
			uartSocketServer.close();
			UARTSocket.killServer(this.uartSocketServer.server, this.uartSocketServer.serverPort);
			uartSocketServer = null;
		}
	}

	@Override
	protected void doSetTimeout(long timeout) throws IOException {
		if (uartSocketServer != null) {
			uartSocketServer.configureTimeout(timeout != 0 ? (timeout + 100) : 0);
		}
	}

	@Override
	public void setRTSDTR(boolean rtshigh, boolean dtrhigh) throws IOException {
		uartSocketServer.setRTSDTR(rtshigh, dtrhigh);
	}

	@Override
	protected void doConfigure(Port portSetting) throws IOException {
	  uartSocketServer.serialport = portSetting.portName;
		int baud = portSetting.baud;
		int databits = portSetting.databits;
		int stopbits = 0;
		switch (portSetting.stopbits) {
		case Port.STOPBIT_ONE:
			stopbits = UARTSocket.STOPBITS_1;
			break;
		case Port.STOPBIT_TWO:
			stopbits = UARTSocket.STOPBITS_2;
			break;
		}
		int parity = 0;
		switch (portSetting.parity) {
		case Port.PARITY_NO:
			parity = UARTSocket.PARITY_NONE;
			break;
		case Port.PARITY_EVEN:
			parity = UARTSocket.PARITY_EVEN;
			break;
		case Port.PARITY_MARK:
			parity = UARTSocket.PARITY_NONE;
			break;
		case Port.PARITY_ODD:
			parity = UARTSocket.PARITY_ODD;
			break;
		case Port.PARITY_SPACE:
			parity = UARTSocket.PARITY_NONE;
			break;
		}
		uartSocketServer.configure(baud, databits, parity, stopbits, false, false, false,
				timeout != 0 ? (timeout + 1000) : 0);
	}
}
