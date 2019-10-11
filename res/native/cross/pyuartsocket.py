#!/usr/bin/env python3

# v1.1dev

# No select, only threads, to be window$ compatible
#
# Ideas
#   * [done] rx or tx sniffing
#   * [done] exclusive rxtx, only one can tx to uart
#   * connect uartsocket servers, sharing uarts
#   * send uart data as multicast instead of tcp
#   * uartsocket discovery over network
#
# Client:Ctrl <1---1> Serial
#      ^
#      |1
#       -----------*> Client:Data
#

import sys
import socket
import threading
import socketserver
import errno
import serial
import queue
import traceback
import serial.tools.list_ports

VERSION = "1.1 dev"

g_ctrl_clients = []
g_data_clients = []
g_uarts = []
g_id = 0
server = None
g_running = True
g_eth_recv_size = 8
g_eth_poll = 1
g_ser_recv_size = 1

# Control channel
CLIENT_CTRL = 0
# Data channel, what is received from uart
CLIENT_DATA_RX = 1
# Data channel, what is sent to uart
CLIENT_DATA_TX = 2
# Data channel, what is received from uart and can send to uart
CLIENT_DATA_RXTX = 3

CMD_SERVER_SHUTDOWN = "X"
CMD_CLIENT_SHUTDOWN = "C"
CMD_LIST_CHANNELS = "D"
CMD_IDENTIFY = "I"
CMD_ATTACH = "A"
CMD_LIST_SERIALS = "L"
CMD_LIST_OPEN_SERIALS = "S"
CMD_OPEN_SERIAL = "O"
CMD_CONFIG_SERIAL = "U"
CMD_CONFIG_SERIAL_BAUDRATE = "B"
CMD_CONFIG_SERIAL_PARITY = "P"
CMD_CONFIG_SERIAL_BYTESIZE = "D"
CMD_CONFIG_SERIAL_STOPBITS = "S"
CMD_CONFIG_SERIAL_TIMEOUT = "T"
CMD_CONFIG_SERIAL_RTIMEOUT = "R"
CMD_CONFIG_SERIAL_WTIMEOUT = "W"
CMD_CONFIG_SERIAL_ITIMEOUT = "M"
CMD_CONFIG_SERIAL_XONXOFF = "X"
CMD_CONFIG_SERIAL_RTSCTS = "Y"
CMD_CONFIG_SERIAL_DSRDTR = "Z"
CMD_CONFIG_SERIAL_SET_RTS = "r"
CMD_CONFIG_SERIAL_SET_DTR = "d"
CMD_CONFIG_SERIAL_GET_CTS = "c"
CMD_CONFIG_SERIAL_GET_DSR = "s"
CMD_CONFIG_SERIAL_GET_RI = "i"
CMD_CONFIG_SERIAL_GET_CD = "e"
CMD_HELP = "?"

def out(s):
  """ output """
  print(s)

def dbg(s):
  """ debug output """
  print(s)

def lldbg(s):
  """ lowlevel debug output """
  pass
  #print(s)

def drop_dead():
  """ kill server and clients """
  dbg("server shutdown")
  g_running = False
  for client in g_ctrl_clients:
    dbg("finishing off client {:d}".format(client.id))
    try:
      client.running = False
      client.socket.shutdown(socket.SHUT_RDWR)
    except:
      pass
    client.socket.close()
  server.shutdown()

def finalize_client(client):
  """ kill client (data/ctrl) """
  dbg("client {:d} exited - cleanup".format(client.id))
  if client.type == CLIENT_CTRL:
    dbg("  client {:d} is control".format(client.id))
    for data_client in client.data_clients_r:
      try:
        dbg("    client {:d} is attached and stopped".format(data_client.id))
        g_data_clients.remove(data_client)
      except ValueError:
        pass
      data_client.running = False
    for data_client in client.data_clients_t:
      try:
        dbg("    client {:d} is attached and stopped".format(data_client.id))
        g_data_clients.remove(data_client)
      except ValueError:
        pass
      data_client.running = False
    dbg("  client {:d} is stopped".format(client.id))
    try:
      g_ctrl_clients.remove(client)
    except ValueError:
      pass
    if client.uart:
      dbg("  serial {:s} is stopped".format(str(client.uart.serial)))
      client.uart.close()
    client.running = False
  else:
    dbg("  client {:d} is data".format(client.id))
    try:
      g_data_clients.remove(client)
    except ValueError:
      pass
    for ctrl_client in g_ctrl_clients:
      try:
        ctrl_client.data_clients_r.remove(client)
      except ValueError:
        pass
      try:
        ctrl_client.data_clients_t.remove(client)
      except ValueError:
        pass
    dbg("  client {:d} is stopped".format(client.id))
    client.running = False
  dbg("client {:d} exit done".format(client.id))


