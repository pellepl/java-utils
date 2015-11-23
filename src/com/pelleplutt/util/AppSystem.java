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

package com.pelleplutt.util;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * General system functionality utilities.
 * 
 * @author petera
 */
public class AppSystem {
  /**
   * Copies an application resource (from jar) to given local destination.
   * 
   * @param path
   *            application resource path
   * @param dest
   *            local file destination
   * @throws IOException
   */
  public static void copyAppResource(String path, File dest)
      throws IOException {
    InputStream is = null;
    OutputStream os = null;
    try {
      is = Thread.currentThread().getContextClassLoader()
          .getResourceAsStream(path);
      if (dest.exists()) {
        dest.delete();
      }
      dest.getParentFile().mkdirs();
      dest.createNewFile();
      os = new FileOutputStream(dest);
      copyStreams(is, os);
    } finally {
      closeSilently(is);
      closeSilently(os);
    }
  }

  /**
   * Reads an application resource (from jar)
   * 
   * @param path
   *            application resource path
   * @return a data array
   * @throws IOException
   */
  public static byte[] getAppResource(String path)
      throws IOException {
    InputStream is = null;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      is = Thread.currentThread().getContextClassLoader()
          .getResourceAsStream(path);
      copyStreams(is, out);
    } finally {
      closeSilently(is);
    }
    return out.toByteArray();
  }
  
  /**
   * Reads contents of file and returns as string. If anything goes wrong
   * null is returned.
   * @param f the file
   * @return the contents
   */
  public static String readFile(File f) {
    String res = null;
    FileReader fr = null;
    try {
      fr = new FileReader(f);
      StringBuilder stringBuf = new StringBuilder();
      char[] tmp = new char[8 * 1024];
      int read;
      while ((read = fr.read(tmp, 0, tmp.length)) > 0) {
        stringBuf.append(tmp, 0, read);
      }
      res = stringBuf.toString();
    } catch (Throwable ignore) {
    } finally {
      closeSilently(fr);
    }
    return res;
  }
  
  /**
   * Writes contents of string to a file. If there is an existing file,
   * it is replaced. If anything goes wrong, false is returned.
   * @param f the file
   * @param content the content to write
   * @return false if bad, true if nice 
   */
  public static boolean writeFile(File f, String content) {
    if (f.exists() && f.isDirectory()) {
      return false;
    }
    if (f.exists()) {
      f.delete();
    }
    f.getParentFile().mkdirs();
    FileWriter fw = null;
    try {
      fw = new FileWriter(f);
      fw.write(content);
    } catch (Throwable ignore) {
      return false;
    } finally {
      closeSilently(fw);
    }
    return true;
  }

	/**
	 * Copies the data provided in inputstream to the outputstream.
	 * 
	 * @param is
	 * @param os
	 * @throws IOException
	 */
	public static void copyStreams(InputStream is, OutputStream os)
			throws IOException {
		byte[] tmp = new byte[8 * 1024];
		int len;
		while ((len = is.read(tmp)) != -1) {
			os.write(tmp, 0, len);
			os.flush();
		}
	}

	/**
	 * Nullchecks and closes an inputstream and discards any ioexceptions
	 * 
	 * @param is
	 */
	public static void closeSilently(InputStream is) {
		try {
			if (is != null) {
				is.close();
			}
		} catch (IOException ignore) {
		}
	}

	/**
	 * Nullchecks and closes an outputstream and discards any ioexceptions
	 * 
	 * @param os
	 */
	public static void closeSilently(OutputStream os) {
		try {
			if (os != null) {
				os.close();
			}
		} catch (IOException ignore) {
		}
	}

	/**
	 * Runs given command as a process. Conditionally catches stdout and err and
	 * returns result on exit. Blocks until finished.
	 * 
	 * @param cmd
	 *            the command to run
	 * @param envp
	 * @param execDir
	 * @param getOut
	 *            true to catch stdout, false to ignore
	 * @param getErr
	 *            true to catch err, false to ignore
	 * @return a structure with the processes information.
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public static ProcessResult run(String cmd, String[] envp, File execDir,
			boolean getOut, boolean getErr) throws IOException,
			InterruptedException {
		Process p = Runtime.getRuntime().exec(cmd, envp, execDir);
		InputStream out = null;
		InputStream err = null;
		if (getOut) {
			out = new BufferedInputStream(p.getInputStream());
		}
		if (getErr) {
			err = new BufferedInputStream(p.getErrorStream());
		}
		int code = p.waitFor();
		String serr = "";
		String sout = "";
		if (getErr) {
			byte[] errb = new byte[err.available()];
			err.read(errb);
			serr = new String(errb);
		}
		if (getOut) {
			byte[] outb = new byte[out.available()];
			out.read(outb);
			sout = new String(outb);
		}
		return new ProcessResult(code, sout, serr);
	}

	static public class ProcessResult {
		public final int code;
		public final String output;
		public final String err;

		public ProcessResult(int code, String output, String err) {
			this.code = code;
			this.output = output;
			this.err = err;
		}
	}

	/**
	 * Sleeps given milliseconds. Ignores interruptedexceptions.
	 * 
	 * @param i
	 */
	public static void sleep(long i) {
		try {
			if (i > 0)
				Thread.sleep(i);
		} catch (InterruptedException ignore) {
		}
	}

  /**
   * Nullchecks and closes a reader and discards any ioexceptions.
   * 
   * @param r
   */
  public static void closeSilently(Reader r) {
      try {
          if (r != null) {
              r.close();
          }
      } catch (IOException e) {
      }
  }

  /**
   * Nullchecks and closes a writer and discards any ioexceptions.
   * 
   * @param w
   */
  public static void closeSilently(Writer w) {
      try {
          if (w != null) {
              w.close();
          }
      } catch (IOException e) {
      }
  }

	static List<Disposable> disposables = new ArrayList<Disposable>();

	public synchronized static void addDisposable(Disposable d) {
		if (!disposables.contains(d)) {
			disposables.add(d);
		}
	}

	public synchronized static void disposeAll() {
		for (Disposable d : disposables) {
			try {
				d.dispose();
			} catch (Throwable t) {
				Log.printStackTrace(t);
			}
		}
		Log.println("all disposed");
		disposables.clear();
	}

	public synchronized static void dispose(Disposable d) {
		try {
			d.dispose();
		} catch (Throwable t) {
		}
		disposables.remove(d);
	}

	public interface Disposable {
		public void dispose();
	}

	public static void waitSilently(Object lock, long ms) {
		try {
			lock.wait(ms);
		} catch (InterruptedException e) {
		}
	}
}
