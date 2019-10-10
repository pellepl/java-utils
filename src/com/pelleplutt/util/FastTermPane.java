package com.pelleplutt.util;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;

public class FastTermPane extends FastTextPane implements KeyListener {
  int dimCols;
  int dimRows;
  int curCol;
  int curRow;
  int fixLongestStringWidth = 8;
  int fontWPx;
  boolean drawCursor = true;
  volatile boolean blinkCursor = true;
  int scrRowMin = 0;
  int scrRowMax = 0;
  boolean terminal = false;
  KeyListener keyListener;
  boolean dbg = false;

  public FastTermPane() {
    super();
    dimCols = 80;
    dimRows = 25;
    addKeyListener(this);
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
  
  public void setScrollArea(int minRow, int maxRow) {
    minRow = Math.max(0, Math.min(dimRows - 1, minRow));
    maxRow = Math.max(0, Math.min(dimRows - 1, maxRow));
    scrRowMin = minRow;
    scrRowMax = maxRow;
    if (dbg) System.out.println("scrlset " + minRow + "--" + maxRow);
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
    if (terminal) {
      longestStringWidth = fixLongestStringWidth;
    }
    super.recalcSize();
  }
  
  @Override
  protected void onCleared() {
    super.onCleared();
    scrRowMax = scrRowMin = -1;
    setCursor(0,0);
  }
   
  protected int getLineNbrAtCursor() {
    int lineCount = doc.countLines()-1;
    int dimRowsMin = Math.min(lineCount, dimRows);
    return lineCount - (dimRowsMin - curRow);
  }
  
  @Override
  public void addText(String s) {
    if (terminal) {
      insertLines(s, null);
    } else {
      super.addText(s);
    }
  }
  
  @Override
  public void addText(String s, int id, Color fg, Color bg, boolean bold) {
    if (terminal) {
      insertLines(s, new Style(id, fg, bg, bold));
    } else {
      super.addText(s, id, fg, bg, bold);
    }
  }
  
  @Override
  public void addText(String s, Style style) {
    if (terminal) {
      insertLines(s, style);
    } else {
      super.addText(s, style);
    }
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
            curCol = 0;
            if (c == '\n') {
              if (dbg) System.out.println("newline");
              nextRow();
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
        nextRow();
        curCol = 0;
      }
      int rem = Math.min(strlen - offs, dimCols - curCol);
      rem = rem < 0 ? 0 : rem;
      if (rem > 0) {
        modifyLine(s.substring(offs, offs + rem), style);
      }
      offs += rem;
    } while (offs < strlen);
  }
  
  // replace line at cursor position by inserting new text at cursor
  protected void modifyLine(String s, Style style) {
    ensureLinesAtCursor();
    int lineNbr = getLineNbrAtCursor();

    Line l = doc.lines.get(lineNbr);
    int lineLen = l.string.length();
    int strlen = s.length();
    int styleStart = curCol;
    int styleEnd = curCol + strlen;

    if (dbg) System.out.println("inserting text at docrow " + lineNbr + " (" + (l == null ? "null" : l.len) + ") cursor " + 
        curCol + "," + curRow + ", " + countLines() + " docrows (" + s + ")");
    String pre;
    if (l == null || curCol > lineLen) {
      // cursor beyond line length, fill up
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < curCol - lineLen; i++) {
        sb.append(' ');
      }
      s = getCharString(' ', curCol - lineLen) + s;
      curCol = lineLen;
      pre = l.string;
    } else {
      pre = l.string.substring(0, Math.min(lineLen, curCol));
    }
    
