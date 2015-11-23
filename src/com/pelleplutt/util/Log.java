package com.pelleplutt.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

//import com.myapp.Essential; // TODO

public class Log {
  public static PrintStream out = System.out;
  public static boolean log = true;              // If general logging should be enabled by default
  public static final int TAB = 8;
  public static final Set<String> includeClasses = new TreeSet<String>();
  public static final Set<String> excludeClasses = new TreeSet<String>();
  public static final List<String> includeStrings = new ArrayList<String>();
  public static final List<String> excludeStrings = new ArrayList<String>();
  public static final String DIRECT_SETTING_DIRECTORY_PROPERTY_NAME = "pelle.log.conf.dir";
  
  public static final String FILE_ENTRY_EXCLUDE_STRINGS = "[[-strings";
  public static final String FILE_ENTRY_INCLUDE_STRINGS = "[[onlystrings";
  public static final String FILE_ENTRY_EXCLUDE_CLASSES = "[[-classes";
  public static final String FILE_ENTRY_INCLUDE_CLASSES = "[[onlyclasses";
  public static final String FILE_ENTRY_STRIP_PACKAGES = "[[-packages";
  public static final String FILE_ENTRY_STRIP_PACKAGES_DISABLE = "[[packages";
  public static final String FILE_ENTRY_STRIP_PACKAGES_AUTO = "[[packagesauto";
  public static final String FILE_ENTRY_LOG_DISABLE = "[[-log";
  public static final String FILE_ENTRY_LOG_ENABLE = "[[log";
  
  static final int STATE_EXCLUDE_STRINGS = 0;
  static final int STATE_INCLUDE_STRINGS = 1;
  static final int STATE_EXCLUDE_CLASSES = 2;
  static final int STATE_INCLUDE_CLASSES = 3;
  
  static boolean ln = true;
  static int maxPrefixStringInTabs = 0;
  
  static boolean stripPackages = true;
  static String thisClassName = Log.class.getName();
  static int stripIndex = -1;
  static int stripTraceLevel = -1;
  static Exception ex = new Exception();
  
  static {
    try {
      readConf();
    } catch (Throwable ignore) {}
  }
  
  public static void printStackTrace(Throwable t) {
	  if (Essential.LOG_C && log && out != System.out && out != System.err) {
		  t.printStackTrace(out);
	  }
  }
  
  public static void print(String s) {
    if (Essential.LOG_C && log) {
      StringBuffer sb = new StringBuffer(s);
      boolean filtered = false;
      filtered = checkWildcardFilters(s);
      if (ln) {
        if (!filtered) {
          sb = doClassAndMethod(sb);
        }
      }
      if (!filtered && sb != null) {
        out.print(sb.toString());
      }
      ln = false;
    }
  }
  public static void println(String s) {
    if (Essential.LOG_C && log) {
      StringBuffer sb = new StringBuffer(s);
      boolean filtered = false;
      filtered = checkWildcardFilters(s);
      if (ln) {
        if (!filtered) {
          sb = doClassAndMethod(sb);
        }
      }
      if (!filtered && sb != null) {
        out.println(sb.toString());
      }
      ln = true;
    }
  }
  