def serial_rx(uart):
  """ thread serial rx """
  while g_running and uart.running:
    try:
      ser_data = uart.serial.read(g_ser_recv_size)
    except serial.serialutil.SerialException as e:
      uart.ctrl_client.error("serial:{}".format(str(e)))
      uart.ctrl_client.running = False
      uart.close()
      break

    if len(ser_data) == 0:
      continue
    lldbg("  ser{:s}->{:s}".format(uart.name, str(ser_data)))
    for data_client in uart.ctrl_client.data_clients_r:
      data_client.q_ser2eth.put(ser_data)

def serial_tx(uart):
  """ thread serial tx """
  while g_running and uart.running:
    try:
      eth_data = uart.q_eth2ser.get(True, 1.0)
      lldbg("  ser{:s}<-{:s}".format(uart.name, str(eth_data)))
      uart.serial.write(eth_data)
      for data_client in uart.ctrl_client.data_clients_t:
        data_client.q_ser2eth.put(eth_data)
    except queue.Empty:
      pass
    except serial.serialutil.SerialException as e:
      uart.ctrl_client.error("serial:{}".format(str(e)))
      uart.ctrl_client.running = False
      uart.close()
      break


class Uart:
  """ class: uart """
  def __init__(self, ctrl_client, name, exclusive):
    self.name = name.strip()
    self.exclusive = exclusive
    self.q_eth2ser = queue.Queue()
    self.running = True
    self.ctrl_client = ctrl_client
    self.serial = serial.Serial()

  def run(self):
    """ starts rx/tx threads """
    rx = threading.Thread(target=serial_rx, args = [self])
    rx.daemon = True
    rx.start()
    tx = threading.Thread(target=serial_tx, args = [self])
    tx.daemon = True
    tx.start()

  def open(self):
    """ opens this serial port with settings from associated ctrl client """
    self.serial.port = self.name
    self.serial.timeout = self.ctrl_client.ser_rtimeout
    self.serial.write_timeout = self.ctrl_client.ser_wtimeout
    self.serial.inter_byte_timeout = self.ctrl_client.ser_itimeout
    self.serial.parity = self.ctrl_client.ser_parity
    self.serial.baudrate = self.ctrl_client.ser_baudrate
    self.serial.bytesize = self.ctrl_client.ser_bytesize
    self.serial.stopbits = self.ctrl_client.ser_stopbits
    self.serial.xonxoff = self.ctrl_client.ser_xonxoff
    self.serial.dsrdtr = self.ctrl_client.ser_dsrdtr
    self.serial.rtscts = self.ctrl_client.ser_rtscts
    self.serial.rts = self.ctrl_client.ser_rts
    self.serial.dtr = self.ctrl_client.ser_dtr
    dbg("opening serial {:s} at client {:d}".format(str(self.serial), self.ctrl_client.id))
    self.serial.open()
    self.run()

  def close(self):
    """ closes this serial port """
    dbg("closing serial {:s} at client {:d}".format(str(self.serial), self.ctrl_client.id))
    self.ctrl_client.uart = None
    self.serial.close()
    self.running = False
    try:
      g_uarts.remove(self)
    except ValueError:
      pass

  def reconfigure(self):
    """ configures the serial parameters """
    self.serial.apply_settings(dict([
    ("timeout", self.ctrl_client.ser_rtimeout,),
    ("write_timeout", self.ctrl_client.ser_wtimeout),
    ("inter_byte_timeout", self.ctrl_client.ser_itimeout),
    ("parity", self.ctrl_client.ser_parity),
    ("baudrate", self.ctrl_client.ser_baudrate),
    ("bytesize", self.ctrl_client.ser_bytesize),
    ("stopbits", self.ctrl_client.ser_stopbits),
    ("dsrdtr", self.ctrl_client.ser_dsrdtr),
    ("rtscts", self.ctrl_client.ser_rtscts),
    ("xonxoff", self.ctrl_client.ser_xonxoff),
    ("rts", self.ctrl_client.ser_rts),
    ("dtr", self.ctrl_client.ser_dtr)
    ]))

  def setRTS(self, x):
    self.serial.setRTS(x)

  def setDTR(self, x):
    self.serial.setDTR(x)

  def readCD(self):
    return self.serial.cd

  def readCTS(self):
    return self.serial.cts

  def readDSR(self):
    return self.serial.dsr

  def readRI(self):
    return self.serial.ri

