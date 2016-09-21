package com.pelleplutt.util;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.SwingUtilities;

public class FastTermPane extends FastTextPane {
  int dimCols;
  int dimRows;
  int curCol;
  int curRow;
  int fixLongestStringWidth = 8;
  int fontWPx;
  boolean drawCursor = true;
  volatile boolean blinkCursor = true;

  public FastTermPane() {
    super();
    dimCols = 80;
    dimRows = 25;
    addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent me) {
        if (SwingUtilities.isRightMouseButton(me)) {
          int col = me.getX() / fontWPx;
          int lines = Math.max(dimRows, doc.countLines());
          int row = me.getY() / fontHPx;
          row = row - (lines - dimRows);
          setCursor(col, row);
        }
      }
    });
    Thread blinky = new Thread(new Runnable() {
      public void run() {
        while (true) {
          AppSystem.sleep(500);
          blinkCursor = !blinkCursor;
          repaint();
        }
      }
    });
    blinky.setDaemon(true);
    blinky.start();
  }
  
  @Override
  public void setFont(Font f) {
    super.setFont(f);
    fontWPx = getFontMetrics(getFont()).charWidth('@');
    setTermSize(dimCols, dimRows);
  }
  
  public void setCursor(int c, int r) {
    curRow = Math.max(0, Math.min(dimRows, r));
    curCol = Math.max(0, Math.min(dimCols, c));
    blinkCursor = true;
    System.out.println("set cursor " + curCol + "," + curRow + " docRow " + getLineNbrAtCursor() + " of " + countLines());
    repaint();
  }
  
  public void setCursorColumn(int c) {
    setCursor(c, curRow);
  }
  
  public void setCursorRow(int r) {
    setCursor(curCol, r);
  }
  
  public void setCursorVisible(boolean x) {
    drawCursor = x;
  }
  
  public int getCursorColumn() {
    return curCol;
  }
  
  public int getCursorRow() {
    return curRow;
  }
  
  public int getWidthChars() {
    return dimCols;
  }
  
  public int getHeightChars() {
    return dimRows;
  }
  
  public void setTermSize(int cols, int rows) {
    dimCols = cols;
    dimRows = rows;
    StringBuilder sb = new StringBuilder(cols);
    for (int i = 0; i < cols; i++) {
      sb.append('@');
    }
    fixLongestStringWidth = getFontMetrics(getFont()).stringWidth(sb.toString()); 
    recalcSize();
  }
  
  @Override
  protected void recalcSize() {
    longestStringWidth = fixLongestStringWidth;
    super.recalcSize();
  }
  
  @Override
  protected void onCleared() {
    super.onCleared();
    setCursor(0,0);
  }
   
  protected int getLineNbrAtCursor() {
    int lineCount = doc.countLines()-1;
    int dimRowsMin = Math.min(lineCount, dimRows);
    return lineCount - (dimRowsMin - curRow);
  }
  
  @Override
  public void addText(String s) {
    insertLines(s, null);
  }
  
  @Override
  public void addText(String s, int id, Color fg, Color bg, boolean bold) {
    insertLines(s, new Style(id, fg, bg, bold));
  }
  
  @Override
  public void addText(String s, Style style) {
    insertLines(s, style);
  }
  
  int indexOfAny(String str, char[] chars) {
    return indexOfAny(str, chars, 0);
  }
  
  int indexOfAny(String str, char[] chars, int fromIx) {
    if (str == null || str.length() <= fromIx) return -1;
    for (int i = fromIx; i < str.length(); i++) {
      char c = str.charAt(i);
      for (int j = 0; j < chars.length; j++) {
        if (chars[j] == c) {
          return i;
        }
      }
    }
    return -1;
  }
  
  final static char[] LF_CHARS = {'\n', '\r'};
  
  // break input on \n and \r
  protected void insertLines(String s, Style style) {
    blinkCursor = true;
    synchronized (doc.lines) {
      int offs = indexOfAny(s, LF_CHARS);
      if (offs < 0) {
        insertLine(s, style);
      } else {
        int prevOffs = 0;
        while (offs >= 0) {
          String line = s.substring(prevOffs, offs);
          insertLine(line, style);
          prevOffs = offs + 1;
          offs = indexOfAny(s, LF_CHARS, prevOffs);
          char c = s.charAt(prevOffs - 1);
          if (prevOffs > 0) {
            if (c == '\n') {
              nextRow();
            } else if (c == '\r') {
              curCol = 0;
            }
          }
        }
        if (prevOffs < s.length()) {
          String line = s.substring(prevOffs);
          insertLine(line, style);
        }
      }
    }
  }

  // break line on max column width
  protected void insertLine(String s, Style style) {
    int strlen = s.length();
    int offs = 0;
    do {
      if (curCol >= dimCols) {
        // end of row, new line
        //System.out.println("EOR: nextRow");
        nextRow();
      }
      int rem = Math.min(strlen - offs, dimCols - curCol);
      rem = rem < 0 ? 0 : rem;
      if (rem > 0) {
        insertString(s.substring(offs, offs + rem), style);
      }
      offs += rem;
    } while (offs < strlen);
  }
  
  // replace line at cursor position
  protected void insertString(String s, Style style) {
    curRow = Math.min(curRow, dimRows);
    //System.out.println("INSERT @ " + curCol + "," + curRow);
    int lineCount = doc.countLines();

    // if cursor beyond document rows, fill up
    while (curRow >= lineCount) {
      //System.out.println("rfill " + curRow + " < " + lineCount);
      int oldCurRow = curRow;
      nextRow();
      curRow = oldCurRow;
      lineCount++;
    }
    
    lineCount = doc.countLines()-1;
    int lineNbr = getLineNbrAtCursor();
    if (lineNbr > lineCount) {
      // at end of document, need a newline
      //System.out.println("NL: eod");
      int oldCurRow = curRow;
      newline();
      lineCount++;
      curRow = oldCurRow;
    }
    Line l = doc.lines.get(lineNbr);
    int lineLen = l.len;

    System.out.println("inserting text at docrow " + lineNbr + " of " + lineCount + " (" + (l == null ? "null" : l.len) + ") cursor " + 
        curCol + "," + curRow + ", " + countLines() + " docrows (" + s + ")");
    
    if (l == null || curCol > lineLen) {
      // cursor beyond line length, fill up
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < curCol - lineLen; i++) {
        sb.append(' ');
      }

      s = sb.toString() + s;
      curCol = lineLen;
    }
    
    int strlen = s.length();
    String oldStr = l.string;
    String newStr = oldStr.substring(0, Math.min(oldStr.length(), curCol));
    newStr += s;
    if (curCol + strlen < l.len) {
      newStr += oldStr.substring(curCol + s.length());
    }
    doc.replaceLine(lineNbr, newStr);
    doc.removeStylesByLineAndOffset(lineNbr, curCol, curCol + strlen);
    if (style != null) {
      doc.addStyleByLineAndOffset(style, lineNbr, curCol, curCol + strlen - 1);
      repaint();
    }
    curCol += s.length();
  }
  
  protected void nextRow() {
    boolean nl = false;
    if (curRow < dimRows-1) {
      curRow++;
    } else {
      nl = true;
    }
    if (nl || getLineNbrAtCursor() >= doc.countLines()) {
      //System.out.println("NL: nextrow");
      doc.addLine(new Line());
      doc.fireOnDocChanged();
    }
    curCol = 0;
  }
  
  
  protected void newline() {
    doc.addLine(new Line());
    doc.fireOnDocChanged();
    curCol = 0;
    if (curRow < dimRows-1) {
      curRow++;
    }
  }

  @Override
  public void paint(Graphics og) {
    super.paint(og);
    if (drawCursor && blinkCursor) {
      Graphics2D g = (Graphics2D)og;
      g.setXORMode(getBackground());
      g.setColor(getForeground());
      int y = getLineNbrAtCursor() * fontHPx;
      int x = curCol * fontWPx;
      g.fillRect(x, y+1, fontWPx, fontHPx);
    }
  }
}
