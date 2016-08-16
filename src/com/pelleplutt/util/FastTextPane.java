/*
 Copyright (c) 2016, Peter Andersson pelleplutt1976@gmail.com

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

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.WindowConstants;

public class FastTextPane extends JPanel {
  private static final long serialVersionUID = -4049699159411811667L;
  public static final int COLOR_SEL_OVERRIDE = 0;
  public static final int COLOR_SEL_MERGE = 1;
  public static final int COLOR_SEL_ADDITIVE = 2;
  
  int selectionMeld = COLOR_SEL_MERGE;
  
  int selectedStartOffset = -1;
  int selectedEndOffset = -1;
  int anchorOffset = -1;
  int longestStringWidth = 0;
  List<Line> lines = new ArrayList<Line>();
  int len = 0;
  int fontHPx;
  Style selectionStyle = new Style();


  public FastTextPane() {
    fontHPx = getFontMetrics(getFont()).getHeight();
    recalcSize();
    addKeyBindings();
    addMouseBindings();
    selectionStyle.fg = null;//Color.black;
    selectionStyle.bg = new Color(0,0,200);
    selectionStyle.bold = false;
  }
  
  public String getText() {
    StringBuilder sb = new StringBuilder();
    synchronized (lines) {
      for (Line line : lines) {
        sb.append(line.string);
        if (line.nl) sb.append('\n');
      }
    }
    
    return sb.toString();
  }
  
  public String getSelectedText() {
    if (selectedStartOffset >= 0 && selectedEndOffset > selectedStartOffset) {
      return getText().substring(selectedStartOffset, Math.min(len, selectedEndOffset));
    } else {
      return null;
    }
  }
  
  public void setText(String s) {
    synchronized (lines) {
      clear();
      addText(s);
    }
    recalcSize();
    repaint();
  }
  
  public void addText(String s, int id, Color fg, Color bg, boolean bold) {
    if (s == null) return;
    int prevLen = len;
    synchronized (lines) {
      int prevOffs = 0;
      int offs = s.indexOf('\n');
      if (offs < 0) {
        appendLine(s);
      } else {
        while (offs >= 0) {
          appendLine(s.substring(prevOffs, offs));
          prevOffs = offs + 1;
          offs = s.indexOf('\n', prevOffs);
          if (prevOffs > 0 && s.charAt(prevOffs-1) == '\n') {
            addLine(new Line());
          }
        }
        if (prevOffs < s.length()) {
          appendLine(s.substring(prevOffs));
        }
        //if (s.endsWith("\n")) {
        //  addLine(new Line());
        //}
      }
    }
    if (id != Integer.MIN_VALUE) {
      addStyleByOffset(id, fg, bg, bold, prevLen, len);
    }
    recalcSize();
    repaint();
  }
  
  public void addText(String s) {
    addText(s, Integer.MIN_VALUE, null, null, false);
  }
  
  protected void addLine(Line l) {
    synchronized (lines) {
      // add nl to previous line
      if (!lines.isEmpty()) {
        Line preLine = lines.get(lines.size()-1); 
        preLine.len++;
        preLine.nl = true;
        len++;
      }
      l.offs = len;
      lines.add(l);
      len += l.len;
      int w = getFontMetrics(getFont()).stringWidth(l.string);
      if (w > longestStringWidth) longestStringWidth = w;
    }
  }
  
  protected void appendLine(String s) {
    synchronized (lines) {
      Line l;
      if (lines.isEmpty()) {
        l = new Line();
        lines.add(l);
      } else {
        l = lines.get(countLines() - 1);
      }
      l.len += s.length();
      len += s.length();
      l.string += s;
      int w = getFontMetrics(getFont()).stringWidth(l.string);
      if (w > longestStringWidth) longestStringWidth = w;
    }
  }
  
  public void select(int startOffs, int endOffs) {
    if (startOffs > len || endOffs < startOffs) return;
    selectedStartOffset = Math.max(0, startOffs);
    selectedEndOffset = Math.min(len, endOffs);
    repaint();
  }
  
  public void unselect() {
    selectedStartOffset = -1;
    selectedEndOffset = -1;
    repaint();
  }
  
  public void addStyleByOffset(int styleId, Color fg, Color bg, boolean bold, int startOffs, int endOffs) {
    if (startOffs > len || endOffs < startOffs) return;
    startOffs = Math.max(0, startOffs);
    endOffs = Math.min(len, endOffs);
    int curOffs = startOffs;
    int curLine = getLineNumberByOffset(startOffs);
    while (curOffs < len && curOffs < endOffs) {
      Line line = lines.get(curLine);
      if (line.offs + line.len > startOffs) {
        Style s = new Style();
        s.id = styleId;
        s.fg = fg;
        s.bg = bg;
        s.bold = bold;
        s.lineStartOffs = Math.max(0, startOffs - line.offs);
        s.lineEndOffs = Math.min(line.len, endOffs - line.offs - 1);
        line.addStyle(s);
      }
      curOffs = line.offs + line.len;
      curLine++;
    }
    repaint();
  }
  
  public void addStyleByLine(int styleId, Color fg, Color bg, boolean bold, int startLine, int endLine) {
    if (startLine >= countLines() || endLine < startLine) return;
    synchronized(lines) {
      startLine = Math.max(0, startLine);
      endLine = Math.min(countLines()-1, endLine);
      for (int l = startLine; l <= endLine; l++) {
        Line line = lines.get(l);
        Style s = new Style();
        s.id = styleId;
        s.fg = fg;
        s.bg = bg;
        s.bold = bold;
        s.lineStartOffs = 0;
        s.lineEndOffs = line.len;
        line.addStyle(s);
      }
    }
    repaint();
  }
  
  public void addStyleByLineAndOffset(int styleId, Color fg, Color bg, boolean bold, int lineNbr, int startOffs, int endOffs) {
    if (lineNbr >= countLines() || lineNbr < 0) return;
    synchronized(lines) {
      Line line = lines.get(lineNbr);
      Style s = new Style();
      s.id = styleId;
      s.fg = fg;
      s.bg = bg;
      s.bold = bold;
      s.lineStartOffs = startOffs;
      s.lineEndOffs = endOffs;
      line.addStyle(s);
    }
    repaint();
  }
  
  public void removeStyle(int styleId) {
    synchronized (lines) {
      for (Line line : lines) {
        if (line.styles != null) {
          for (int i = line.styles.size()-1; i >= 0 ; i--) {
            if (line.styles.get(i).id == styleId) {
              line.styles.remove(i);
            }
          }
        }
      }
    }
    repaint();
  }
  
  public int countLines() {
    return lines.size();
  }
  
  public int getTextLength() {
    return len;
  }
  
  public String getTextAtLine(int line) {
    if (line >= 0 && line < countLines()) {
      return lines.get(line).string;
    } else {
      return null;
    }
  }
  
  public void clear() {
    synchronized (lines) {
      lines.clear();
      len = 0;
      longestStringWidth = 0;
    }
  }
  
  public void setFont(Font font) {
    super.setFont(font);
    fontHPx = getFontMetrics(getFont()).getHeight();
    if (lines != null) {
      synchronized (lines) {
        longestStringWidth = 0;
        for (Line line : lines) {
          int w = getFontMetrics(getFont()).stringWidth(line.string);
          if (w > longestStringWidth) longestStringWidth = w;
        }
      }
      repaint();
    }
  }
   
  public void setBackground(Color bg) {
    super.setBackground(bg);
    repaint();
  }
  
  public void setForeground(Color fg) {
    super.setForeground(fg);
    repaint();
  }
  
  public void setSelectionForeground(Color sfg) {
    selectionStyle.fg = sfg;
    repaint();
  }
  
  public void setSelectionBackground(Color sbg) {
    selectionStyle.bg = sbg;
    repaint();
  }
  
  public void setSelectionStyle(int style) {
    selectionMeld = style;
    repaint();
  }
  
  public void scrollToOffset(int offs) {
    JScrollPane scrlP = getScroll();
    if (scrlP == null) return;
    Point p = getCoordinatesForOffset(offs);
    if (p == null) return;
    int w = scrlP.getViewport().getWidth();
    int h = scrlP.getViewport().getHeight();
    Rectangle r = new Rectangle(p.x - w/4, p.y - h/4, w/2, h/2);
    scrlP.getHorizontalScrollBar().setValue(r.x);
    scrlP.getVerticalScrollBar().setValue(r.y);
    //scrlP.getViewport().scrollRectToVisible(r);
  }
  
  public void scrollToLineNumber(int lineNbr) {
    JScrollPane scrlP = getScroll();
    if (scrlP == null) return;
    lineNbr = Math.max(0, Math.min(lineNbr, countLines()-1));
    int w = scrlP.getViewport().getWidth();
    int h = scrlP.getViewport().getHeight();
    Rectangle r = new Rectangle(0, getYForLineNumber(lineNbr) - h/4, w/2, h/2);
    scrlP.getHorizontalScrollBar().setValue(r.x);
    scrlP.getVerticalScrollBar().setValue(r.y);
    //scrlP.getViewport().scrollRectToVisible(r);
  }
  
  public void scrollLinesRelative(int lines) {
    JScrollPane scrlP = getScroll();
    if (scrlP == null) return;
    int amount = (scrlP.getVerticalScrollBar().getMaximum() * fontHPx) / getTotalHeightPx();
    scrlP.getVerticalScrollBar().setValue(scrlP.getVerticalScrollBar().getValue() + lines * amount);
  }
  
  public int getYForLineNumber(int lineNbr) {
    return lineNbr * fontHPx;
  }
  
  public int getLineNumberAt(int y) {
    return y / fontHPx;
  }
  
  public int getLineLength(int lineNbr) {
    if (lineNbr < 0 || lineNbr >= countLines()) return 0;
    Line line = lines.get(lineNbr);
    return line.len;
  }
  
  public int getLineOffset(int lineNbr) {
    if (lineNbr < 0 || lineNbr >= countLines()) return 0;
    Line line = lines.get(lineNbr);
    return line.offs;
  }
  
  public String getLineByOffset(int offset) {
    return getLineByLineNumber(getLineNumberByOffset(offset));
  }
  
  public String getLineByLineNumber(int lineNbr) {
    if (lineNbr < 0 || lineNbr >= countLines()) return null;
    Line line = lines.get(lineNbr);
    return line.string;
  }
  
  public Point getCoordinatesForOffset(int offs) {
    int lineNbr = getLineNumberByOffset(offs);
    if (lineNbr < 0 || lineNbr >= countLines()) return null;
    Line line = lines.get(lineNbr);
    int strOffs = offs - line.offs;
    String s = line.string.substring(0, Math.min(strOffs, line.len-1));
    int x = getFontMetrics(getFont()).stringWidth(s);
    return new Point(x, getYForLineNumber(lineNbr));
  }
  
  public int getLineNumberByOffset(int offs) {
    // binary search
    // extend length to power of 2
    final int lineCnt = countLines();
    if (offs >= len) return lineCnt; 
    int hsb = 31;
    while (hsb > 0 && (lineCnt & (1 << hsb)) == 0) {
      hsb--;
    }
    int lineCnt2 = (1 << (hsb+1));
    int curStep = lineCnt2/2;
    int curLine = lineCnt2/2;
    Line line;
    do {
      int l = Math.max(0, Math.min(lineCnt-1, curLine));
      line = lines.get(l);
      
      if (offs >= line.offs && offs < line.offs + line.len) {
        break;
      }
      
      if (offs < line.offs) {
        curLine -= curStep;
      } else {
        curLine += curStep;
      }
      curStep /= 2;
      if (curStep == 0) curStep = 1;
    } while (true);
    
    return Math.max(0, Math.min(lineCnt-1, curLine));
  }
  
  public int getOffsetAt(int x, int y) {
    if (x < 0) x = 0;
    if (y < 0) y = 0;
    int lineNbr = y / fontHPx;
    if (lineNbr >= countLines()) return len;
    Line line = lines.get(lineNbr);
    int linelen = line.string.length();

    int fullLineW = getFontMetrics(getFont()).stringWidth(line.string);
    if (x >= fullLineW) {
      return line.offs + linelen;
    }
    
    // binary search
    // extend length to power of 2
    int hsb = 31;
    while (hsb > 0 && (linelen & (1 << hsb)) == 0) {
      hsb--;
    }
    int linelen2 = (1 << (hsb+1));
    int curStep = linelen2/2;
    int curOffs = linelen2/2;
    int killing = 0;
    int offs;
    do {
      offs = Math.max(0, Math.min(linelen-1, curOffs));
      int startX = getFontMetrics(getFont()).stringWidth(line.string.substring(0, offs));
      int width = getFontMetrics(getFont()).charWidth(line.string.charAt(offs));
      if (x >= startX && x < startX + width) {
        // fixpoint, break early
        break;
      }
      if (x < startX) {
        curOffs -= curStep;
      } else {
        curOffs += curStep;
      }
      curStep /= 2;
      if (curStep == 0) {
        // run an extra iteration with curStep = 1 to get a proper fix
        curStep = 1;
        killing++;
      }
    } while (killing < 2);
    
    return Math.min(line.offs + line.len - 1, line.offs + offs);
  }
  
  Dimension __d = new Dimension();
  protected void recalcSize() {
    int h = countLines() * fontHPx;
    int w = longestStringWidth;
    __d.width = w;
    __d.height = h;
    setMinimumSize(__d);
    setPreferredSize(__d);
    setSize(__d);
  }
  
  protected JScrollPane getScroll() {
    Container c = getParent();
    if (c != null && !(c instanceof JViewport)) {
      return null;
    }
    c = c.getParent();
    if (c != null && !(c instanceof JScrollPane)) {
      return null;
    }
    JScrollPane scrlPane = ((JScrollPane)c);
    return scrlPane;
  }
  
  
  public void paint(Graphics og) {
    Graphics2D g = (Graphics2D)og;
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
        RenderingHints.VALUE_ANTIALIAS_ON);
    g.setFont(getFont());
    g.setColor(getBackground());
    g.fillRect(0, 0, getWidth(), getHeight());
    g.setColor(getForeground());
    final Rectangle visRect = getVisibleRect();
    int visLine = visRect.y / fontHPx;
    int y = visLine * fontHPx;
    final int visMaxY = visRect.y + visRect.height;
    final int maxLine = countLines();
    while (y < visMaxY && visLine < maxLine) {
      Line l = lines.get(visLine);
      paintLineShards(g, y, l);
      visLine++;
      y += fontHPx;
    }
  }
  
  private Color colMerge(Color colStyle, Color colSel) {
    if (colSel == null) {
      return colStyle;
    } else if (colStyle != null) {
      return new Color(
          (colStyle.getRed() + colSel.getRed()) / 2,
          (colStyle.getGreen() + colSel.getGreen()) / 2,
          (colStyle.getBlue() + colSel.getBlue()) / 2
          );
    } else {
      return colSel;
    }
  }
  
  private Color colAdditive(Color colStyle, Color colSel) {
    if (colSel == null) {
      return colStyle;
    } else if (colStyle != null) {
      return new Color(
          Math.min(255, (colStyle.getRed() + colSel.getRed())),
          Math.min(255, (colStyle.getGreen() + colSel.getGreen())),
          Math.min(255, (colStyle.getBlue() + colSel.getBlue()))
          );
    } else {
      return colSel;
    }
  }
  
  Style shardStyle = new Style();
  boolean shardSelected = false;
  
  protected void paintLineShards(Graphics2D g, int y, Line line) {
    selectionStyle.lineStartOffs = selectedStartOffset - line.offs;
    selectionStyle.lineEndOffs = selectedEndOffset - line.offs;
    int curOffs = 0;
    int x = 0;
    while (curOffs < line.len) {
      shardStyle.fg = null;
      shardStyle.bg = null;
      shardStyle.bold = false;
      shardSelected = false;
      int nextOffs = findShardStyle(line, curOffs);
      int strNextOffs = Math.min(line.nl ? line.len - 1 : line.len, nextOffs);
      String shardStr = line.string.substring(curOffs, strNextOffs);
      final int shardTextW = getFontMetrics(getFont()).stringWidth(shardStr);
      Color fg = null;
      Color bg = null;
      boolean bold = false;

    if (!shardSelected) {
        // no selection
        fg = shardStyle.fg;
        bg = shardStyle.bg;
        bold = shardStyle.bold;
      } else {
        // selected
        switch (selectionMeld) {
        case COLOR_SEL_MERGE:
          fg = colMerge(shardStyle.fg, selectionStyle.fg);
          bg = colMerge(shardStyle.bg, selectionStyle.bg);
          break;
        case COLOR_SEL_ADDITIVE:
          fg = colAdditive(shardStyle.fg, selectionStyle.fg);
          bg = colAdditive(shardStyle.bg, selectionStyle.bg);
          break;
        default:
          fg = selectionStyle.fg == null ? shardStyle.fg : selectionStyle.fg; 
          bg = selectionStyle.bg == null ? shardStyle.bg : selectionStyle.bg;
          break;
        }
        bold = shardStyle.bold || selectionStyle.bold;
      }
      
      if (fg == null) fg = getForeground();
      if (bg != null) {
        g.setColor(bg);
        if (!line.nl || nextOffs < line.len) {
          g.fillRect(x, y + 1, shardTextW + 1, fontHPx);
        } else {
          g.fillRect(x, y + 1, getWidth(), fontHPx);
        }
      }
      g.setColor(fg);
      g.drawString(shardStr, x, y + fontHPx - 1);
      if (bold) {
        g.drawString(shardStr, x + 1, y + fontHPx - 1);
      }

      x += shardTextW;
      curOffs = nextOffs;
    }
  }
  
  /**
   * Searches all styles for given line @ given offset, sets shardStyle accordingly
   * @param line
   * @param offs
   * @return next offset
   */
  protected int findShardStyle(Line line, int offs) {
    int selNextOffs = Integer.MAX_VALUE;
    int styleNextOffs = Integer.MAX_VALUE;
    // check selection
    if (selectionStyle.lineEndOffs < offs || selectionStyle.lineStartOffs >= line.len) {
      // no selection
      selNextOffs = line.len;
    } else {
      // selection
      if (offs < selectionStyle.lineStartOffs) {
        // unselected first part of line
        selNextOffs = selectionStyle.lineStartOffs;
      } else {
        if (selectionStyle.lineEndOffs < line.len) {
          if (offs < selectionStyle.lineEndOffs) {
            // selected end part of line
            shardSelected = true;
            selNextOffs = selectionStyle.lineEndOffs;
          } else {
            // unselected end part of line
            selNextOffs = line.len;
          }
        } else {
          // selecting whole rest of line
          shardSelected = true;
          selNextOffs = line.len;
        }
      }
    }
    // check styles
    if (line.styles != null) {
      // pass thru all styles, find the one with nearest offset and/or minimum length
      int minOffs = Integer.MAX_VALUE;
      int minLen = Integer.MAX_VALUE;
      int minLenOffs = Integer.MAX_VALUE;
      
      for (Style style : line.styles) {
        if (offs > style.lineEndOffs) {
          // processed style
          continue;
        }
        if (offs >= style.lineStartOffs) {
          // active style
          int styleLen = style.lineEndOffs - style.lineStartOffs; 
          if (styleLen < minLen) {
            minLen = styleLen;
            minLenOffs = style.lineEndOffs + 1;
            if (style.fg != null) shardStyle.fg = style.fg; 
            if (style.bg != null) shardStyle.bg = style.bg;
            shardStyle.bold |= style.bold;
          }
        } else {
          // upcoming style
          if (style.lineStartOffs < minOffs) {
            minOffs = style.lineStartOffs;
          }
        }
      } // per style
      
      styleNextOffs = Math.min(line.len, Math.min(minLenOffs, minOffs));
    }
    return Math.min(line.len, Math.min(selNextOffs, styleNextOffs));
  }
  
  protected int getTotalHeightPx() {
    return countLines() * fontHPx;
  }

  protected static class Style {
    public Style(Color fg, Color bg, boolean bold) {
      this.fg = fg;
      this.bg = bg;
      this.bold = bold;
    }
    public Style() {
    }
    public int id;
    Color fg, bg;
    boolean bold;
    int lineStartOffs, lineEndOffs;
  }
  
  protected class Line {
    String string;
    List<Style> styles;
    int offs;
    int len;
    boolean nl;

    public Line() {
      string = "";
      this.offs = FastTextPane.this.len;
      len = 0;
    }
    
    public void addStyle(Style s) {
      if (styles == null) {
        styles = new ArrayList<Style>();
      }
      styles.add(s);
    }
    
    public void removeStyle(Style s) {
      styles.remove(s);
      if (styles.isEmpty()) {
        styles = null;
      }
    }
  }
  
  protected void addKeyBindings() {
    ActionMap actionMap = getActionMap();

    getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_UP, 0), "pageup");
    getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_DOWN, 0), "pagedown");
    getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "lineup");
    getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "linedown");
    getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), "copy");

    actionMap.put("pageup", new AbstractAction() {
      private static final long serialVersionUID = -1443733874776516263L;
      @Override
      public void actionPerformed(ActionEvent e) {
        JScrollPane scrlP = getScroll();
        if (scrlP == null) return;
        int amount = (scrlP.getVerticalScrollBar().getMaximum() * getVisibleRect().height) / getTotalHeightPx();
        scrlP.getVerticalScrollBar().setValue(scrlP.getVerticalScrollBar().getValue() - amount);
      }
    });
    actionMap.put("pagedown", new AbstractAction() {
      private static final long serialVersionUID = -8653322027672633784L;
      @Override
      public void actionPerformed(ActionEvent e) {
        JScrollPane scrlP = getScroll();
        if (scrlP == null) return;
        int amount = (scrlP.getVerticalScrollBar().getMaximum() * getVisibleRect().height) / getTotalHeightPx();
        scrlP.getVerticalScrollBar().setValue(scrlP.getVerticalScrollBar().getValue() + amount);
      }
    });
    actionMap.put("lineup", new AbstractAction() {
      private static final long serialVersionUID = -1062546522262665070L;
      @Override
      public void actionPerformed(ActionEvent e) {
        scrollLinesRelative(-1);
      }
    });
    actionMap.put("linedown", new AbstractAction() {
      private static final long serialVersionUID = 3150021404117024757L;
      @Override
      public void actionPerformed(ActionEvent e) {
        scrollLinesRelative(1);
      }
    });
    actionMap.put("copy", new AbstractAction() {
      private static final long serialVersionUID = 8906214459680671442L;
      @Override
      public void actionPerformed(ActionEvent e) {
        String cpyBuffer = getSelectedText();
        if (cpyBuffer != null) {
          StringSelection stringSelection = new StringSelection(cpyBuffer);
          Clipboard clpbrd = Toolkit.getDefaultToolkit().getSystemClipboard();
          clpbrd.setContents(stringSelection, null);
        }
      }
    });
  }
  
  protected void addMouseBindings() {
    addMouseListener(mouseEventReceiver);
    addMouseMotionListener(mouseEventReceiver);
    addMouseWheelListener(mouseEventReceiver);
    
  }

  final MouseAdapter mouseEventReceiver = new MouseAdapter() {
    @ Override
    public void mousePressed(MouseEvent e) {
      anchorOffset = -1;
      if (e.getButton() == MouseEvent.BUTTON1) {
        selectedStartOffset = getOffsetAt(e.getX(), e.getY());
        anchorOffset = selectedStartOffset; 
        selectedEndOffset = anchorOffset;
        repaint();
      }
    }
    
    @ Override
    public void mouseDragged(MouseEvent e) {
      if (anchorOffset >= 0) {
        int offset = getOffsetAt(e.getX(), e.getY());
        if (offset == anchorOffset) {
          selectedStartOffset = anchorOffset;
          selectedEndOffset = anchorOffset + 1;
        } else if (offset < anchorOffset) {
          selectedStartOffset = offset;
          selectedEndOffset = anchorOffset;
        } else {
          selectedStartOffset = anchorOffset;
          selectedEndOffset = offset + 1;
        }
        JScrollPane p = getScroll();
        if (p != null) {
          int y = e.getY() - p.getViewport().getViewPosition().y;
          if (y < 0) {
            scrollLinesRelative(y / fontHPx);
          } else if (y > p.getViewport().getHeight()) {
            scrollLinesRelative((y - p.getViewport().getHeight()) / fontHPx);
          }
        }
      }
      repaint();
    }
    
    @ Override
    public void mouseWheelMoved(MouseWheelEvent e) {
      JScrollPane scrlP = getScroll();
      if (scrlP != null) {
        int amount = (scrlP.getVerticalScrollBar().getMaximum() * fontHPx) / getTotalHeightPx();
        scrlP.getVerticalScrollBar().setValue(scrlP.getVerticalScrollBar().getValue() + 4 * amount * e.getWheelRotation());
      }
    }
  };
  
  public static void main(String[] args) {
    JFrame f = new JFrame();
    f.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    f.getContentPane().setLayout(new BorderLayout());
    FastTextPane ftp = new FastTextPane();
    f.setSize(600, 400);
    f.setLocationByPlatform(true);
    
    
    f.getContentPane().add(
        new JScrollPane(ftp, 
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED));
    
    ftp.setForeground(new Color(255,255,192));
    ftp.setBackground(Color.black);
    ftp.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
    String text = AppSystem.readFile(new File("/home/petera/proj/gateway/generic/blost.c"));
    //String text = AppSystem.readFile(new File(("/home/petera/proj/gw-tools/simprof/src/com/pelleplutt/util/FastTextPane.java"))); 
    //String text = AppSystem.readFile(new File(("/home/petera/bible.txt")));
    ftp.setText(text);
//    int lineNbr = 0;
//    int sumLen = 0;
//    for (Line l : ftp.lines) {
//      System.out.format("line %-4d  offs:%-6d  len:%-3d  nxt:%-6d  %s\n", lineNbr++, l.offs, l.len, l.offs + l.len, l.string);
//      sumLen += l.len;
//    }
//    System.out.println("sum text length:" + sumLen);
    System.out.println("text raw length:" + text.length());
    System.out.println("ftp text length:" + ftp.len);
    System.out.println("getText length :" + ftp.getText().length());
    
    int offset = 0;
    String keyword = "line";
    while ((offset = text.indexOf(keyword, offset)) >= 0) {
      ftp.addStyleByOffset(1, Color.magenta, null, true, offset, offset + keyword.length());
      offset++;
    }
    
    
    f.setVisible(true);
  }
}