class Client(threading.Thread):
  """ class: client """
  def __init__(self, request_handler):
    global g_id
    threading.Thread.__init__(self)
    self.id = g_id
    g_id = g_id + 1
    self.running = True
    self.socket = request_handler.request
    self.type = CLIENT_CTRL
    self.cmd = ""
    self.q_ser2eth = queue.Queue()
    self.data_clients_r = []  # these are the CLIENT_DATA_RX and CLIENT_DATA_RXTX types
    self.data_clients_t = []  # these are the CLIENT_DATA_TX ypes
    self.ctrl_client = None
    self.uart = None
    self.ser_rtimeout = 1.0
    self.ser_wtimeout = 1.0
    self.ser_itimeout = None
    self.ser_baudrate = 115200
    self.ser_parity = serial.PARITY_NONE
    self.ser_bytesize = 8
    self.ser_stopbits = 1
    self.ser_xonxoff = False
    self.ser_rtscts = False
    self.ser_dsrdtr = False
    self.ser_rts = None
    self.ser_dtr = None
    self.zeroes = 0
    dbg("client {:d} entered [{:s}:{:d}]".format(self.id, self.socket.getpeername()[0], self.socket.getpeername()[1]))
    self.start()

  def run(self):
    """ thread serial rx queue reader """
    while self.running:
      try:
        ser_data = self.q_ser2eth.get(True, 1.0)
        self.socket.sendall(ser_data)
      except queue.Empty:
        pass

  def echo(self, text):
    """ echo message to peer """
    self.socket.sendall(bytes(text, 'ascii'))

  def echo_client(self, client):
    """ echo client info to peer """
    if client.type == CLIENT_CTRL:
      self.echo(str("C{:d}\t[{:s}:{:d}]".format(client.id, client.socket.getpeername()[0], client.socket.getpeername()[1])))
      if client.uart:
        self.echo(str("\tuart:") + str(client.uart.name) + str("\t") + \
                  str("baud:") + str(client.ser_baudrate) + str("\t") + \
                  str("data:") + str(client.ser_bytesize) + str("\t") + \
                  str("stop:") + str(client.ser_stopbits) + str("\t") + \
                  str("par:") + str(client.ser_parity) + str("\t") + \
                  str("rtmo:") + str("-" if client.ser_rtimeout == None else client.ser_rtimeout*1000.0) + str("\t") + \
                  str("wtmo:") + str("-" if client.ser_wtimeout == None else client.ser_wtimeout*1000.0) + str("\t") + \
                  str("itmo:") + str("-" if client.ser_itimeout == None else client.ser_itimeout*1000.0) + str("\t") + \
                  str("dsrdtr:") + str("1" if client.ser_dsrdtr else "0") + str("\t") + \
                  str("rtscts:") + str("1" if client.ser_rtscts else "0") + str("\t") + \
                  str("xonxoff:") + str("1" if client.ser_xonxoff else "0"))
      if len(client.data_clients_r) + len(client.data_clients_t) > 0:
        self.echo(str("\tattachees:") + str(len(client.data_clients_r) + len(client.data_clients_t)))
      self.echo(str("\n"))
    else:
      self.echo(str("D{:d}\t[{:s}:{:d}]".format(client.id, client.socket.getpeername()[0], client.socket.getpeername()[1])))
      if client.type == CLIENT_DATA_RX:
        self.echo(str("\trx"))
      elif client.type == CLIENT_DATA_TX:
        self.echo(str("\ttx"))
      elif client.type == CLIENT_DATA_RXTX:
        self.echo(str("\trxtx"))
      if client.ctrl_client != None:
        self.echo(str("\tattached:C") + str(client.ctrl_client.id))
        if client.ctrl_client.uart:
          self.echo(str("\tuart:") + str(client.ctrl_client.uart.name))
      self.echo(str("\n"))

  def error(self, text):
    """ echo error to peer """
    self.socket.sendall(bytes("ERROR " + text + "\n", 'ascii'))

  def ok(self):
    """ echo ok to peer """
    self.socket.sendall(bytes("OK\n", 'ascii'))

  def on_eth_data(self, data):
    """ handle incoming ethernet data """
    global g_running
    if len(data) == 0:
      self.zeroes = self.zeroes + 1
      if self.zeroes > 100000:
        sys.exit(1)
      return
    self.zeroes = 0
    if self.type == CLIENT_CTRL:
      strdata = str(data, 'ascii')
      self.cmd = self.cmd + strdata
      if strdata.endswith("\n"):
        try:
          self.on_command(self.cmd)
        except serial.serialutil.SerialException as e:
          self.error("serial:{}".format(str(e)))
        except:
          self.error("unknown:{}".format(sys.exc_info()[0]))
          traceback.print_exc()
        self.cmd = ""

    elif self.type == CLIENT_DATA_RXTX:
      if self.ctrl_client.uart != None:
        lldbg("  eth{:s}<-{:s}".format(self.ctrl_client.uart.name, str(data)))
        self.ctrl_client.uart.q_eth2ser.put(data)

  def help(self):
    """ dump help to peer """
    self.echo("uartsocket " + VERSION + "\n")
    self.echo(CMD_SERVER_SHUTDOWN  + "            shuts down server, closes all serials, and detaches all clients and channels\n")
    self.echo(CMD_CLIENT_SHUTDOWN  + " (<n>)      shuts down given channel or self if no id\n")
    self.echo(CMD_IDENTIFY         + "            returns this channels' id\n")
    self.echo(CMD_ATTACH           + " <n> (R|T)  attaches this channel to given channel, making this channel a full duplex data channel, or an Rx/Tx sniff channel\n")
    self.echo(CMD_LIST_CHANNELS    + "            lists all control and data channels\n")
    self.echo(CMD_LIST_SERIALS     + " (*)        lists serial ports, gives extra info if non-empty argument\n")
    self.echo(CMD_LIST_OPEN_SERIALS+ "            lists opened ports by channel id and associated serial port\n")
    self.echo(CMD_OPEN_SERIAL      + " <ser> (X)  opens serial port, eXclusively if wanted\n")
    self.echo(CMD_CONFIG_SERIAL    + " <config params> sets/gets serial port params and reconfigures if open\n")
    self.echo("  " + CMD_CONFIG_SERIAL_BAUDRATE + "<baud>      sets serial baudrate\n")
    self.echo("  " + CMD_CONFIG_SERIAL_PARITY   + "<par>       sets serial parity\n")
    self.echo("  " + CMD_CONFIG_SERIAL_BYTESIZE + "<byte>      sets serial bytesize\n")
    self.echo("  " + CMD_CONFIG_SERIAL_STOPBITS + "<stop>      sets serial stopbits\n")
    self.echo("  " + CMD_CONFIG_SERIAL_TIMEOUT  + "<tmo>       sets serial read and write timeout in milliseconds\n")
    self.echo("  " + CMD_CONFIG_SERIAL_RTIMEOUT + "<tmo>       sets serial read timeout in milliseconds\n")
    self.echo("  " + CMD_CONFIG_SERIAL_WTIMEOUT + "<tmo>       sets serial write timeout in milliseconds\n")
    self.echo("  " + CMD_CONFIG_SERIAL_ITIMEOUT + "<tmo>       sets serial intracharacter timeout in milliseconds\n")
    self.echo("  " + CMD_CONFIG_SERIAL_RTSCTS   + "<ena>       enable or disable rts/cts hw flow control\n")
    self.echo("  " + CMD_CONFIG_SERIAL_DSRDTR   + "<ena>       enable or disable dsr/dtr hw flow control\n")
    self.echo("  " + CMD_CONFIG_SERIAL_XONXOFF  + "<ena>       enable or disable xon/xoff sw flow control\n")
    self.echo("  " + CMD_CONFIG_SERIAL_SET_RTS  + "<rts>       sets serial rts line hi/lo\n")
    self.echo("  " + CMD_CONFIG_SERIAL_SET_DTR  + "<dtr>       sets serial dtr line hi/lo\n")
    self.echo("  " + CMD_CONFIG_SERIAL_GET_CTS  + "            returns serial cts line state\n")
    self.echo("  " + CMD_CONFIG_SERIAL_GET_DSR  + "            returns serial dsr line state\n")
    self.echo("  " + CMD_CONFIG_SERIAL_GET_RI   + "            returns serial ri line state\n")
    self.echo("  " + CMD_CONFIG_SERIAL_GET_CD   + "            returns serial cd line state\n")

  def on_command(self, cmd_str):
    """ handle ctrl command from peer """
    cmds = cmd_str.split(' ')
    cmd = cmds[0].strip()
    if len(cmds) == 2:
      arg = cmds[1].strip()
      arg2 = None
    elif len(cmds) > 2:
      arg = cmds[1].strip()
      arg2 = cmds[2].strip()
    else:
      arg = None
      arg2 = None

    if cmd == CMD_SERVER_SHUTDOWN:
      self.ok()
      drop_dead()

    elif cmd == CMD_CLIENT_SHUTDOWN:
      closee = None
      if (arg != None):
        other_id = int(arg)
        for client in g_ctrl_clients:
          if client.id == other_id:
            closee = client
            break
        for client in g_data_clients:
          if client.id == other_id:
            closee = client
            break
      else:
        closee = self
      if closee == None:
        self.error("no such channel")
      else:
        closee.running = False
        self.ok()

    elif cmd == CMD_IDENTIFY:
      self.echo("{}\n".format(self.id))
      self.ok()

    elif cmd == CMD_ATTACH:
      other_id = int(arg)
      if other_id == self.id:
        self.error("cannot attach to self")
      elif len(self.data_clients_r) + len(self.data_clients_t) > 0:
        self.error("have attachees")
      else:
        data_type = CLIENT_DATA_RXTX
        if arg2 != None:
          if arg2 == 'R':
            data_type = CLIENT_DATA_RX
          elif arg2 == 'T':
            data_type = CLIENT_DATA_TX
          else:
            self.error("unknown type (R,T or nothing)")
            return
        for ctrl_client in g_ctrl_clients:

          if ctrl_client.id == other_id:
            dbg("attach client {:d} to {:d} as type {:d}".format(self.id, other_id, data_type))
            self.ctrl_client = ctrl_client
            if data_type == CLIENT_DATA_TX:
              ctrl_client.data_clients_t.append(self)
            else:
              if not ctrl_client.accept(data_type):
                self.error("control channel denies access of data channel type")
                return
              ctrl_client.data_clients_r.append(self)
            g_ctrl_clients.remove(self)
            g_data_clients.append(self)
            self.type = data_type
            self.ok()
            return
        self.error("no such channel")

    elif cmd == CMD_LIST_SERIALS:
      ports = serial.tools.list_ports.comports()
      for p in ports:
        if (arg != None):
          self.echo(str(p[0]) + "\t" + str(p[1]) + "\t" + str(p[2]) + "\n")
        else:
          self.echo(str(p[0]) + "\n")
      self.ok()

    elif cmd == CMD_LIST_CHANNELS:
      for client in g_ctrl_clients:
        self.echo_client(client)
      for client in g_data_clients:
        self.echo_client(client)
      self.ok()

    elif cmd == CMD_LIST_OPEN_SERIALS:
      for ctrl_client in g_ctrl_clients:
        if ctrl_client.uart:
          self.echo_client(ctrl_client)
      self.ok()

    elif cmd == CMD_OPEN_SERIAL:
      exclusive = False
      if arg2 != None:
        if arg2 == 'X':
          exclusive = True
        else:
          self.error("unknown flag (X or nothing)")
          return

      if self.uart:
        self.uart.close()

      for u in g_uarts:
        if u.name == arg:
          self.error("already opened in other channel")
          return

      if exclusive:
        rxtxers = 0
        for data_client in self.data_clients_r:
          if data_client.type == CLIENT_DATA_RXTX:
            rxtxers = rxtxers + 1
            if rxtxers > 1:
              # not allowed, close client
              dbg("ctrl channel " + str(self.id) + " opened uart exclusively, dropping data client " + str(data_client.id))
              data_client.running = False

      self.uart = Uart(self, arg, exclusive)
      self.uart.open()
      g_uarts.append(self.uart)
      self.ok()

    elif cmd == CMD_CONFIG_SERIAL:
      self.config_serial(cmds[1:])

    elif cmd == CMD_HELP:
      self.help()
      self.ok()

    elif cmd == "-":
      self.echo_client(self)
      self.ok()

    else:
        self.error("unknown command")

  def config_serial(self, args):
    """ sets serial config """
    ok = 1
    conf = 0
    for cmdl in args:
      cmd = cmdl[0]
      arg = cmdl[1:].strip()

      if cmd == CMD_CONFIG_SERIAL_BAUDRATE:
        self.ser_baudrate = int(arg)
        conf |= 1

      elif cmd == CMD_CONFIG_SERIAL_PARITY:
        if arg == 'n':
          self.ser_parity = serial.PARITY_NONE
        elif arg == 'o':
          self.ser_parity = serial.PARITY_ODD
        elif arg == 'e':
          self.ser_parity = serial.PARITY_EVEN
        elif arg == 'm':
          self.ser_parity = serial.PARITY_MARK
        elif arg == 's':
          self.ser_parity = serial.PARITY_SPACE
        else:
          self.error("unknown parity (n,o,e,m,s)")
          ok = 0
        if ok == 1:
          conf |= 1

      elif cmd == CMD_CONFIG_SERIAL_BYTESIZE:
        if arg == '8':
          self.ser_bytesize = serial.EIGHTBITS
        elif arg == '7':
          self.ser_bytesize = serial.SEVENBITS
        elif arg == '6':
          self.ser_bytesize = serial.SIXBITS
        elif arg == '5':
          self.ser_bytesize = serial.FIVEBITS
        else:
          self.error("unknown bytesize (8,7,6,5)")
          ok = 0
        if ok == 1:
          conf |= 1

      elif cmd == CMD_CONFIG_SERIAL_TIMEOUT:
        tmo = int(arg)
        self.ser_rtimeout = self.ser_wtimeout = tmo/1000.0
        conf |= 1

      elif cmd == CMD_CONFIG_SERIAL_RTIMEOUT:
        tmo = int(arg)
        self.ser_rtimeout = tmo/1000.0
        conf |= 1

      elif cmd == CMD_CONFIG_SERIAL_WTIMEOUT:
        tmo = int(arg)
        self.ser_wtimeout = tmo/1000.0
        conf |= 1

      elif cmd == CMD_CONFIG_SERIAL_ITIMEOUT:
        tmo = int(arg)
        self.ser_itimeout = tmo/1000.0
        conf |= 1

      elif cmd == CMD_CONFIG_SERIAL_STOPBITS:
        if arg == "1":
          self.ser_stopbits = serial.STOPBITS_ONE
        elif arg == "1.5":
          self.ser_bytesize = serial.STOPBITS_ONE_POINT_FIVE
        elif arg == "2":
          self.ser_bytesize = serial.STOPBITS_TWO
        else:
          self.error("unknown stopbits (1,1.5,2)")
          ok = 0
        if ok == 1:
          conf |= 1

      elif cmd == CMD_CONFIG_SERIAL_RTSCTS:
        if arg == "0":
          self.ser_rtscts = False
        elif arg == "1":
          self.ser_rtscts = True
        else:
          self.error("unknown setting (0,1)")
          ok = 0
        if ok == 1:
          conf |= 1

      elif cmd == CMD_CONFIG_SERIAL_DSRDTR:
        if arg == "0":
          self.ser_dsrdtr = False
        elif arg == "1":
          self.ser_dsrdtr = True
        else:
          self.error("unknown setting (0,1)")
          ok = 0
        if ok == 1:
          conf |= 1

      elif cmd == CMD_CONFIG_SERIAL_XONXOFF:
        if arg == "0":
          self.ser_xonxoff = False
        elif arg == "1":
          self.ser_xonxoff = True
        else:
          self.error("unknown setting (0,1)")
          ok = 0
        if ok == 1:
          conf |= 1

      elif cmd == CMD_CONFIG_SERIAL_SET_RTS:
        if arg == "0":
          self.ser_rts = False
          if self.uart != None:
            self.uart.setRTS(False)
        elif arg == "1":
          self.ser_rts = True
          if self.uart != None:
            self.uart.setRTS(True)
        elif arg == "-":
          self.ser_rts = None
        else:
          self.error("unknown line state (0,1,-)")
          ok = 0

      elif cmd == CMD_CONFIG_SERIAL_SET_DTR:
        if arg == "0":
          self.ser_dtr = False
          if self.uart != None:
            self.uart.setDTR(False)
        elif arg == "1":
          self.ser_dtr = True
          if self.uart != None:
            self.uart.setDTR(True)
        elif arg == "-":
          self.ser_dtr = None
        else:
          self.error("unknown line state (0,1,-)")
          ok = 0

      elif cmd == CMD_CONFIG_SERIAL_GET_CD:
        if self.uart != None:
          self.echo(str(1 if self.uart.readCD() else 0) + "\n")
        else:
          self.echo("-\n")

      elif cmd == CMD_CONFIG_SERIAL_GET_CTS:
        if self.uart != None:
          self.echo(str(1 if self.uart.readCTS() else 0) + "\n")
        else:
          self.echo("-\n")

      elif cmd == CMD_CONFIG_SERIAL_GET_DSR:
        if self.uart != None:
          self.echo(str(1 if self.uart.readDSR() else 0) + "\n")
        else:
          self.echo("-\n")

      elif cmd == CMD_CONFIG_SERIAL_GET_RI:
        if self.uart != None:
          self.echo(str(1 if self.uart.readRI() else 0) + "\n")
        else:
          self.echo("-\n")

      else:
        self.error("unknown argument")
        ok = 0

      if ok == 0:
        break

    if ok == 1 and conf == 1 and self.uart != None:
      self.uart.reconfigure()
    if ok == 1:
      self.ok()

  def accept(self, data_type):
    """ returns if a data channel may attach to this channel """
    if data_type != CLIENT_CTRL and data_type == CLIENT_DATA_RXTX:
      if self.uart != None and self.uart.exclusive:
        for data_client in self.data_clients_r:
          if data_client.type == CLIENT_DATA_RXTX:
            # uart is exclusive, and already have one data channel - deny
            return False
    return True

