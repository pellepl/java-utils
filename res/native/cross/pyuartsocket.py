#!/usr/bin/env python3

# No select, only threads, to be window$ compatible

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

g_ctrl_clients = []
g_data_clients = []
g_uarts = []
g_id = 0
server = None
g_running = True

CMD_SERVER_SHUTDOWN = "X"
CMD_CLIENT_SHUTDOWN = "C"
CMD_IDENTIFY = "I"
CMD_ATTACH = "A"
CMD_LIST_SERIALS = "L"
CMD_OPEN_SERIAL = "O"
CMD_CONFIG_SERIAL = "U"
CMD_CONFIG_SERIAL_BAUDRATE = "B"
CMD_CONFIG_SERIAL_PARITY = "P"
CMD_CONFIG_SERIAL_BYTESIZE = "D"
CMD_CONFIG_SERIAL_STOPBITS = "S"
CMD_CONFIG_SERIAL_TIMEOUT = "T"
CMD_CONFIG_SERIAL_ITIMEOUT = "M"
CMD_CONFIG_SERIAL_SET_RTS = "r"
CMD_CONFIG_SERIAL_SET_DTR = "d"
CMD_CONFIG_SERIAL_GET_CTS = "c"
CMD_CONFIG_SERIAL_GET_DSR = "s"
CMD_CONFIG_SERIAL_GET_RI = "i"
CMD_CONFIG_SERIAL_GET_CD = "e"
CMD_HELP = "?"

def dbg(s):
  """ debug output """
  print(s)

def lldbg(s):
  """ lowlevel debug output """
  pass
  #print(s)

def drop_dead():
  """ kill server and clients """
  global g_running
  dbg("server shutdown")
  g_running = False
  for client in g_ctrl_clients:
    dbg("finishing off client {:d}".format(client.id))
    try:
      client.running = FalseTR
      client.socket.shutdown(socket.SHUT_RDWR)
    except:
      pass
    client.socket.close()
  server.shutdown()

def finalize_client(client):
  """ kill client (data/ctrl) """
  dbg("client {:d} exited".format(client.id))
  if client.ctrl:
    for data_client in client.data_clients:
      try:
        g_data_clients.remove(data_client)
      except ValueError:
        pass
      data_client.running = False
    try:
      g_ctrl_clients.remove(client)
    except ValueError:
      pass
    if client.uart:
      client.uart.close()
    client.running = False
  else:    
    try:
      g_data_clients.remove(client)
    except ValueError:
      pass
    for ctrl_client in g_ctrl_clients:
      try:
        ctrl_client.data_clients.remove(client)
      except ValueError:
        pass
    client.running = False


def serial_rx(uart):
  """ thread serial rx """
  while g_running and uart.running:
    try:
      ser_data = uart.serial.read(1)
    except serial.serialutil.SerialException as e:
      uart.ctrl_client.error("serial:{}".format(str(e)))
      uart.ctrl_client.running = False
      uart.close()
      break

    if len(ser_data) == 0:
      continue
    lldbg("  ser{:s}->{:s}".format(uart.name, str(ser_data)))
    for data_client in uart.ctrl_client.data_clients:
      data_client.q_ser2eth.put(ser_data)

def serial_tx(uart):
  """ thread serial tx """
  while g_running and uart.running:
    try:
      eth_data = uart.q_eth2ser.get(True, 1.0)
      lldbg("  ser{:s}<-{:s}".format(uart.name, str(eth_data)))
      uart.serial.write(eth_data)
    except queue.Empty:
      pass
    except serial.serialutil.SerialException as e:
      uart.ctrl_client.error("serial:{}".format(str(e)))
      uart.ctrl_client.running = False
      uart.close()
      break


class Uart:
  """ class: uart """
  def __init__(self, ctrl_client, name):
    self.name = name.strip()
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
    ("xonxoff", self.ctrl_client.ser_xonxoff),
    ("rts", self.ctrl_client.ser_rts),
    ("dtr", self.ctrl_client.ser_dtr)
    ]));


