package com.pelleplutt.util;

import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
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
import java.util.ArrayList;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.KeyStroke;

public class FastTextPane extends JPanel {
  private static final long serialVersionUID = -4049699159411811667L;
  public static final int COLOR_SEL_OVERRIDE = 0;
  public static final int COLOR_SEL_MERGE = 1;
  public static final int COLOR_SEL_ADDITIVE = 2;
  
  int selectionMeld = COLOR_SEL_MERGE;
  
  String newLine = System.getProperty("line.separator");
  boolean rectangularSelection;
  int selectedStartOffset = -1;
  int selectedEndOffset = -1;
  int anchorOffset = -1;
  int anchorRow = -1;
  int anchorX = -1;
  int selectedStartRow = -1;
  int selectedStartX = -1;
  int selectedEndRow = -1;
  int selectedEndX = -1;

  int longestStringWidth = 0;
  int fontHPx;
  Style selectionStyle = new Style();
  Doc doc;

  public FastTextPane() {
    doc = new Doc();
    doc.listeners.add(this);
    fontHPx = getFontMetrics(getFont()).getHeight();
    recalcSize();
    addKeyBindings();
    addMouseBindings();
    selectionStyle.fg = null;//Color.black;
    selectionStyle.bg = Color.gray;
    selectionStyle.bold = false;
    setFocusable(true);
    setDoubleBuffered(true);
    addMouseListener(new MouseAdapter() {
      @Override
      public void mousePressed(MouseEvent me) {
        ((JPanel) me.getSource()).requestFocusInWindow();
      }
    });
  }
  
  public Doc getDocument() {
    return doc;
  }
  
  public void setDocument(Doc document) {
    doc.listeners.remove(this);
    doc = document;
    doc.listeners.add(this);
    onDocChanged();
  }
  
  public String getText() {
    StringBuilder sb = new StringBuilder();
    synchronized (doc.lines) {
      for (Line line : doc.lines) {
        sb.append(line.string);
        if (line.nl) sb.append(newLine);
      }
    }
    
    return sb.toString();
  }
  
  public String getSelectedText() {
    if (rectangularSelection) {
      StringBuilder sb = new StringBuilder();
      for (int l = Math.min(countLines(), selectedStartRow); l <= Math.min(countLines() - 1, selectedEndRow); l++) {
        Line line = doc.lines.get(l);
        int sOffs = getLineOffsetAt(l, selectedStartX);
        int eOffs = getLineOffsetAt(l, selectedEndX);
        sb.append(line.string.substring(sOffs, eOffs));
        sb.append(newLine);
      }
      return sb.toString();
    } else {
      if (selectedStartOffset >= 0 && selectedEndOffset > selectedStartOffset) {
        return getText().substring(selectedStartOffset, Math.min(doc.len, selectedEndOffset));
      } else {
        return null;
      }
    }
  }

  public void setText(String s) {
    doc.setText(s);
    unselect();
  }
  
  void onDocChanged() {
    recalcSize();
    //repaint();
  }
  
  void onDocRepaint() {
    repaint();
  }
  
  void onLineAdded(String s) {
    int w = getFontMetrics(getFont()).stringWidth(s);
    if (w > longestStringWidth) longestStringWidth = w;
  }
  
  void onCleared() {
    longestStringWidth = 8;
    recalcSize();
    //repaint();
  }
  
  public void addText(String s, int id, Color fg, Color bg, boolean bold) {
    doc.addText(s, id, fg, bg, bold);
  }
  
  public void addText(String s) {
    doc.addText(s, Integer.MIN_VALUE, null, null, false);
  }
  
  public void select(int startOffs, int endOffs) {
    if (startOffs > doc.len || endOffs < startOffs) return;
    rectangularSelection = false;
    selectedStartOffset = Math.max(0, startOffs);
    selectedEndOffset = Math.min(doc.len, endOffs);
    repaint();
  }
  
  public void select(int startRow, int startX, int endRow, int endX) {
    if (startRow > countLines() || endRow < startRow) return;
    if (endX < startX) return;
    rectangularSelection = true;
    selectedStartRow = startRow;
    selectedStartX = startX;
    selectedEndRow = endRow;
    selectedEndX = endX;
    repaint();
  }
  
  public void unselect() {
    selectedStartOffset = -1;
    selectedEndOffset = -1;
    anchorOffset = -1;
    selectedStartRow = -1;
    selectedEndRow = -1;
    anchorRow = -1;
    rectangularSelection = false;
    repaint();
  }
  
  public void addStyleByOffset(int styleId, Color fg, Color bg, boolean bold, int startOffs, int endOffs) {
    doc.addStyleByOffset(styleId, fg, bg, bold, startOffs, endOffs);
  }
  