  public static void readConf() throws IOException {
    if (Essential.LOG_C && log) {
      String dir = System.getProperty(DIRECT_SETTING_DIRECTORY_PROPERTY_NAME, System.getProperty("user.home"));
      File f = new File(dir, Essential.LOG_SETTING_FILE_NAME);
      if (f.exists()) {
        FileReader fr = null;
        BufferedReader br = null;
        try {
          fr = new FileReader(f);
          br = new BufferedReader(fr);
          int state = STATE_EXCLUDE_STRINGS;
          for (String line = br.readLine(); line != null; line = br.readLine()) {
            if (!line.startsWith("//") && line.trim().length()>0) {
              if (line.equalsIgnoreCase(FILE_ENTRY_EXCLUDE_STRINGS)) {
                state = STATE_EXCLUDE_STRINGS;
              } else if (line.equalsIgnoreCase(FILE_ENTRY_INCLUDE_STRINGS)) {
                state = STATE_INCLUDE_STRINGS;
              } else if (line.equalsIgnoreCase(FILE_ENTRY_EXCLUDE_CLASSES)) {
                state = STATE_EXCLUDE_CLASSES;
              } else if (line.equalsIgnoreCase(FILE_ENTRY_INCLUDE_CLASSES)) {
                state = STATE_INCLUDE_CLASSES;
              } else if (line.equalsIgnoreCase(FILE_ENTRY_LOG_DISABLE)) {
                log = false;
              } else if (line.equalsIgnoreCase(FILE_ENTRY_LOG_ENABLE)) {
                log = true;
              } else if (line.equalsIgnoreCase(FILE_ENTRY_STRIP_PACKAGES)) {
                stripPackages = true;
              } else if (line.equalsIgnoreCase(FILE_ENTRY_STRIP_PACKAGES_DISABLE)) {
                stripPackages = false;
              } else if (line.equalsIgnoreCase(FILE_ENTRY_STRIP_PACKAGES_AUTO)) {
                stripPackages = true;
                stripIndex = 1+thisClassName.lastIndexOf('.');
              } else {
                switch (state) {
                case STATE_INCLUDE_STRINGS:
                  includeStrings.add(line);
                  break;
                case STATE_EXCLUDE_STRINGS:
                  excludeStrings.add(line);
                  break;
                case STATE_EXCLUDE_CLASSES:
                  excludeClasses.add(line);
                  break;
                case STATE_INCLUDE_CLASSES:
                  includeClasses.add(line);
                  break;
                }
              }
            }
          }
        } finally {
          if (fr != null) {
            try {
              fr.close();
            } catch (IOException ignore) {}
          }
          if (br != null) {
            try {
              br.close();
            } catch (IOException ignore) {}
          }
        }
      }
    }
  }
  
  static boolean checkWildcardFilters(String s) {
    boolean filtered = false;
    if (!includeStrings.isEmpty()) {
      filtered = true;
      for (String incString : includeStrings) {
        if (s.indexOf(incString) != -1) {
          filtered = false;
          break;
        }
      }
    }
    if (!filtered && !excludeStrings.isEmpty()) {
      for (String exString : excludeStrings) {
        filtered = s.indexOf(exString) != -1;
        if (filtered) {
          break;
        }
      }
    }
    return filtered;
  }
  
  @SuppressWarnings("unused")
  static StringBuffer doClassAndMethod(StringBuffer sb) {
    boolean filtered = false;
    if (Essential.LOG_CLASS || Essential.LOG_METHOD || Essential.LOG_LINE) {
      StackTraceElement[] stack = ex.fillInStackTrace().getStackTrace();
      
      if (stripTraceLevel == -1) {
        for (int i = 0; i < stack.length; i++) {
          StackTraceElement stackElement = stack[i];
          if (!stackElement.getClassName().startsWith(thisClassName)) {
            stripTraceLevel = i;
            break;
          }
        }
      }
      StackTraceElement stackElement = stack[stripTraceLevel];
      if (!includeClasses.isEmpty()) {
        filtered = !includeClasses.contains(stackElement.getClassName());
      }
      if (!excludeClasses.isEmpty()) {
        filtered |= excludeClasses.contains(stackElement.getClassName());
      }
      
      if (!filtered) {
        StringBuilder sbPre = new StringBuilder();
        if (Essential.LOG_CLASS) {
          String className = stackElement.getClassName();
          if (stripPackages) {
            if (stripIndex > 0) {
              sbPre.append(className.substring(stripIndex));
            } else {
              int ix = className.lastIndexOf('.');
              sbPre.append(className.substring(ix > 0 ? ix+1 : 0));
            }
          } else {
            sbPre.append(className);
          }
        }
        if (Essential.LOG_METHOD) {
          sbPre.append('.');
          sbPre.append(stackElement.getMethodName());
        }
        if (Essential.LOG_LINE) {
          sbPre.append('(');
          sbPre.append(stackElement.getLineNumber());
          sbPre.append(')');
        }
        sbPre.append(':');
        sbPre.append('\t');
        int lenInTabs = sbPre.length()/TAB;
        maxPrefixStringInTabs = Math.max(maxPrefixStringInTabs, lenInTabs);
        int extraTabs = 0;
        if (lenInTabs < maxPrefixStringInTabs) {
          extraTabs = (maxPrefixStringInTabs - lenInTabs);
          for (int t = 0; t < extraTabs; t++) {
            sbPre.append('\t');
          }
        }
        sb.insert(0, sbPre);
      }
    }
    if (filtered) {
      sb = null;
    }
    return sb;
  }
}