    String oldStr = l.string;
    String newStr = pre; 
    newStr += s;
    if (curCol + strlen < l.len) {
      newStr += oldStr.substring(curCol + s.length());
    }
    doc.replaceLine(lineNbr, newStr);
    doc.removeStylesByLineAndOffset(lineNbr, styleStart, styleEnd-1);
    if (style != null) {
      doc.addStyleByLineAndOffset(style, lineNbr, styleStart, styleEnd-1);
    }
    curCol += s.length();
  }
  
  protected void ensureLinesAtCursor() {
    curRow = Math.min(curRow, dimRows);
    int lineCount = doc.countLines();
  
    // if cursor beyond document rows, fill up
    while (curRow >= lineCount) {
      int oldCurRow = curRow;
      nextRow();
      curRow = oldCurRow;
      lineCount++;
    }
    
    lineCount = doc.countLines()-1;
    int lineNbr = getLineNbrAtCursor();
    if (lineNbr > lineCount) {
      // at end of document, need a newline
      int oldCurRow = curRow;
      newline();
      lineCount++;
      curRow = oldCurRow;
    }
  }
  
  public void eraseLineFull() {
    synchronized (doc.lines) {
      ensureLinesAtCursor();
      int lineNbr = getLineNbrAtCursor();
      //Line l = doc.lines.get(lineNbr);
      doc.replaceLine(lineNbr, "");
      doc.removeStylesOnLine(lineNbr);
      curCol = 0;
    }
  }
  
  public void eraseLineBefore() {
    synchronized (doc.lines) {
      ensureLinesAtCursor();
      int lineNbr = getLineNbrAtCursor();
      Line l = doc.lines.get(lineNbr);
      String post = "";
      if (curCol < l.string.length()) {
        post = l.string.substring(curCol);
      }
      doc.replaceLine(lineNbr, getCharString(' ', curCol) + post);
      doc.removeStylesByLineAndOffset(lineNbr, 0, curCol); 
    }
  }
  
  public void eraseLineAfter() {
    synchronized (doc.lines) {
      ensureLinesAtCursor();
      int lineNbr = getLineNbrAtCursor();
      Line l = doc.lines.get(lineNbr);
      String pre = l.string.substring(0, Math.min(l.string.length(), curCol));
      doc.replaceLine(lineNbr, pre);
      doc.removeStylesByLineAndOffset(lineNbr, curCol, l.len); 
    }
  }
  
  public void deleteChars(int chars) {
    synchronized (doc.lines) {
      ensureLinesAtCursor();
      int lineNbr = getLineNbrAtCursor();
      Line l = doc.lines.get(lineNbr);
      String orig = l.string;
      String frag = orig.substring(0, Math.min(orig.length(), curCol));
      if (orig.length() > curCol + chars) {
        frag += orig.substring(curCol + chars);
      }
      doc.replaceLine(lineNbr, frag);
      doc.removeStylesByLineAndOffset(lineNbr, curCol, chars); // TODO 
    }
  }

  
  public void nextRow() {
    synchronized (doc.lines) {
      boolean nl = false;
      if (scrollAreaDefined()) {
        if (curRow < scrRowMax) {
          curRow++;
        } else if (curRow == scrRowMax) {
          if (dbg) System.out.println("nextRow.scroll +1");
          scroll(1);
        } else {
          nl = true;
        }
      } else {
        if (curRow < dimRows-1) {
          curRow++;
        } else {
          nl = true;
        }
      }
      if (nl || getLineNbrAtCursor() >= doc.countLines()) {
        doc.addLine(new Line());
        doc.fireOnDocChanged();
      }
    }
  }
  
  public void prevRow() {
    if (scrollAreaDefined()) {
      if (dbg) System.out.println("prevRow curRow:" + curRow + " " + scrRowMin);
      if (curRow <= scrRowMin) {
        if (dbg) System.out.println("prevRow.scroll -1");
        scroll(-1);
      } else {
        curRow--;
      }
    } else {
      curRow--;
    }
    curRow = Math.max(0, curRow);
  }
  
  void newline() {
    boolean nl = true;
    if (scrollAreaDefined()) {
      if (curRow < scrRowMax) {
        if (dbg) System.out.println("newline.scroll +1");
        scroll(1);
        nl = false;
      }
    } 
    if (nl) {
      doc.addLine(new Line());
      doc.fireOnDocChanged();
      if (curRow < dimRows-1) {
        curRow++;
      }
    }
    curCol = 0;
  }
  
  protected boolean scrollAreaDefined() {
    return scrRowMax > 0 && scrRowMax > scrRowMin;
  }
  
  public void scroll(int relativeLines) {
    if (relativeLines == 0 || !scrollAreaDefined()) {
      return;
    }
    
    synchronized (doc.lines) {
      int lineCount = doc.countLines()-1;
      int dimRowsMin = Math.min(lineCount, dimRows);
      int lineMin =  lineCount - (dimRowsMin - scrRowMin);
      int lineMax =  lineCount - (dimRowsMin - scrRowMax);
      if (lineMin >= lineMax) return;
      
      while (lineMax > lineCount) {
        doc.addLine(new Line());
        lineCount++;
      }
      
      int offs;
      int lenDelta = 0;
      if (relativeLines > 0) {
        int curLine = lineMin;
        offs = doc.lines.get(curLine).offs;
        // scroll down (visually up), make blanks at bottom
        // 1. replace lines
        while (curLine <= lineMax - relativeLines) {
          Line src = doc.lines.get(curLine + relativeLines);
          Line dst = doc.lines.get(curLine);
          lenDelta = dst.len - src.len;
          dst.offs = offs;
          dst.len = src.len;
          dst.string = src.string;
          dst.styles = src.styles;
          dst.nl = src.nl;
          offs += dst.len;
          curLine++;
        }
        // 2. fill with blanks
        while (curLine <= lineMax) {
          Line blank = doc.lines.get(curLine);
          lenDelta = 1 - blank.len;
          blank.offs = offs;
          blank.nl = true;
          blank.len = 1;
          blank.string = "";
          blank.styles = null;
          offs += blank.len;
          curLine++;
        }
        // 3. move offset of remaining lines
        while (curLine <= lineCount) {
          doc.lines.get(curLine++).offs -= lenDelta;
        }
      } else {
        // scroll up (visually down), make blanks at top
        int curLine = lineMin;
        offs = doc.lines.get(curLine).offs;
        int curLineRev = lineMax;
        relativeLines = -relativeLines;
        // 1. replace lines
        while (curLineRev >= lineMin + relativeLines) {
          Line dst = doc.lines.get(curLineRev);
          Line src = doc.lines.get(curLineRev - relativeLines);
          lenDelta = dst.len - src.len;
          dst.len = src.len;
          dst.string = src.string;
          dst.styles = src.styles;
          dst.nl = src.nl;
          curLineRev--;
        }
        // 2. fill with blanks
        while (curLineRev >= lineMin) {
          Line blank = doc.lines.get(curLineRev);
          lenDelta = 1 - blank.len;
          blank.nl = true;
          blank.len = 1;
          blank.string = "";
          blank.styles = null;
          curLineRev--;
        }
        // 3. fix offset of all lines
        while (curLine <= lineCount) {
          Line l = doc.lines.get(curLine++);
          l.offs = offs;
          offs += l.len;
        }
      }
      // adjust document length and fire change event
      doc.len += lenDelta;
      doc.fireOnDocChanged();
    }
  }
  
  String getCharString(char c, int length) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < length; i++) {
      sb.append(c);
    }
    return sb.toString();
  }
  
  public void setTerminalMode(boolean term) {
    if (dbg) System.out.println("terminal mode : " + term);
    terminal = term;
    recalcSize();
    repaint();
  }

  public boolean isTerminalMode() {
    return terminal;
  }
  
  public void setKeyListener(KeyListener k) {
    keyListener = k;
  }

  @Override
  public void paint(Graphics og) {
    super.paint(og);
    if (terminal && drawCursor && blinkCursor) {
      Graphics2D g = (Graphics2D)og;
      g.setXORMode(getBackground());
      g.setColor(getForeground());
      int y = getLineNbrAtCursor() * fontHPx;
      int x = curCol * fontWPx;
      g.fillRect(x, y+2, fontWPx, fontHPx);
    }
  }

  @Override
  public void keyTyped(KeyEvent e) {
    if (!terminal || keyListener == null) return;
    keyListener.keyTyped(e);
  }

  @Override
  public void keyPressed(KeyEvent e) {
    if (!terminal || keyListener == null) return;
    keyListener.keyPressed(e);
  }

  @Override
  public void keyReleased(KeyEvent e) {
    if (!terminal || keyListener == null) return;
    keyListener.keyReleased(e);
  }
}