  public void addStyleByLine(int styleId, Color fg, Color bg, boolean bold, int startLine, int endLine) {
    doc.addStyleByLine(styleId, fg, bg, bold, startLine, endLine);
  }
  
  public void addStyleByLineAndOffset(int styleId, Color fg, Color bg, boolean bold, int lineNbr, int startOffs, int endOffs) {
    doc.addStyleByLineAndOffset(styleId, fg, bg, bold, lineNbr, startOffs, endOffs);
  }
  
  public void removeStyle(int styleId) {
    doc.removeStyle(styleId);
  }
  
  public int countLines() {
    return doc.countLines();
  }
  
  public int getTextLength() {
    return doc.len;
  }
  
  public String getTextAtLine(int line) {
    if (line >= 0 && line < countLines()) {
      return doc.lines.get(line).string;
    } else {
      return null;
    }
  }
  
  public void clear() {
    doc.clear();
  }
  
  public void setFont(Font font) {
    super.setFont(font);
    fontHPx = getFontMetrics(getFont()).getHeight();
    if (doc != null && doc.lines != null) {
      synchronized (doc.lines) {
        longestStringWidth = 0;
        for (Line line : doc.lines) {
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
    Rectangle r = new Rectangle(p.x - w/2, p.y - h/2, w, h);
    int hxs = scrlP.getHorizontalScrollBar().getValue();
    int hxe = hxs + w - 64;
    if (p.x <= hxs || p.x >= hxe) {
      scrlP.getHorizontalScrollBar().setValue(r.x);
    }
    scrlP.getVerticalScrollBar().setValue(r.y);
    //scrlP.getViewport().scrollRectToVisible(r);
  }
  
  public void scrollToLineNumber(int lineNbr) {
    JScrollPane scrlP = getScroll();
    if (scrlP == null) return;
    lineNbr = Math.max(0, Math.min(lineNbr, countLines()-1));
    int w = scrlP.getViewport().getWidth();
    int h = scrlP.getViewport().getHeight();
    Rectangle r = new Rectangle(0, getYForLineNumber(lineNbr) - h/2, w, h);
    scrlP.getHorizontalScrollBar().setValue(r.x);
    scrlP.getVerticalScrollBar().setValue(r.y);
    //scrlP.getViewport().scrollRectToVisible(r);
  }
  
  public void scrollLinesRelative(int lines) {
    JScrollPane scrlP = getScroll();
    if (scrlP == null) return;
    int amount = (int)(((long)scrlP.getVerticalScrollBar().getMaximum() * fontHPx) / (long)getTotalHeightPx());
    scrlP.getVerticalScrollBar().setValue(scrlP.getVerticalScrollBar().getValue() + lines * amount);
  }
  
  public void scrollPagesRelative(int i) {
    JScrollPane scrlP = getScroll();
    if (scrlP == null) return;
    int amount = (int)(((long)scrlP.getVerticalScrollBar().getMaximum() * (long)getVisibleRect().height) / (long)getTotalHeightPx());
    scrlP.getVerticalScrollBar().setValue(scrlP.getVerticalScrollBar().getValue() + amount*i);
  }
  
  public void scrollHorizontalRelative(int i) {
    JScrollPane scrlP = getScroll();
    if (scrlP == null) return;
    scrlP.getHorizontalScrollBar().setValue(scrlP.getHorizontalScrollBar().getValue() + fontHPx * i);
  }

  public void scrollToEnd() {
    JScrollPane scrlP = getScroll();
    if (scrlP == null) return;
    scrlP.getVerticalScrollBar().setValue(scrlP.getVerticalScrollBar().getMaximum());
  }

  public int getYForLineNumber(int lineNbr) {
    return lineNbr * fontHPx;
  }
  
  public int getLineNumberAt(int y) {
    return y / fontHPx;
  }
  
  public int getLineLength(int lineNbr) {
    if (lineNbr < 0 || lineNbr >= countLines()) return 0;
    Line line = doc.lines.get(lineNbr);
    return line.len;
  }
  
  public int getLineOffset(int lineNbr) {
    if (lineNbr < 0 || lineNbr >= countLines()) return 0;
    Line line = doc.lines.get(lineNbr);
    return line.offs;
  }
  
  public int getLineOffsetAt(int lineNbr, int x) {
    if (lineNbr < 0 || lineNbr >= countLines()) return 0;
    x -= 3;
    Line line = doc.lines.get(lineNbr);
    String str = line.string;
    int len = line.nl ? line.len - 1 : line.len;
    FontMetrics fm = getFontMetrics(getFont());
    int offs = 0;
    while (offs < len && fm.stringWidth(str.substring(0, offs)) < x) {
      offs++;
    }
    return offs;
  }
  
  public String getLineByOffset(int offset) {
    return getLineByLineNumber(doc.getLineNumberByOffset(offset));
  }
  
  public String getLineByLineNumber(int lineNbr) {
    if (lineNbr < 0 || lineNbr >= countLines()) return null;
    Line line = doc.lines.get(lineNbr);
    return line.string;
  }
  
  public Point getCoordinatesForOffset(int offs) {
    int lineNbr = doc.getLineNumberByOffset(offs);
    if (lineNbr < 0 || lineNbr >= countLines()) return null;
    Line line = doc.lines.get(lineNbr);
    int strOffs = offs - line.offs;
    String s = line.string.substring(0, Math.min(strOffs, line.len-1));
    int x = getFontMetrics(getFont()).stringWidth(s);
    return new Point(x, getYForLineNumber(lineNbr));
  }

  public int getOffsetAt(int x, int y) {
    if (x < 0) x = 0;
    if (y < 0) y = 0;
    int lineNbr = y / fontHPx;
    if (lineNbr >= countLines()) return doc.len;
    Line line = doc.lines.get(lineNbr);
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
  
  
  public void paintComponent(Graphics og) {
    if (og == null) return;
    og.setColor(Color.black);
    og.fillRect(0, 0, getWidth(), getHeight());
    Graphics2D g = (Graphics2D)og;
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
        RenderingHints.VALUE_ANTIALIAS_ON);
    g.setFont(getFont());
    final Rectangle visRect = getVisibleRect();
    int visLine = visRect.y / fontHPx;
    int y = visLine * fontHPx;
    final int visMaxY = visRect.y + visRect.height;
    final int maxLine = countLines();
    g.setColor(getForeground());
    while (y < visMaxY && visLine < maxLine) {
      Line l = doc.lines.get(visLine);
      paintLineShards(g, y, l, visLine);
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
  
  protected void paintLineShards(Graphics2D g, int y, Line line, int lineNbr) {
    if (!rectangularSelection && selectedStartOffset >= 0) {
      selectionStyle.lineStartOffs = selectedStartOffset - line.offs;
      selectionStyle.lineEndOffs = selectedEndOffset - line.offs;
    } else if (rectangularSelection && selectedStartRow >= 0) {
      selectionStyle.lineStartOffs = getLineOffsetAt(lineNbr, selectedStartX);
      selectionStyle.lineEndOffs = getLineOffsetAt(lineNbr, selectedEndX);
    }
    int curOffs = 0;
    int x = 0;
    while (curOffs < line.len) {
      shardStyle.fg = null;
      shardStyle.bg = null;
      shardStyle.bold = false;
      shardSelected = false;
      int nextOffs = findShardStyle(line, curOffs, lineNbr);
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
  protected int findShardStyle(Line line, int offs, int lineNbr) {
    int selNextOffs = Integer.MAX_VALUE;
    int styleNextOffs = Integer.MAX_VALUE;
    // check selection
    if (!rectangularSelection && (selectionStyle.lineEndOffs < offs || selectionStyle.lineStartOffs >= line.len)) {
      // no selection
      selNextOffs = line.len;
    } else if (rectangularSelection &&
        (lineNbr < selectedStartRow || lineNbr > selectedEndRow ||
            selectionStyle.lineEndOffs < offs || selectionStyle.lineStartOffs >= line.len)) {
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
          if (styleLen <= minLen) {
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
  
  protected static class Line {
    String string;
    List<Style> styles;
    int offs;
    int len;
    boolean nl;

    public Line() {
      string = "";
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

    getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_UP, 0), "pageup");
    getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_DOWN, 0), "pagedown");
    getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "lineup");
    getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "linedown");
    getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), "copy");
    getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_HOME, InputEvent.CTRL_DOWN_MASK), "home");
    getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_END, InputEvent.CTRL_DOWN_MASK), "end");

