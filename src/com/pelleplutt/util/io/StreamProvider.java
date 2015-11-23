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
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import com.pelleplutt.util.AppSystem;
import com.pelleplutt.util.Log;

public class StreamProvider {
	static final int CHUNK_SIZE = 1024;
	IO source;
	volatile boolean open;
	volatile long wrIx = 0;
	long startIx = 0;
	List<byte[]> data = new ArrayList<byte[]>();
	List<Subscriber> subscribers = new ArrayList<Subscriber>();
	Reader reader;
	final Object SUBSCRIBER_SIG = new Object();
	final Object DATA_MODIFICATION = new Object();

	public void setSource(InputStream in, OutputStream out) {
		source = new IO(in, out);
	}

	public void connectSource() {
		open = true;
		reader = new Reader();
		Thread t = new Thread(reader, "streamprovider-reader");
		t.setDaemon(true);
		t.start();
	}

	public void closeSource() {
		open = false;
		// notify subscribers
		synchronized (SUBSCRIBER_SIG) {
			SUBSCRIBER_SIG.notifyAll();
		}
		AppSystem.closeSilently(source.in);
		AppSystem.closeSilently(source.out);
		synchronized (reader) {
			while (reader.active) {
				try {
					reader.wait(200);
				} catch (InterruptedException e) {
				}
			}
		}
	}

	// accessed by one thread only
	void putData(byte[] b, int len) {
		// remove data that wont be referenced
		discardOldData();

		// store received data
		for (int i = data.size(); i <= ((wrIx - startIx) + len) / CHUNK_SIZE; i++) {
			data.add(new byte[CHUNK_SIZE]);
		}
		long curIx = wrIx;
		int srcIx = 0;
		while (srcIx < len) {
			byte[] chunk = data.get((int) (curIx - startIx) / CHUNK_SIZE);
			int offset = (int) (curIx % CHUNK_SIZE);
			int rLen = Math.min(len - srcIx, CHUNK_SIZE - offset);
			System.arraycopy(b, srcIx, chunk, offset, rLen);
			curIx += rLen;
			srcIx += rLen;
		}
		wrIx += len;

		// notify subscribers
		synchronized (SUBSCRIBER_SIG) {
			SUBSCRIBER_SIG.notifyAll();
		}
	}

	// accessed by many threads, no modification
	int getData(byte[] b, int offs, int len, long ix) {
		if (ix >= wrIx) {
			return open ? 0 : -1;
		}
		len = (int) Math.min(len, wrIx - ix);
		long srcIx = ix;
		int dstIx = 0;
		synchronized (DATA_MODIFICATION) { // protected against startIx
											// modification
			while (dstIx < len) {
				byte[] chunk = data.get((int) (srcIx - startIx) / CHUNK_SIZE);
				int offset = (int) (srcIx % CHUNK_SIZE);
				int rLen = Math.min(len - dstIx, CHUNK_SIZE - offset);
				System.arraycopy(chunk, offset, b, dstIx + offs, rLen);
				dstIx += rLen;
				srcIx += rLen;
			}
		}

		return len;
	}

	void discardOldData() {
		long minIx = Long.MAX_VALUE;
		synchronized (subscribers) {
			for (Subscriber subscriber : subscribers) {
				minIx = Math.min(minIx, subscriber.rdIx);
			}
		}
		synchronized (DATA_MODIFICATION) {
			if ((minIx - startIx) / CHUNK_SIZE > 0) {
				while (startIx < minIx) {
					data.remove(0);
					startIx += CHUNK_SIZE;
				}
			}
		}
	}

	public Subscriber subscribe() {
		Subscriber s = new Subscriber();
		s.rdIx = startIx;
		synchronized (subscribers) {
			subscribers.add(s);
		}
		return s;
	}

	public void unsubscribe(Subscriber s) {
		s.active = false;
		synchronized (SUBSCRIBER_SIG) {
			SUBSCRIBER_SIG.notifyAll();
		}
		synchronized (subscribers) {
			subscribers.remove(s);
		}
		discardOldData();
	}

	/**
	 * Subscriber class tapping a stream
	 */
	public class Subscriber {
		static final int PAUSE_OFF = 0;
		static final int PAUSE_SKIP = 1;
		static final int PAUSE_RETAIN = 2;
		long rdIx;
		long timeout = 0;
		int paused = PAUSE_OFF;
		final IO io;
		volatile boolean active = true;

		public Subscriber() {
			io = new IO(new InputStream(), source.out);
		}

		public void pause(boolean skipIncomingData) {
			paused = skipIncomingData ? PAUSE_SKIP : PAUSE_RETAIN;
			synchronized (SUBSCRIBER_SIG) {
				SUBSCRIBER_SIG.notifyAll();
			}
		}

		public void resume() {
			paused = PAUSE_OFF;
			synchronized (SUBSCRIBER_SIG) {
				SUBSCRIBER_SIG.notifyAll();
			}
		}

		public void setTimeout(long ms) {
			timeout = ms;
			synchronized (SUBSCRIBER_SIG) {
				SUBSCRIBER_SIG.notifyAll();
			}
		}

		public int read(byte[] b, int offs, int l) {
			int res = 0;
			try {
				while (active && open && (rdIx >= wrIx || paused != PAUSE_OFF)) {
					synchronized (SUBSCRIBER_SIG) {
						if (timeout > 0) {
							SUBSCRIBER_SIG.wait(timeout);
							break;
						} else {
							SUBSCRIBER_SIG.wait();
						}
					}
					switch (paused) {
					case PAUSE_SKIP:
						rdIx = wrIx;
						// fallthru
					case PAUSE_RETAIN:
						if (!open) {
							res = -1;
						}
						break;
					}
				}
				if (res >= 0 && paused == PAUSE_OFF) {
					res = getData(b, offs, l, rdIx);
					if (res > 0) {
						rdIx += res;
					}
				}
			} catch (InterruptedException e) {
				res = -1;
			}
			return active ? res : -1;
		}

		/**
		 * Subscriber InputStream implementation
		 */
		class InputStream extends java.io.InputStream {
			byte[] cbuf = new byte[1];

			public int available() {
				return (int) (wrIx - Subscriber.this.rdIx);
			}

			public int read(byte[] b, int off, int len) throws IOException {
				return Subscriber.this.read(b, off, len);
			}

			public int read() throws IOException {
				int res = Subscriber.this.read(cbuf, 0, 1);
				return res == 1 ? (cbuf[0] & 0xff) : -1;
			}
		}
	}

	/**
	 * The single reader of a stream
	 */
	class Reader implements Runnable {
		volatile boolean active;

		public void run() {
			Log.println("reader started");
			active = true;
			try {
				byte[] buf = new byte[CHUNK_SIZE];
				while (open) {
					int res = source.in.read(buf);
					if (res < 0) {
						open = false;
					} else if (res > 0) {
						putData(buf, res);
					}
				}
			} catch (IOException e) {
				open = false;
			} finally {
				closeSource();
				active = false;
				synchronized (Reader.this) {
					Reader.this.notifyAll();
				}
				Log.println("reader stopped");
			}
		}
	}

	/**
	 * I/O pair
	 */
	static public class IO {
		InputStream in;
		OutputStream out;

		public IO(InputStream i, OutputStream o) {
			in = i;
			out = o;
		}
	}
}