class ThreadedTCPRequestHandler(socketserver.BaseRequestHandler):
  """ class: server request handler """
  def handle(self):
    client = Client(self)
    g_ctrl_clients.append(client)
    try:
      cmd = ""
      self.request.settimeout(g_eth_poll)
      while g_running and client.running:
        try:
          client.on_eth_data(self.request.recv(g_eth_recv_size))
        except socket.timeout:
          continue
        if not client.running:
          break
    except socket.error as e:
      if e.errno != errno.EPIPE:
        raise
    finally:
      dbg("client {:d} request thread done".format(client.id))
      finalize_client(client)

class ThreadedTCPServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
  """ class: server """
  allow_reuse_address = True


#
# Entry point
#

def dump_help(prg):
  """ print help """
  out("Usage: " + prg + " [OPTION...] [bind_address] [port]")
  out("       -e              Ethernet receive size (defaults to 8 bytes)")
  out("       -p              Ethernet client poll interval (defaults to 1 second)")
  out("       -s              Serial receive size (defaults to 1 byte)")

if __name__ == "__main__":
  """ main entry """
  HOST, PORT = "localhost", 5001
  host_port_arg = None
  port_arg = None
  skip = False
  for ix, arg in enumerate(sys.argv):
    if ix == 0 or skip:
      skip = False
      continue
    if arg[0] == '-':
      if arg == "-e":
        g_eth_recv_size = int(sys.argv[ix+1])
        skip = True
      elif arg == "-s":
        g_ser_recv_size = int(sys.argv[ix+1])
        skip = True
      elif arg == "-p":
        g_eth_poll = int(sys.argv[ix+1])
        skip = True
      
    elif host_port_arg == None:
      host_port_arg = arg
    elif port_arg == None:
      port_arg = arg
    else:
      out("Host and port already set, ambiguous argument {:s}".format(arg))
      dump_help(sys.argv[0])
      sys.exit(1)
  if port_arg:
    HOST = host_port_arg
    PORT = int(port_arg)
  elif host_port_arg:
    port_nbr = None
    try:
      port_nbr = int(host_port_arg)
      if port_nbr < 0 or port_nbr >= 0x10000:
        port_nbr = None
    except:
      pass
    if port_nbr:
      PORT = port_nbr
    else:
      socket.inet_aton(host_port_arg)
      HOST = host_port_arg

  dbg("starting server @ {:s}:{:d}".format(HOST, PORT))
  server = ThreadedTCPServer((HOST, PORT), ThreadedTCPRequestHandler)
  server.daemon_threads =  True
  with server:
    ip, port = server.server_address
    server_thread = threading.Thread(target=server.serve_forever)
    server_thread.daemon = True
    server_thread.start()
    server_thread.join()
  g_running = False
  server.shutdown()
  dbg("stopped server @ {:s}:{:d}".format(HOST, PORT))