    actionMap.put("pageup", new AbstractAction() {
      private static final long serialVersionUID = -1443733874776516263L;
      @Override
      public void actionPerformed(ActionEvent e) {
        scrollPagesRelative(-1);
      }
    });
    actionMap.put("pagedown", new AbstractAction() {
      private static final long serialVersionUID = -8653322027672633784L;
      @Override
      public void actionPerformed(ActionEvent e) {
        scrollPagesRelative(1);
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
    actionMap.put("home", new AbstractAction() {
      private static final long serialVersionUID = 8906144596280671442L;
      @Override
      public void actionPerformed(ActionEvent e) {
        JScrollPane scrlP = getScroll();
        if (scrlP == null) return;
        scrlP.getVerticalScrollBar().setValue(0);
      }
    });
    actionMap.put("end", new AbstractAction() {
      private static final long serialVersionUID = 8390621445968067142L;
      @Override
      public void actionPerformed(ActionEvent e) {
        JScrollPane scrlP = getScroll();
        if (scrlP == null) return;
        scrlP.getVerticalScrollBar().setValue(scrlP.getVerticalScrollBar().getMaximum());
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
        rectangularSelection = (e.getModifiers() & InputEvent.SHIFT_MASK) != 0;
        if (rectangularSelection) {
          selectedStartRow = getLineNumberAt(e.getY());
          selectedStartX = e.getX();
          selectedEndRow = selectedStartRow; 
          selectedEndX = selectedStartX;
          anchorRow = selectedStartRow;
          anchorX = selectedStartX;
        } else {
          selectedStartOffset = getOffsetAt(e.getX(), e.getY());
          anchorOffset = selectedStartOffset; 
          selectedEndOffset = anchorOffset;
        }
        repaint();
      }
    }
    
    @ Override
    public void mouseDragged(MouseEvent e) {
      if (anchorOffset >= 0 || anchorRow >= 0) {
        if (rectangularSelection) {
          int line = getLineNumberAt(e.getY());
          int selX = e.getX();
          if (line == anchorRow && selX == anchorX) {
            selectedStartRow = anchorRow;
            selectedEndRow = anchorRow;
            selectedStartX = anchorX;
            selectedEndX = anchorX + 1;
          } else {
            if (line < anchorRow) {
              selectedStartRow = line;
              selectedEndRow = anchorRow;
            } else {
              selectedStartRow = anchorRow;
              selectedEndRow = line;
            }
            if (selX < anchorX) {
              selectedStartX = selX;
              selectedEndX = anchorX;
            } else {
              selectedStartX = anchorX;
              selectedEndX = selX;
            }
          }
        } else {
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
        if (getTotalHeightPx() <= 0) return;
        int amount = (scrlP.getVerticalScrollBar().getMaximum() * fontHPx) / getTotalHeightPx();
        scrlP.getVerticalScrollBar().setValue(scrlP.getVerticalScrollBar().getValue() + 4 * amount * e.getWheelRotation());
      }
    }
  };
  
  /**
   * FastTextPane document model
   */
  public static class Doc {
    List<Line> lines = new ArrayList<Line>();
    List<FastTextPane> listeners = new ArrayList<FastTextPane>();
    int len = 0;
    int maxBytes = 0;
    
    public void setMaximumDocumentBytes(int bytes) {
      maxBytes = bytes;
      if (maxBytes > 0) {
        removeLinesUntilLength(maxBytes);
      }
    }
    
    public void fireOnDocChanged() {
      for (FastTextPane ftp : listeners) {
        ftp.onDocChanged();
      }
    }
    
    public void fireOnDocRepaint() {
      for (FastTextPane ftp : listeners) {
        ftp.onDocRepaint();
      }
    }
    
    public void fireOnLineAdded(String s) {
      for (FastTextPane ftp : listeners) {
        ftp.onLineAdded(s);
      }
    }
    
    public void fireOnCleared() {
      for (FastTextPane ftp : listeners) {
        ftp.onCleared();
      }
    }
    
    public void setText(String s) {
      synchronized (lines) {
        internalClear();
        addText(s);
      }
      fireOnDocChanged();
    }
    
    protected void internalClear() {
      synchronized (lines) {
        lines.clear();
        len = 0;
      }
    }
    
    public void clear() {
      internalClear();
      fireOnCleared();
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
      fireOnDocRepaint();
    }
    
    public int countLines() {
      return lines.size();
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
      fireOnDocRepaint();
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
      fireOnDocRepaint();
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
      fireOnDocRepaint();
    }

    
    public void addText(String s, int id, Color fg, Color bg, boolean bold) {
      if (s == null) return;
      int prevLen;
      synchronized (lines) {
        if (maxBytes > 0 && len + s.length() > maxBytes) {
          // tidy
          removeLinesUntilLength(maxBytes);
        }
        prevLen = len;
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
              Line line = new Line();
              line.offs = len;
              addLine(line);
            }
          }
          if (prevOffs < s.length()) {
            appendLine(s.substring(prevOffs));
          }
        }
      }
      if (id != Integer.MIN_VALUE) {
        addStyleByOffset(id, fg, bg, bold, prevLen, len);
      }
      fireOnDocChanged();
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
        fireOnLineAdded(l.string);
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
        fireOnLineAdded(l.string);
      }
    }
    
    protected void removeLinesUntilLength(int maxLen) {
      synchronized (lines) {
        int movedOffs = 0;
        while (!lines.isEmpty() && len > maxLen) {
          Line line = lines.remove(0);
          movedOffs += line.len;
          len -= line.len;
        }
        if (movedOffs > 0) {
          for (Line line : lines) {
            line.offs -= movedOffs;
          }
        }
      }
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
  }
}
