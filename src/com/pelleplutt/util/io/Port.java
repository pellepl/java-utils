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

public class Port implements Comparable<Port> {
	public static final int UNDEF = -1;
	public static final int BAUD_110 = 110;
	public static final int BAUD_300 = 300;
	public static final int BAUD_600 = 600;
	public static final int BAUD_1200 = 1200;
	public static final int BAUD_2400 = 2400;
	public static final int BAUD_4800 = 4800;
	public static final int BAUD_9600 = 9600;
	public static final int BAUD_14400 = 14400;
	public static final int BAUD_19200 = 19200;
	public static final int BAUD_38400 = 38400;
	public static final int BAUD_57600 = 57600;
	public static final int BAUD_115200 = 115200;
	public static final int BAUD_128000 = 128000;
	public static final int BAUD_230400 = 230400;
	public static final int BAUD_256000 = 256000;
	public static final int BAUD_460800 = 460800;
	public static final int BAUD_921600 = 921600;
	public static final int PARITY_NO = 0;
	public static final int PARITY_ODD = 1;
	public static final int PARITY_EVEN = 2;
	public static final String PARITY_NONE_S = "None";
	public static final String PARITY_ODD_S = "Odd";
	public static final String PARITY_EVEN_S = "Even";

	public static final int PARITY_MARK = 3;
	public static final int PARITY_SPACE = 4;

	public static final int STOPBIT_ONE = 1;
	public static final int STOPBIT_TWO = 2;
	public static final int BYTESIZE_5 = 5;
	public static final int BYTESIZE_6 = 6;
	public static final int BYTESIZE_7 = 7;
	public static final int BYTESIZE_8 = 8;

	public String portName;
	public String uiName;
	public int baud;
	public int parity;
	public int stopbits;
	public int databits;

	public Port() {
		portName = "undef";
		uiName = "undef";
		baud = BAUD_115200;
		parity = PARITY_NO;
		stopbits = STOPBIT_ONE;
		databits = BYTESIZE_8;
	}

	public static int parseParity(String s) {
		if (PARITY_NONE_S.equals(s)) {
			return PARITY_NO;
		}
		if (PARITY_EVEN_S.equals(s)) {
			return PARITY_EVEN;
		}
		if (PARITY_ODD_S.equals(s)) {
			return PARITY_ODD;
		}
		return PARITY_NO;
	}

	public static String parityToString(int parity) {
		switch (parity) {
		case PARITY_NO:
			return PARITY_NONE_S;
		case PARITY_EVEN:
			return PARITY_EVEN_S;
		case PARITY_ODD:
			return PARITY_ODD_S;
		default:
			return null;
		}
	}

	public int compareTo(Port o) {
		if (o instanceof Port) {
			return portName.compareTo(((Port) o).portName);
		} else {
			return 0;
		}
	}
}