class Client(threading.Thread):
  """ class: client """
  def __init__(self, request_handler):
    global g_id
    threading.Thread.__init__(self)
    self.id = g_id
    g_id = g_id + 1
    self.running = True
    self.socket = request_handler.request
    self.ctrl = True
    self.cmd = ""
    self.q_ser2eth = queue.Queue()
    self.data_clients = []
    self.ctrl_client = None
    self.uart = None
    self.ser_rtimeout = 1.0
    self.ser_wtimeout = 1.0
    self.ser_itimeout = None
    self.ser_baudrate = 115200
    self.ser_parity = serial.PARITY_NONE
    self.ser_bytesize = 8
    self.ser_stopbits = 1
    self.ser_xonxoff = 0
    self.ser_rts = None
    self.ser_dtr = None
    self.zeroes = 0

    dbg("client {:d} entered".format(self.id))
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
    if self.ctrl:
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
          
    else:
      if self.ctrl_client.uart != None:
        lldbg("  eth{:s}<-{:s}".format(self.ctrl_client.uart.name, str(data)))
        self.ctrl_client.uart.q_eth2ser.put(data)

  def help(self):
    """ dump help to peer """
    self.echo(CMD_SERVER_SHUTDOWN + "        shuts down server\n")
    self.echo(CMD_CLIENT_SHUTDOWN + "        shuts down client and any attached clients\n")
    self.echo(CMD_IDENTIFY        + "        identifies this client\n")
    self.echo(CMD_ATTACH          + " <n>    attaches this client to given client and makes this client a data channel\n")
    self.echo(CMD_LIST_SERIALS    + "        lists serial ports\n")
    self.echo(CMD_OPEN_SERIAL     + " <ser>  opens serial port\n")
    self.echo(CMD_CONFIG_SERIAL   + " <config params> sets/gets serial port params and reconfigures if open\n")
    self.echo("  " + CMD_CONFIG_SERIAL_BAUDRATE + "<baud>  sets serial baudrate\n")
    self.echo("  " + CMD_CONFIG_SERIAL_PARITY   + "<par>   sets serial parity\n")
    self.echo("  " + CMD_CONFIG_SERIAL_BYTESIZE + "<byte>  sets serial bytesize\n")
    self.echo("  " + CMD_CONFIG_SERIAL_STOPBITS + "<stop>  sets serial stopbits\n")
    self.echo("  " + CMD_CONFIG_SERIAL_TIMEOUT  + "<tmo>   sets serial read and write timeout in milliseconds\n")
    self.echo("  " + CMD_CONFIG_SERIAL_ITIMEOUT + "<tmo>   sets serial intracharacter timeout in milliseconds\n")
    self.echo("  " + CMD_CONFIG_SERIAL_SET_RTS  + "<rts>   sets serial rts line hi/lo\n")
    self.echo("  " + CMD_CONFIG_SERIAL_SET_DTR  + "<dtr>   sets serial dtr line hi/lo\n")
    self.echo("  " + CMD_CONFIG_SERIAL_GET_CTS  + "        returns serial cts line state\n")
    self.echo("  " + CMD_CONFIG_SERIAL_GET_DSR  + "        returns serial dsr line state\n")
    self.echo("  " + CMD_CONFIG_SERIAL_GET_RI   + "        returns serial ri line state\n")
    self.echo("  " + CMD_CONFIG_SERIAL_GET_CD   + "        returns serial cd line state\n")

  def on_command(self, cmd_str):
    """ handle ctrl command from peer """ 
    cmds = cmd_str.split(' ')
    cmd = cmds[0].strip()
    if len(cmds) == 2:
      arg = cmds[1].strip()
    elif len(cmds) > 2:
      arg = cmds[1:]
    else:
      arg = None
      
    if cmd == CMD_SERVER_SHUTDOWN:
      self.ok()
      drop_dead()
  
    elif cmd == CMD_CLIENT_SHUTDOWN:
      self.running = False
      self.ok()
  
    elif cmd == CMD_IDENTIFY:
      self.echo("{}\n".format(self.id))
      self.ok()
  
    elif cmd == CMD_ATTACH:
      other_id = int(arg)
      if other_id == self.id:
        self.error("cannot attach to self")
      elif len(self.data_clients) > 0:
        self.error("have attachees")
      else:
        for ctrl_client in g_ctrl_clients:
          if ctrl_client.id == other_id:
            dbg("attach client {:d} to {:d}".format(self.id, other_id))
            self.ctrl_client = ctrl_client
            ctrl_client.data_clients.append(self)
            g_ctrl_clients.remove(self)
            g_data_clients.append(self)
            self.ctrl = False
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
      
    elif cmd == CMD_OPEN_SERIAL:
      if self.uart:
        self.uart.close()
      self.uart = Uart(self, arg)
      self.uart.open()
      g_uarts.append(self.uart)
      self.ok()
  
    elif cmd == CMD_CONFIG_SERIAL:
      self.config_serial(cmds[1:])
      
    elif cmd == CMD_HELP:
      self.help()
      self.ok()
  
  
    elif cmd == "-":
      if self.uart:
        print("{:s}".format(str(self.uart.serial.get_settings())))
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
          
      elif cmd == CMD_CONFIG_SERIAL_SET_RTS:
        if arg == "0":
          self.ser_rts = False
          if self.uart != None:
            self.uart.serial.setRTS(False)
        elif arg == "1":
          self.ser_rts = True
          if self.uart != None:
            self.uart.serial.setRTS(True)
        elif arg == "-":
          self.ser_rts = None
        else:
          self.error("unknown line state (0,1,-)")
          ok = 0
          
      elif cmd == CMD_CONFIG_SERIAL_SET_DTR:
        if arg == "0":
          self.ser_dtr = False
          if self.uart != None:
            self.uart.serial.setDTR(False)
        elif arg == "1":
          self.ser_dtr = True
          if self.uart != None:
            self.uart.serial.setDTR(True)
        elif arg == "-":
          self.ser_dtr = None
        else:
          self.error("unknown line state (0,1,-)")
          ok = 0

      elif cmd == CMD_CONFIG_SERIAL_GET_CD:
        if self.uart != None:
          self.echo(str(1 if self.uart.serial.cd else 0) + "\n")
        else:
          self.echo("-\n")
          
      elif cmd == CMD_CONFIG_SERIAL_GET_CTS:
        if self.uart != None:
          self.echo(str(1 if self.uart.serial.cts else 0) + "\n")
        else:
          self.echo("-\n")
          
      elif cmd == CMD_CONFIG_SERIAL_GET_DSR:
        if self.uart != None:
          self.echo(str(1 if self.uart.serial.dsr else 0) + "\n")
        else:
          self.echo("-\n")
          
      elif cmd == CMD_CONFIG_SERIAL_GET_RI:
        if self.uart != None:
          self.echo(str(1 if self.uart.serial.ri else 0) + "\n")
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

