package com.pelleplutt.util;

/**
 * O(1) double linked list, no fuzz 
 * @author petera
 */
public class LList {
  private LElem head, tail;
  private int size;
  
  public LList() {
    clear();
  }
  
  public final LElem head() {
    return head;
  }
  
  public final LElem tail() {
    return tail;
  }
  
  public final LElem next(LElem e) {
    return e == null ? null : e.lnext;
  }

  public final LElem prev(LElem e) {
    return e == null ? null : e.lprev;
  }
  
  public final void add(LElem e) {
    if (head == null) {
      head = e;
      e.lprev = null;
    } else {
      tail.lnext = e;
      e.lprev = tail;
    }
    tail = e;
    e.lnext = null;
    size++;
  }

  public final void remove(LElem e) {
    if (e == head) {
      head = head.lnext;
    } else {
      e.lprev.lnext = e.lnext;
    }
    
    if (e.lnext == null) {
      tail = e.lprev;
    } else {
      e.lnext.lprev = e.lprev;
    }
    e.lnext = null;
    e.lprev = null;
    size--;
  }

  public final void insertBefore(LElem pivot, LElem e) {
    e.lnext = pivot;
    e.lprev = pivot.lprev;
    if (pivot.lprev != null) {
      pivot.lprev.lnext = e;
    } else {
      head = e;
    }
    pivot.lprev = e;
    size++;
  }

  public final void insertAfter(LElem pivot, LElem e) {
    e.lnext = pivot.lnext;
    e.lprev = pivot;
    if (pivot.lnext != null) {
      pivot.lnext.lprev = e;
    } else {
      tail = e;
    }
    pivot.lnext = e;
    size++;
  }
  
  public final void clear() {
    size = 0;
    head = tail = null;
  }
  
  public final int size() {
    return size;
  }

}
