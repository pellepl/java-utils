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

import java.awt.Component;
import java.io.File;
import java.util.regex.Pattern;

import javax.swing.ImageIcon;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileFilter;

public class UIUtil {
  static public final String PROP_DEFUALT_PATH = "pelleplutt.path.default";
  static public final String PROP_RELATIVE_RESOURCE_PATH = "pelleplutt.path.resources";

  public static ImageIcon createImageIcon(String path) {
    String resPath = System.getProperty(PROP_RELATIVE_RESOURCE_PATH);
    if (resPath == null) {
      resPath = "res/";
    }
    String urlPath = path.replaceAll(Pattern.quote(resPath), "");
    java.net.URL imgURL = UIUtil.class.getClassLoader().getResource(urlPath);
    if (imgURL != null) {
      return new ImageIcon(imgURL);
    } else {
      Log.println("Couldn't find file: " + path);
      return new ImageIcon(path);
    }
  }

  public static boolean showQueryYesNo(Component owner, String header, String text) {
    return JOptionPane.YES_OPTION == JOptionPane.showConfirmDialog(
        owner, text, header, JOptionPane.YES_NO_OPTION);
  }

  public static void showPopup(Component owner, String header, String text) {
    JOptionPane.showMessageDialog(owner, text, header,
        JOptionPane.PLAIN_MESSAGE);
  }

  public static int showWarning(Component owner, String header, String text,
      int optionType) {
    return JOptionPane.showConfirmDialog(owner, text, header, optionType,
        JOptionPane.WARNING_MESSAGE);
  }

  public static int showWarning(Component owner, String header, String text,
      int optionType, Object[] options, Object defOp) {
    return JOptionPane.showOptionDialog(owner, text, header, optionType,
        JOptionPane.WARNING_MESSAGE, null, // do not use a custom Icon
        options, // the titles of buttons
        defOp);
  }

  public static void showError(Component owner, String header, String text) {
    JOptionPane.showMessageDialog(owner, text, header,
        JOptionPane.ERROR_MESSAGE);
  }

  public static void showError(Component owner, String header, Throwable e) {
    showError(owner, header, (e.getClass().getName() + ": ") + e.getMessage());
  }

  public static File selectFile(Component owner, String title, String buttonText) {
    return selectFile(owner, title, buttonText, null, null, false, false);
  }

  public static File selectFile(Component owner, String title, String buttonText,
      String fileSuffix, String filterDesc) {
    String[] fs = { fileSuffix };
    return selectFile(owner, title, buttonText, fs, filterDesc, false, false);
  }

  public static File selectFile(Component owner, String title, String buttonText,
      String[] fileSuffix, String filterDesc) {
    return selectFile(owner, title, buttonText, fileSuffix, filterDesc, false,
        false);
  }

  public static File selectFile(Component owner, String title, String buttonText,
      boolean filesOnly, boolean directoriesOnly) {
    return selectFile(owner, title, buttonText, null, null, filesOnly,
        directoriesOnly);
  }

  public static File selectFile(Component owner, String title, String buttonText,
      final String[] filterSuffix, final String filterDescr,
      final boolean filesOnly, final boolean directoriesOnly) {
    String defaultPath = System.getProperty(PROP_DEFUALT_PATH);
    if (defaultPath == null) {
      defaultPath = System.getProperty("user.home");
    }
    JFileChooser chooser = new JFileChooser(new File(defaultPath));
    int fselMode = JFileChooser.FILES_AND_DIRECTORIES;
    if (filesOnly) {
      fselMode = JFileChooser.FILES_ONLY;
    } else if (directoriesOnly) {
      fselMode = JFileChooser.DIRECTORIES_ONLY;
    }
    chooser.setFileSelectionMode(fselMode);
    if (filterSuffix != null) {
      chooser.setFileFilter(new FileFilter() {
        public boolean accept(File f) {
          if (f.isDirectory())
            return true;
          String lfn = f.getName().toLowerCase();
          for (int i = 0; i < filterSuffix.length; i++) {
            if (lfn.endsWith(filterSuffix[i].toLowerCase())) {
              return true;
            }
          }
          return false;
        }

        public String getDescription() {
          return filterDescr;
        }
      });
    }
    chooser.setApproveButtonText(buttonText);
    chooser.setDialogTitle(title);
    int returnVal = chooser.showOpenDialog(owner);
    if (returnVal == JFileChooser.APPROVE_OPTION) {
      File f = chooser.getSelectedFile();
      if (f.isDirectory()) {
        defaultPath = f.getAbsolutePath();
      } else {
        defaultPath = f.getParentFile().getAbsolutePath();
      }
      System.setProperty(PROP_DEFUALT_PATH, defaultPath);
      return f;
    } else {
      return null;
    }
  }
}