class ThreadedTCPRequestHandler(socketserver.BaseRequestHandler):
  """ class: server request handler """
  def handle(self):
    global g_running
    self.client = Client(self)
    g_ctrl_clients.append(self.client)
    try:
      cmd = ""
      self.request.settimeout(1.0)
      while g_running and self.client.running:
        try:
          self.client.on_eth_data(self.request.recv(8))
        except socket.timeout:
          continue
        if not self.client.running:
          break
    except socket.error as e:
      if e.errno != errno.EPIPE:
        raise
    finally:
      finalize_client(self.client)
    g_running = False


class ThreadedTCPServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
  """ class: server """
  allow_reuse_address = True


if __name__ == "__main__":
  """ main entry """
  HOST, PORT = "localhost", 5001
  if len(sys.argv) > 2:
    HOST = sys.argv[1]
    PORT = int(sys.argv[2])
  elif len(sys.argv) == 2:
    PORT = int(sys.argv[1])

  dbg("starting server @ {:s}:{:d}".format(HOST, PORT))
  server = ThreadedTCPServer((HOST, PORT), ThreadedTCPRequestHandler)
  server.daemon_threads =  True
  with server:
    ip, port = server.server_address
    server_thread = threading.Thread(target=server.serve_forever)
    server_thread.daemon = True
    server_thread.start()
    server_thread.join()
  server.shutdown()
  dbg("stopped server @ {:s}:{:d}".format(HOST, PORT))

