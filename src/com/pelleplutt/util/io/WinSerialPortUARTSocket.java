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


import java.io.File;
import java.io.IOException;

import com.pelleplutt.util.AppSystem;
import com.pelleplutt.util.Log;
/**
 * Serial port for win.
 * @author petera
 */
public class WinSerialPortUARTSocket extends UARTSocket {
	public static final String PROP_PATH_BIN = "portconnector.win.bin";
	public static final String PROP_NAME = "portconnector.win.name";
	public static final int VERSION = 0x00010001;

	protected WinSerialPortUARTSocket() {
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
		// cpy our little binary 
		if (update) {
			AppSystem.copyAppResource("native/win/uartsocket.exe", exe);
			AppSystem.writeFile(verFile, Integer.toString(ver));
			Log.println("copy ok");
		}
	}

	File getBinFile() {
		String name = System.getProperty(PROP_NAME) + ".exe";
		File binFile = new File(System.getProperty(PROP_PATH_BIN), name);
		return binFile;
	}

  @Override
  File getVersionFile() {
    String name = System.getProperty(PROP_NAME) + ".ver";
    File verFile = new File(System.getProperty(PROP_PATH_BIN), name);
    return verFile;
  }

  @Override
  int getVersion() {
    return VERSION;
  }
}
