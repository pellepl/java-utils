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


import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

import com.pelleplutt.util.*;
/**
 * Serial port for all platforms in python3 and pyserial.
 * 
 * @author petera
 */
public class PySerialPortUARTSocket extends UARTSocket {
	public static final String PROP_PATH_BIN = "portconnector.python.bin";
	
	public static final int VERSION = 0x00010000;
	
	protected PySerialPortUARTSocket() {
	}

	String preprocessPortName(String portname) {
		if (!portname.startsWith("/dev/") && portname.startsWith("tty")) {
			portname = "/dev/" + portname;
		}
		return portname;
	}

	public void configureTimeout(long timeout)
			throws IOException {
		sData.setSoTimeout((int)timeout + ((timeout > 0) ? 100 : 0));
		controlCommand(true, "U T" + timeout
			+ " M" + (timeout == 0 ? '1' : '0'), 0);
	}

	void checkBinary(File exe, File verFile, int ver) throws IOException, InterruptedException {
	   boolean update = false;
	    // check exe
	    if (!exe.exists()) {
	      update = true;
	    }
	    // check version
	    if (!update && !verFile.exists()) {
	      update = true;
	    } else {
	      String fverStr = AppSystem.readFile(verFile);
	      int fver = 0;
	      try {
	        fver = Integer.parseInt(fverStr);
	      } catch (Throwable ignore) {}
	      update = fver < ver;
	    }
	  
		// copy python file
		if (update) {
			File dst = new File(System.getProperty(PROP_PATH_BIN));
			AppSystem.copyAppResource("native/cross/pyuartsocket.py", dst);
			Set<PosixFilePermission> attrs = PosixFilePermissions.fromString("rwxr-xr--");
			Files.setPosixFilePermissions(dst.toPath(), attrs);
			AppSystem.writeFile(verFile, Integer.toString(ver));
		}
	}

  File getBinFile() {
    File binFile = new File(System.getProperty(PROP_PATH_BIN));
    return binFile;
  }


  @Override
  File getVersionFile() {
    File verFile = new File(System.getProperty(PROP_PATH_BIN)+".ver");
    return verFile;
  }

  @Override
  int getVersion() {
    return VERSION;
  }
  
  @Override
  protected void postExec() {
    AppSystem.sleep(500);
  }
}
