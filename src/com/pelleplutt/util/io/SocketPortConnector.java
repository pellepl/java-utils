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
package com.pelleplutt.io;

import java.io.IOException;
import java.net.Socket;

public class SocketPortConnector extends PortConnector {
	Socket socket;
	
	public SocketPortConnector() {
	}

	@Override
	protected void doConnect(Port portSetting) throws Exception {
		String port = portSetting.portName;
		String targetHost = port.substring(0, port.indexOf(':'));
		String targetPort = port.substring(port.indexOf(':') + 1, port.length());
		Socket socket = new Socket(targetHost, Integer.parseInt(targetPort));
		this.socket = socket;
		socket.setSoTimeout(0);
		setInputStream(socket.getInputStream());
		setOutputStream(socket.getOutputStream());
	}

	@Override
	protected void doDisconnect() throws IOException {
		socket.shutdownInput();
		socket.shutdownOutput();
	}

	@Override
	protected void doSetTimeout(long timeout) throws IOException {
		socket.setSoTimeout((int)timeout);
	}

	@Override
	public String[] getDevices() {
		String[] devices = {"localhost:5000"};
		return devices;
	}

	@Override
	public void setRTSDTR(boolean rtshigh, boolean dtrhigh) throws IOException {
	}

	@Override
	protected void doConfigure(Port portSetting) throws IOException {
	}

}
