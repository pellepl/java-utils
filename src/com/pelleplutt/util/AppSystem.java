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

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Reader;
import java.io.Writer;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

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
   *          application resource path
   * @param dest
   *          local file destination
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
   *          application resource path
   * @return a data array
   * @throws IOException
   */
  public static byte[] getAppResource(String path) throws IOException {
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
   * Reads contents of file and returns as string. If anything goes wrong null
   * is returned.
   * 
   * @param f
   *          the file
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
   * Writes contents of string to a file. If there is an existing file, it is
   * replaced. If anything goes wrong, false is returned.
   * 
   * @param f
   *          the file
   * @param content
   *          the content to write
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
   * Writes byte byffer to a file. If there is an existing file, it is replaced.
   * If anything goes wrong, false is returned.
   * 
   * @param f
   *          the file
   * @param content
   *          the content to write
   * @return false if bad, true if nice
   */
  public static boolean writeFile(File f, byte[] content) {
    if (f.exists() && f.isDirectory()) {
      return false;
    }
    if (f.exists()) {
      f.delete();
    }
    f.getParentFile().mkdirs();
    FileOutputStream fos = null;
    try {
      fos = new FileOutputStream(f);
      fos.write(content);
    } catch (Throwable ignore) {
      return false;
    } finally {
      closeSilently(fos);
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
   *          the command to run
   * @param envp
   * @param execDir
   * @param getOut
   *          true to catch stdout, false to ignore
   * @param getErr
   *          true to catch err, false to ignore
   * @return a structure with the processes information.
   * @throws IOException
   * @throws InterruptedException
   */
  public static ProcessResult run(String cmd, String[] envp, File execDir,
		  boolean getOut, boolean getErr) throws IOException, InterruptedException {
	String[] spl = cmd.split("\\s");
    List<String> conspl = new ArrayList<String>();
    char quote = '\0';
    for (String s : spl) {
      if (quote == '\0') {
        if (s.charAt(0) == '\'' || s.charAt(0) == '"') {
          quote = s.charAt(0);
          s = s.substring(1);
        }
        conspl.add(s);
      } else {
        if (s.charAt(s.length() - 1) == quote) {
          s = s.substring(0, s.length() - 1);
          quote = '\0';
        }
        String ss = conspl.remove(conspl.size() - 1);
        conspl.add(ss + " " + s);
      }
    }
    Process p = Runtime.getRuntime().exec(conspl.toArray(new String[conspl.size()]), envp, execDir);
    final String nl = System.getProperty("line.separator");
    BufferedReader out = null;
    BufferedReader err = null;
    if (getOut) {
      out = new BufferedReader(new InputStreamReader(p.getInputStream()));
    }
    if (getErr) {
      err = new BufferedReader(new InputStreamReader(p.getErrorStream()));
    }
    StringBuffer serr = new StringBuffer();
    StringBuffer sout = new StringBuffer();
    String s = "";
    if (getOut) {
      while ((s = out.readLine()) != null) {
        sout.append(s);
        sout.append(nl);
      }
    }
    if (getErr) {
      while ((s = err.readLine()) != null) {
        serr.append(s);
        serr.append(nl);
      }
    }
    int code = p.waitFor();
    return new ProcessResult(code, sout.toString(), serr.toString());
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
   * Compiles, loads and instantiates a java file of given interface. Each java
   * file will get its own classloader.
   * 
   * @param classpath
   *          path of the java files source tree
   * @param javaFile
   *          the actual compilable java file, relative to classpath
   * @param ifc
   *          the interface of the java file to compile
   * @param errOut
   *          print stream where error messages are printed
   * @return the instance of the compiled file, or null if something failed
   */
  @SuppressWarnings("unchecked")
  public static <E> E compileInstantiate(File classpath, String javaFile,
      Class<E> ifc, PrintStream errOut) {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    E inst = null;
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int result = compiler.run(null, null, err,
        new File(classpath, javaFile).getAbsolutePath());
    if (result != 0) {
      if (errOut != null)
        errOut.println(err.toString());
    } else {
      try {
        ClassLoader loader = URLClassLoader.newInstance(
            new URL[] { classpath.toURI().toURL() },
            Thread.currentThread().getContextClassLoader());
        String className = javaFile.replace(".java", "").replace('/', '.')
            .replace('\\', '.');
        Class<?> clazz = loader.loadClass(className);
        if (!ifc.isAssignableFrom(clazz)) {
          if (errOut != null)
            errOut.println(javaFile + " is not interface of " + ifc.getName());
        } else {
          inst = (E) clazz.newInstance();
        }
      } catch (Throwable t) {
        if (errOut != null)
          errOut.println(t.getMessage());
      }
    }
    return inst;
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

  static int __x = 0;
  static int __maxNum = 0;

  /**
   * Constructs a path from given string. If the given string contains special
   * sequences, these are replaced. %Y% will be replaced by current year. %M%
   * will be replaced by current month. %D% will be replaced by current day. %h%
   * will be replaced by current hour. %m% will be replaced by current minute.
   * %s% will be replaced by current second. %x% will be replaced by an index.
   * Everytime this function is called the index is increased. The index is set
   * to zero at app startup. Asterisk (*) is a wild card, if there are matching
   * files the asterisk will be replaced by the highest found index within
   * similar files plus one. E.g. log*.txt on an existing directory containing
   * log1.txt and log2.txt will return a filename being log3.txt.
   * 
   * @param path
   *          the filename with or without path
   * @return a file denoting the resulting path and file name
   */
  public static File makeSmartPath(String path) {
    Calendar c = Calendar.getInstance();
    path = path.replaceAll("%Y%", String.format("%d", c.get(Calendar.YEAR)));
    path = path.replaceAll("%M%",
        String.format("%02d", c.get(Calendar.MONTH) + 1));
    path = path.replaceAll("%D%",
        String.format("%02d", c.get(Calendar.DAY_OF_MONTH)));
    path = path.replaceAll("%h%",
        String.format("%02d", c.get(Calendar.HOUR_OF_DAY)));
    path = path.replaceAll("%m%",
        String.format("%02d", c.get(Calendar.MINUTE)));
    path = path.replaceAll("%s%",
        String.format("%02d", c.get(Calendar.SECOND)));
    path = path.replaceAll("%x%", Integer.toString(__x++));
    final int pix = path.indexOf('*');
    if (pix < 0) {
      return new File(path);
    }
    __maxNum = 0;
    final String filename = Paths.get(path).getFileName().toString();
    final String parent = Paths.get(path).getParent() == null ? "."
        : Paths.get(path).getParent().toString();
    String s = "\\Q" + filename.replaceAll("\\*", "\\\\E(.*)\\\\Q") + "\\E";
    final Pattern pattern = Pattern.compile(s);
    final PathMatcher filematcher = FileSystems.getDefault()
        .getPathMatcher("glob:" + filename);
    try {
      Files.walkFileTree(Paths.get(parent), new FileVisitor<Path>() {
        @Override
        public FileVisitResult preVisitDirectory(Path dir,
            BasicFileAttributes attrs) throws IOException {
          return dir.equals(Paths.get(parent)) ? FileVisitResult.CONTINUE
              : FileVisitResult.SKIP_SUBTREE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
          if (filematcher.matches(file.getFileName())) {
            Matcher m = pattern.matcher(file.getFileName().toString());
            if (m.matches()) {
              try {
                int nbr = Integer.parseInt(m.group(1));
                if (nbr > __maxNum) {
                  __maxNum = nbr;
                }
              } catch (Throwable t) {
              }
            }
          }
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc)
            throws IOException {
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc)
            throws IOException {
          return FileVisitResult.TERMINATE;
        }
      });
    } catch (IOException ioe) {
    }

    String fname2 = filename.replaceAll("\\*", Integer.toString(__maxNum + 1));
    return new File(parent, fname2);
  }

  public static interface AppSystemFileVisitor {
    /**
     * Called from visitDirectory to handle a visited file.
     * 
     * @param file
     *          the file
     * @param attrs
     *          the file attributes
     * @return true to continue, false to terminate the visitor
     */
    public boolean visit(Path file, BasicFileAttributes attrs);
  }

  /**
   * Visits files in a given directory, recursive or not.
   * 
   * @param dir
   *          The directory to visit.
   * @param namePattern
   *          The name pattern for matching files. Asterisk for wildcard.
   * @param recurse
   *          Whether to recurse or not.
   * @param v
   *          The visitor.
   */
  public static void visitDirectory(String dir, String namePattern,
      final boolean recurse, final AppSystemFileVisitor v) {
    final String parent = dir == null ? "." : Paths.get(dir).toString();
    String patStr = "\\Q" + namePattern.replaceAll("\\*", "\\\\E(.*)\\\\Q")
        + "\\E";
    final Pattern pattern = Pattern.compile(patStr);
    final PathMatcher filematcher = FileSystems.getDefault()
        .getPathMatcher("glob:" + namePattern);
    try {
      Files.walkFileTree(Paths.get(parent), new FileVisitor<Path>() {
        @Override
        public FileVisitResult preVisitDirectory(Path dir,
            BasicFileAttributes attrs) throws IOException {
          return dir.equals(Paths.get(parent)) || recurse
              ? FileVisitResult.CONTINUE
              : FileVisitResult.SKIP_SUBTREE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
          if (filematcher.matches(file.getFileName())) {
            Matcher m = pattern.matcher(file.getFileName().toString());
            if (m.matches()) {
              if (!v.visit(file, attrs)) {
                return FileVisitResult.TERMINATE;
              }
            }
          }
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc)
            throws IOException {
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc)
            throws IOException {
          return recurse ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
        }
      });
    } catch (IOException ioe) {
    }
  }

  /**
   * Removes files in a given directory, recursive or not.
   * 
   * @param dir
   *          The directory.
   * @param namePattern
   *          The name pattern for files to remove. Asterisk for wildcard.
   * @param recurse
   *          Whether to recurse or not.
   */
  public static void removeFiles(String dir, String namePattern,
      boolean recurse) {
    visitDirectory(dir, namePattern, recurse, new AppSystemFileVisitor() {
      @Override
      public boolean visit(Path file, BasicFileAttributes attrs) {
        file.toFile().delete();
        return true;
      }
    });
  }

  /**
   * Returns files in a given directory, recursive or not.
   * 
   * @param dir
   *          The directory.
   * @param namePattern
   *          The name pattern for files to remove. Asterisk for wildcard.
   * @param recurse
   *          Whether to recurse or not.
   */
  public static List<File> findFiles(String dir, String namePattern,
      boolean recurse) {
    final List<File> res = new ArrayList<File>();
    visitDirectory(dir, namePattern, recurse, new AppSystemFileVisitor() {
      @Override
      public boolean visit(Path file, BasicFileAttributes attrs) {
        res.add(file.toFile());
        return true;
      }
    });
    return res;
  }

  static List<Disposable> disposables = new ArrayList<Disposable>();

  public synchronized static void addDisposable(Disposable d) {
    if (!disposables.contains(d)) {
      disposables.add(d);
    }
  }

  public synchronized static void disposeAll() {
    while (!disposables.isEmpty()) {
      Disposable d = disposables.get(0);
      try {
        Log.println("dispose of " + d);
        dispose(d);
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
      if (ms == 0) {
        lock.wait();
      } else {
        lock.wait(ms);
      }
    } catch (InterruptedException e) {
    }
  }

  public static byte[] parseBytes(String hex) {
    hex = hex.toLowerCase();
    List<Byte> b = new ArrayList<Byte>();
    int d = 0;
    int nibcount = 0;
    for (int i = 0; i < hex.length(); i++) {
      char c = hex.charAt(i);
      boolean hexChar = false;
      if (c >= '0' && c <= '9') {
        d = (d << 4) | (c - '0');
        nibcount++;
        hexChar = true;
      } else if (c >= 'a' && c <= 'f') {
        d = (d << 4) | (c - 'a' + 10);
        nibcount++;
        hexChar = true;
      }
      if (hexChar && nibcount >= 2) {
        b.add((byte) d);
        nibcount = d = 0;
      }
      if (!hexChar && nibcount > 0) {
        b.add((byte) d);
        nibcount = d = 0;
      }
    }
    if (nibcount > 0) {
      b.add((byte) d);
    }
    if (!b.isEmpty()) {
      byte[] barr = new byte[b.size()];
      for (int i = 0; i < barr.length; i++) {
        barr[i] = b.get(i);
      }
      return barr;
    }
    return null;
  }

  static final String ___h = "0123456789abcdef";

  public static String formatBytes(byte[] b) {
    StringBuilder s = new StringBuilder(b.length * 3 - 1);
    for (int i = 0; i < b.length; i++) {
      int x = b[i];
      s.append(___h.charAt((x >> 4) & 0xf));
      s.append(___h.charAt(x & 0xf));
      if (i < b.length - 1)
        s.append(' ');
    }
    return s.toString();
  }

  public static BufferedImage loadImage(String res) {
    File f = new File("res/" + res);
    if (!f.exists()) {
      String urlPath = res;
      java.net.URL imgURL = Thread.currentThread().getContextClassLoader()
          .getResource(urlPath);
      try {
        return ImageIO.read(imgURL);
      } catch (IOException e) {
        e.printStackTrace();
        return null;
      }
    } else {
      try {
        return ImageIO.read(f);
      } catch (IOException e) {
        e.printStackTrace();
        return null;
      }
    }
  }

}
