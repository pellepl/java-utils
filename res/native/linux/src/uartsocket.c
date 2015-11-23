/*
 ============================================================================
 Name        : uartsocket.c
 Author      : Peter Andersson
 Version     : 1.5

 Copyright (c) 2012-2014, Peter Andersson pelleplutt1976@gmail.com

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


 Description : Pipes a serial socket to a TCP socket.

 ============================================================================
 */

//
// Includes
//

#define VERSION "1.5"

#define USE_TCP_NODELAY 0

#define _GNU_SOURCE

#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <pthread.h>

#include <string.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <netinet/in.h>

#include <fcntl.h>

#include <termios.h>
#include <sys/ioctl.h>

#if USE_TCP_NODELAY
#include <linux/tcp.h>
#endif

#include <errno.h>

//
// Definitions
//

/**
 * Client type, control
 */
#define TYPE_CONTROL  0
/**
 * Client type, data
 */
#define TYPE_DATA   1
/**
 * Client type, bash terminal
 */
#define TYPE_BASH   2
/**
 * Maximum command line length
 */
#define CMD_BUF_LEN   256

/**
 * Maximum amount of bytes handled when piping uart and socket
 */
#define PIPE_BUF_SIZE   1024

/**
 * Defines how many r/w calls returning zero bytes are allowed, in cases where
 * e.g. an FTDI USB UART is unplugged. This will not yield an error but will
 * loop indefinetly unless watched for.
 */
#define MAX_ZERO_LOOPS  1023

#define BASH_BUFFER_MAX 65536
#define BASH_IX_BUFFER_MAX (BASH_BUFFER_MAX/CMD_BUF_LEN)

#define BASHOUT(x,...) do {fprintf(stdout,(x), ## __VA_ARGS__); fflush(stdout);} while(0);
#define BASH_BS       "\b\b  \b\b\b \b"
#define BASH_BSNO     "\b\b  \b\b"
#define BASH_CLEARLN  "\33[2K\r"

#define DBG 0

#ifndef DBG
#define DBG 0
#endif

#if DBG
#define DBG_PRINT(x, ...) do {fprintf(stdout, x"\n", ## __VA_ARGS__); fflush(stdout);}while(0);
#else
#define DBG_PRINT(x, ...)
#endif
#define INFO(x, ...) do {fprintf(stdout, x"\n", ## __VA_ARGS__); fflush(stdout);}while(0);

/**
 * Client struct, defines a client being either a control channel
 * or a data channel.
 */
struct ClientElem_s {
  /** flag indicating if client is running or should die */
  volatile int running;
  /** type of client, either data or control */
  int type;
  /** the thread hosting this client */
  pthread_t thread;
  /** the socket this client is communicating via */
  int sockfd;
  /** the tty descriptor this client is piping */
  int ttyfd;
  /** address of client */
  struct sockaddr_in addr;
  /** uart settings */
  struct termios termSettings;
  /** uart status */
  int curStatus;

  char resbuf[CMD_BUF_LEN * 2];
  char cmdbuf[CMD_BUF_LEN];
  char deviceString[64];

  char *bashBuf;
  int *bashIxBuf;
  int bashBufLen;
  int bashIx;
  int curBashIx;

  int keepOpen;

  /** next element */
  struct ClientElem_s *pNext;
};
typedef struct ClientElem_s ClientElem_t;

//
// Globals
//

/** First client in client list */
static ClientElem_t *pClientListHead = NULL;
/** Last client in client list */
static ClientElem_t *pClientListLast = NULL;

/** Counter of live clients */
static volatile int g_liveClients = 0;

/** Flag indicating if server is running or should die */
static int g_serverRunning = 1;

//
// List functions
//

/**
 * Creates a new client list element.
 * @return pointer to new element, or NULL if out of memory
 */
static ClientElem_t *newElement() {
  ClientElem_t *pClient;

  pClient = malloc(sizeof(ClientElem_t));
  if (pClient != NULL) {
    memset(pClient, 0, sizeof(ClientElem_t));
    pClient->pNext = NULL;
    pClient->ttyfd = -1;

    if (pClientListHead == NULL) {
      pClientListHead = pClient;
    } else {
      pClientListLast->pNext = pClient;
    }
    pClientListLast = pClient;
  } else {
    return NULL;
  }
  return pClientListLast;
}

/**
 * Helper function for removing an element
 * @param pPrevClient pointer to element before the element to be removed.
 *        Can be NULL if being first element in list.
 * @param pCurClient pointer to element to be removed. Must never be NULL.
 */
static void removeElement(ClientElem_t *pPrevClient, ClientElem_t *pCurClient) {
  if (pPrevClient != NULL) {
    pPrevClient->pNext = pCurClient->pNext;
  } else {
    // first element, update head
    pClientListHead = pCurClient->pNext;
  }
  if (pCurClient->pNext == NULL) {
    // last element, update tail
    pClientListLast = pPrevClient;
  }
}

/**
 * Removes a client element by given address.
 * @param pClient address to the client element to remove.
 */
static void removeElementByAddress(ClientElem_t *pClient) {
  ClientElem_t *pCurClient = pClientListHead;
  ClientElem_t *pPrevClient = NULL;
  while (pCurClient != NULL) {
    if (pCurClient == pClient) {
      removeElement(pPrevClient, pCurClient);
      break;
    }
    pPrevClient = pCurClient;
    pCurClient = pCurClient->pNext;
  }
}

/**
 * Returns client element by given index.
 * @param ix the index of the client element to return.
 * @return pointer to the client at given index or NULL if no such client.
 */
static ClientElem_t *getElementByIndex(int ix) {
  int cIx = 0;
  ClientElem_t *pCurClient = pClientListHead;
  ClientElem_t *pRes = NULL;
  while (pCurClient != NULL) {
    if (cIx == ix) {
      pRes = pCurClient;
      break;
    }
    cIx++;
    pCurClient = pCurClient->pNext;
  }
  return pRes;
}

/**
 * Returns the index of given client element.
 * @param pClient Pointer to client element whose index should
 *                be retreived.
 * @return the index or -1 if no such element
 */
static int getIndexByElement(ClientElem_t *pClient) {
  int res = -1;
  int ix = 0;
  ClientElem_t *pCurClient = pClientListHead;
  while (pCurClient != NULL) {
    if (pCurClient == pClient) {
      res = ix;
      break;
    }
    ix++;
    pCurClient = pCurClient->pNext;
  }
  return res;
}

//
// UART functions
//

static speed_t getSpeed(const char *pSpeed) {
  if (0 == strcmp("50", pSpeed)) {
    return B50;
  } else if (0 == strcmp("75", pSpeed)) {
    return B75;
  } else if (0 == strcmp("110", pSpeed)) {
    return B110;
  } else if (0 == strcmp("134", pSpeed)) {
    return B134;
  } else if (0 == strcmp("150", pSpeed)) {
    return B150;
  } else if (0 == strcmp("200", pSpeed)) {
    return B200;
  } else if (0 == strcmp("300", pSpeed)) {
    return B300;
  } else if (0 == strcmp("600", pSpeed)) {
    return B600;
  } else if (0 == strcmp("1200", pSpeed)) {
    return B1200;
  } else if (0 == strcmp("1800", pSpeed)) {
    return B1800;
  } else if (0 == strcmp("2400", pSpeed)) {
    return B2400;
  } else if (0 == strcmp("4800", pSpeed)) {
    return B4800;
  } else if (0 == strcmp("9600", pSpeed)) {
    return B9600;
  } else if (0 == strcmp("19200", pSpeed)) {
    return B19200;
  } else if (0 == strcmp("38400", pSpeed)) {
    return B38400;
#ifdef B57600
  } else if (0 == strcmp("57600", pSpeed)) {
    return B57600;
#endif
#ifdef B115200
  } else if (0 == strcmp("115200", pSpeed)) {
    return B115200;
#endif
#ifdef B230400
  } else if (0 == strcmp("230400", pSpeed)) {
    return B230400;
#endif
#ifdef B460800
  } else if (0 == strcmp("460800", pSpeed)) {
    return B460800;
#endif
#ifdef B500000
  } else if (0 == strcmp("500000", pSpeed)) {
    return B500000;
#endif
#ifdef B576000
  } else if (0 == strcmp("576000", pSpeed)) {
    return B576000;
#endif
#ifdef B921600
  } else if (0 == strcmp("921600", pSpeed)) {
    return B921600;
#endif
  }
  return 0;
}

#define SEND(m, ...) \
  do {\
    if (pClient->type == TYPE_BASH) { \
      fprintf(stdout, m"\n", ## __VA_ARGS__); fflush(stdout); \
    } else { \
      n = sprintf(pClient->resbuf, m "\n", ## __VA_ARGS__); \
      write(pClient->sockfd, &pClient->resbuf, n);\
    }\
  } while (0);

int setUART(ClientElem_t *pClient, int *argIx, int argc) {
  int n;
  int i;
  int fd = pClient->ttyfd;
  char *pCmd = pClient->cmdbuf;

  DBG_PRINT("UART CONFIG args %i", argc);

  /* parse arguments */
  for (i = 1; i < argc; i++) {
    DBG_PRINT("UART ARG %i : %s", i, &pCmd[argIx[i]]);
    switch (pCmd[argIx[i]]) {
    /* set baud rate */
    case 'B': {
      speed_t b = getSpeed(&pCmd[argIx[i] + 1]);
      if (b == 0 || cfsetispeed(&pClient->termSettings, b) < 0
          || cfsetospeed(&pClient->termSettings, b) < 0) {
        SEND("ERROR Baud rate not supported");
        return -1;
      }
    }
      break;
      /* set data bits */
    case 'D': {
      pClient->termSettings.c_cflag &= ~CSIZE;
      switch (pCmd[argIx[i] + 1]) {
      case '8':
        pClient->termSettings.c_cflag |= CS8;
        break;
      case '7':
        pClient->termSettings.c_cflag |= CS7;
        break;
      case '6':
        pClient->termSettings.c_cflag |= CS6;
        break;
      case '5':
        pClient->termSettings.c_cflag |= CS5;
        break;
      default:
        SEND("ERROR Number of databits not supported [5,6,7,8]");
        return -1;
      }
    }
      break;
      /* set stop bits */
    case 'S': {
      switch (pCmd[argIx[i] + 1]) {
      case '1':
        pClient->termSettings.c_cflag &= ~CSTOPB;
        break;
      case '2':
        pClient->termSettings.c_cflag |= CSTOPB;
        break;
      default:
        SEND("ERROR Number of stopbits not supported [1,2]");
        return -1;
      }
    }
      break;
      /* set parity */
    case 'P': {
      switch (pCmd[argIx[i] + 1]) {
      case 'n': /* none */
        pClient->termSettings.c_cflag &= ~PARENB;
        break;
      case 'o': /* odd */
        pClient->termSettings.c_cflag |= PARENB | PARODD;
        break;
      case 'e': /* even */
        pClient->termSettings.c_cflag |= PARENB;
        pClient->termSettings.c_cflag &= ~PARODD;
        break;
      default:
        SEND("ERROR Parity not supported [n,o,e]");
        return -1;
      }
    }
      break;
      /* set timeout */
    case 'T': {
      pClient->termSettings.c_cc[VTIME] = atoi(&pCmd[argIx[i] + 1]);
    }
      break;
      /* set vmin */
    case 'M': {
      pClient->termSettings.c_cc[VMIN] = atoi(&pCmd[argIx[i] + 1]);
    }
      break;
      /* set RTS */
    case 'r': {
      switch (pCmd[argIx[i] + 1]) {
      case '0':
        pClient->curStatus |= TIOCM_RTS;
        break;
      case '1':
        pClient->curStatus &= ~TIOCM_RTS;
        break;
      default:
        SEND("ERROR RTS setting not supported [0,1]");
        return -1;
      }
    }
      break;
      /* set DTR */
    case 'd': {
      switch (pCmd[argIx[i] + 1]) {
      case '0':
        pClient->curStatus |= TIOCM_DTR;
        break;
      case '1':
        pClient->curStatus &= ~TIOCM_DTR;
        break;
      default:
        SEND("ERROR DTR setting not supported [0,1]");
        return -1;
      }
    }
      break;
    }
  }

  /* set settings */
  if (tcsetattr(fd, TCSADRAIN, &pClient->termSettings) < 0) {
    SEND("ERROR Could not configure device");
    return -1;
  }

  /* set lines */
  if (ioctl(fd, TIOCMSET, &pClient->curStatus) < 0) {
    SEND("ERROR Could not configure lines");
    return -1;
  }
  return 0;
}

//
// Channel functions
//

/**
 * Reads a line of input, terminated by \n, into given buffer. The line
 * is split into parameters. Returns length of line. If max length is reached
 * the function returns directly reading no more input.
 * @param fd the descriptor to read from
 * @param pBuf buffer where to put data
 * @param len maximum line length
 * @param pArgIx pointer to argument index buffer
 * @param pArgs pointer to argument counter
 * @param error pointer to flag defining if read fails (0 = no fail)
 * @return length of line
 */
static int readLine(int fd, char *pBuf, int len, int *pArgIx, int *pArgs, int *error) {
  struct timeval time;
  fd_set set;
  int n;
  char i;
  int clen = 0;
  *pArgs = 0;
  pArgIx[0] = 0;
  int zeroByteCnt = 0;
  int run = 1;
  while (run) {
    FD_ZERO(&set);
    FD_SET(fd, &set);
    time.tv_sec = 1;
    time.tv_usec = 0;

    if (select(fd + 1, &set, NULL, NULL, &time) > 0) {
      n = read(fd, &i, 1);
      if (n < 0) {
        INFO("ERROR readLine");
        *error = 1;
        run = 0;
      } else if (n > 0) {
        zeroByteCnt = 0;
        if (i == '\n') {
          pBuf[clen] = 0;
          (*pArgs)++;
          break;
        }
        if (i != '\r') {
          if (i == ' ') {
            pBuf[clen++] = 0;
            (*pArgs)++;
            pArgIx[*pArgs] = clen;
          } else {
            pBuf[clen++] = i;
          }
        }
        if (clen == len - 1) {
          (*pArgs)++;
          break;
        }
      } else {
        zeroByteCnt++;
        if (zeroByteCnt > MAX_ZERO_LOOPS) {
          DBG_PRINT("ERROR readLine %i zero byte counts", zeroByteCnt);
          *error = 2;
          run = 0;
        }
      }
    }
  }

  return clen;
}

static int openDevice(ClientElem_t *pClient, char *dev) {
  int n;
  strncpy(pClient->deviceString, dev, 64);
  int ttyfd = open(dev, O_RDWR/* | O_DIRECT */| O_NONBLOCK);
  if (ttyfd < 0) {
    if (!pClient->keepOpen) SEND("ERROR could not open \"%s\": %s", dev, strerror(errno));
    return 1;
  } else {
    pClient->ttyfd = ttyfd;
    /* get current UART settings */
    if (tcgetattr(ttyfd, &pClient->termSettings) < 0) {
      SEND("ERROR could get configuration for \"%s\": %s",
          dev, strerror(errno));
      return -1;
    }
    /* get current UART line status */
    if (ioctl(ttyfd, TIOCMGET, &pClient->curStatus) < 0) {
      SEND("ERROR could not get status for \"%s\": %s",
          dev, strerror(errno));
      return -1;
    }
    /* disable input processing */
    pClient->termSettings.c_iflag &= ~(IGNBRK | BRKINT | ICRNL | INLCR
        | PARMRK | INPCK | ISTRIP | IXON | IXOFF);
    /* disable line processing */
    pClient->termSettings.c_lflag &= ~(ECHO | ECHONL | ICANON | IEXTEN
        | ISIG);
    /* disable character processing, no hang-up */
    pClient->termSettings.c_cflag &= ~(HUPCL);
    pClient->termSettings.c_cflag |= CREAD | CLOCAL;
    /* do not insert 0d before 0a */
    pClient->termSettings.c_oflag &= ~OPOST;
    return 0;
  }
}

/**
 * Parses a line of data given to a control channel.
 * @param pClient the client of the channel
 * @param argIx array with start index of each argument
 * @param argCount number of arguments
 * @return 0 on success, a negative value otherwise
 */
static int ctrlParse(ClientElem_t *pClient, int *argIx, int argCount,
    int cmdlen) {
  int n;
  int res = -1;
  char *pCmd = pClient->cmdbuf;

  switch (pCmd[0]) {
  /* Identify this control channel */
  case 'I': {
    SEND("%i", getIndexByElement(pClient));
    res = 0;
    break;
  }
    /* Attach this channel to given control channel and make it a data channel */
  case 'A': {
    int ix = atoi(&pCmd[argIx[1]]);
    ClientElem_t *pOtherClient = getElementByIndex(ix);
    if (pOtherClient == NULL) {
      SEND("ERROR no such channel");
    } else if (pClient == pOtherClient) {
      SEND("ERROR cannot attach to self");
    } else if (pOtherClient->ttyfd == -1) {
      SEND("ERROR channel not connected to device");
    } else {
      pClient->ttyfd = pOtherClient->ttyfd;
      pClient->type = TYPE_DATA;
      res = 0;
    }
    break;
  }
    /* Open device */
  case 'O': {
    res = openDevice(pClient, &pClient->cmdbuf[argIx[1]]);
    if (res > 0) res = 0;
    break;
  }
    /* Configure UART */
  case 'U': {
    res = setUART(pClient, argIx, argCount);
    break;
  }
    /* Kill client, close device */
  case 'C': {
    DBG_PRINT("Killing client");
    pClient->running = 0;
    res = 0;
    break;
  }
    /* Kill server */
  case 'X': {
    DBG_PRINT("Killing server");
    g_serverRunning = 0;
    res = 0;
    break;
  }
  default: {
    SEND("ERROR unkown command: %s [I - identify, A x - attach to x, O dev - open serial device, U cfg - configure UART, C - kill client, X - kill server]", pCmd);
  }
  }
  if (res == 0) {
    SEND("OK");
  }
  return res;
}

static int memcpyBash(ClientElem_t *pClient, int six, int eix, char *buf) {
  int n = 0;
  int bsix = pClient->bashIxBuf[six];
  int beix = pClient->bashIxBuf[eix];
  if (bsix > beix) {
    n = BASH_BUFFER_MAX - bsix - 1;
    memcpy(buf, &pClient->bashBuf[bsix], n);
    buf += n;
    bsix = 0;
  }
  n += beix - bsix;
  memcpy(buf, &pClient->bashBuf[bsix], beix - bsix);
  return n;
}

static int bashOut(ClientElem_t *pClient, int ix) {
  int i;
  int bsix = pClient->bashIxBuf[(ix-1) & (BASH_IX_BUFFER_MAX-1)];
  int beix = pClient->bashIxBuf[ix];
  if (bsix == beix || beix == -1) return 0;
  BASHOUT(BASH_CLEARLN);
  pClient->bashBufLen = pClient->bashIxBuf[(pClient->bashIx-1) & (BASH_IX_BUFFER_MAX-1)];
  if (bsix > beix) {
    for (i = bsix; i < BASH_BUFFER_MAX - bsix - 1; i++) {
      fprintf(stdout, "%c", pClient->bashBuf[i]);
      pClient->bashBuf[pClient->bashBufLen++] = pClient->bashBuf[i];
      if (pClient->bashBufLen >= BASH_BUFFER_MAX) {
        pClient->bashBufLen = 0;
      }
    }
    bsix = 0;
  }
  for (i = bsix; i < beix-1; i++) {
    fprintf(stdout, "%c", pClient->bashBuf[i]);
    pClient->bashBuf[pClient->bashBufLen++] = pClient->bashBuf[i];
    if (pClient->bashBufLen >= BASH_BUFFER_MAX) {
      pClient->bashBufLen = 0;
    }
  }
  fflush(stdout);
  return 1;
}

/**
 * Do own linebuffering and simple terminal handling in bash mode
 */
static int bashCheck(ClientElem_t *pClient, char **ppBuf, int *pn) {
  char *pBuf = *ppBuf;
  if (*pn == 1) {
    if (pBuf[0] == 0x7f) {
      // back
      if (pClient->bashBufLen == pClient->bashIxBuf[(pClient->bashIx - 1) & (BASH_IX_BUFFER_MAX-1)]) {
        BASHOUT(BASH_BSNO);
      } else {
        pClient->bashBufLen--;
        if (pClient->bashBufLen < 0) {
          pClient->bashBufLen = BASH_BUFFER_MAX - 1;
        }
        BASHOUT(BASH_BS);
      }
      return 0;
    }
  } else if (*pn == 3) {
    if (strncmp("\x1b[A", pBuf, 3) == 0) {
      // arrow up
      pClient->curBashIx = (pClient->curBashIx -1) & (BASH_IX_BUFFER_MAX-1);
      if (!bashOut(pClient, pClient->curBashIx)) {
        BASHOUT("\b\b\b\b    \b\b\b\b");
        pClient->curBashIx = (pClient->curBashIx +1) & (BASH_IX_BUFFER_MAX-1);
      }
      return 0;
    } else if (strncmp("\x1b[B", pBuf, 3) == 0) {
      // arrow down
      pClient->curBashIx = (pClient->curBashIx +1) & (BASH_IX_BUFFER_MAX-1);
      if (!bashOut(pClient, pClient->curBashIx)) {
        BASHOUT("\b\b\b\b    \b\b\b\b");
        pClient->curBashIx = (pClient->curBashIx -1) & (BASH_IX_BUFFER_MAX-1);
      }
      return 0;
    } else if (pBuf[0] == 0x1b) {
      // other esc seqs
      BASHOUT("\b\b\b\b    \b\b\b\b");
      return 0;
    }
  }

  int i;
  int prevBashIx = (pClient->bashIx - 1) & (BASH_IX_BUFFER_MAX-1);
  for (i = 0; i < *pn; i++) {
    pClient->bashBuf[pClient->bashBufLen++] = pBuf[i];
    if (pClient->bashBufLen >= BASH_BUFFER_MAX) {
      pClient->bashBufLen = 0;
    }
    if (pBuf[i] == '\n') {
      pClient->bashIxBuf[pClient->bashIx++] = pClient->bashBufLen;
      if (pClient->bashIx >= BASH_IX_BUFFER_MAX) {
        pClient->bashIx = 0;
      }
      pClient->curBashIx = pClient->bashIx;
    }
  }
  pClient->bashIxBuf[pClient->bashIx] = -1;
  int eix = (pClient->bashIx-1) & (BASH_IX_BUFFER_MAX-1);
  if (prevBashIx != eix) {
    *pn = memcpyBash(pClient, prevBashIx, eix, *ppBuf);
    return 1;
  } else {
    return 0;
  }
}

/**
 * Pipes data from ttyfd read to sockfd write and from sockfd read
 * to ttyfd write.
 * @param pClient the client whose fd:s to pipe
 */
static void pipeData(ClientElem_t *pClient) {
  struct timeval time;
  fd_set set;
  int n;
  int ttyfd = pClient->ttyfd;
  int sockin = pClient->sockfd;
  int sockout = pClient->sockfd;
  char buf[PIPE_BUF_SIZE];

  if (pClient->type == TYPE_BASH) {
    sockin = STDIN_FILENO;
    sockout = STDOUT_FILENO;
  }

#if USE_TCP_NODELAY
  int on = 1;
  if(setsockopt(sockfd, IPPROTO_TCP, TCP_NODELAY, (char*) &on,
          sizeof(int))<0)
  {
    INFO("TCP_NODELAY failed");
  }
#endif

  int zeroByteCnt = 0;
  while (pClient->running) {
    int maxfd = sockin > ttyfd ? sockin : ttyfd;
    FD_ZERO(&set);
    FD_SET(sockin, &set);
    FD_SET(pClient->ttyfd, &set);
    time.tv_sec = 1;
    time.tv_usec = 0;
    /* multiplex so we don't have to create yet another thread */
    if (select(maxfd + 1, &set, NULL, NULL, &time) > 0) {
      if (FD_ISSET(sockin, &set)) {
        /* want to send something to the uart */
        n = read(sockin, buf, PIPE_BUF_SIZE);
        if (n < 0) {
          INFO("ERROR hostread pipe");
          pClient->running = 0;
        } else if (n > 0) {
          char* pBuf = &buf[0];
          if (pClient->type != TYPE_BASH || bashCheck(pClient, (char**)&pBuf, &n) != 0) {
            if (write(ttyfd, buf, n) < 0) {
              INFO("ERROR ttywrite pipe");
              pClient->running = 0;
            }
          }
        } else {
          zeroByteCnt++;
        }
      }
      if (FD_ISSET(ttyfd, &set)) {
        /* got something on the uart */
        char keep_trying;
        do {
          keep_trying = 0;
          n = read(ttyfd, buf, PIPE_BUF_SIZE);
          if (n < 0 && errno == EAGAIN) {
            keep_trying = 1;
            continue;
          }
          if (n < 0) {
            if (errno == EAGAIN) {
            } else {
            }
            INFO("ERROR ttyread pipe %i", n);
            perror("perror");
            pClient->running = 0;
          } else if (n > 0) {
            if (write(sockout, buf, n) < 0) {
              INFO("ERROR hostwrite pipe");
              pClient->running = 0;
            }
            zeroByteCnt = 0;
          } else {
            zeroByteCnt++;
          }
        } while (keep_trying && pClient->running);
      }
      if (zeroByteCnt > MAX_ZERO_LOOPS) {
        INFO("ERROR pipeData %i zero byte counts", zeroByteCnt);
        pClient->running = 0;
      }
    }
  }
}

/**
 * Closes all data channels connected to given descriptor
 * @param ttyfd the descriptor identifying channels to close
 */
static void closeDataChannels(int ttyfd) {
  ClientElem_t *pCurClient = pClientListHead;
  while (pCurClient != NULL) {
    if (pCurClient->type == TYPE_DATA && pCurClient->ttyfd == ttyfd) {
      DBG_PRINT("dataclient 0x%p closing", pCurClient);
      pCurClient->running = 0;
    }
    pCurClient = pCurClient->pNext;
  }
}

/**
 * Main thread entry function for a data/control channel.
 */
static void *ctrlClientFunc(void *pVClient) {
  ClientElem_t *pClient = (ClientElem_t *) pVClient;
  int n;
  int argIx[CMD_BUF_LEN / 2];
  int argCount = 0;
  pClient->running = 1;

  DBG_PRINT("client running %p", pClient);

  /* Control channel */
  while (pClient->running && pClient->type == TYPE_CONTROL) {
    int readLineError;
    n = readLine(pClient->sockfd, pClient->cmdbuf, CMD_BUF_LEN, argIx,
        &argCount, &readLineError);
    if (readLineError != 0) {
      pClient->running = 0;
    }
    DBG_PRINT("readline returned %i, argCount:%i, running:%i %s", n, argCount, pClient->running, pClient->cmdbuf);
    if (n > 0 && pClient->running) {
      n = ctrlParse(pClient, argIx, argCount, n);
    }
  }
  if (pClient->running && pClient->type == TYPE_DATA) {
    /* Control channel morphed to a data channel */
    pipeData(pClient);
  }

  /* Close fd:s */
  close(pClient->sockfd);
  if (pClient->type == TYPE_CONTROL && pClient->ttyfd != -1) {
    close(pClient->ttyfd);
    closeDataChannels(pClient->ttyfd);
  }

  /* Cleanup */
  DBG_PRINT("client dead %p: %s", pClient, pClient->type == TYPE_DATA ? "DATA" : "CTRL");
  removeElementByAddress(pClient);

  g_liveClients--;
  return NULL;
}

/**
 * Creates a brand new control client, adds it to the list and starts a thread for it.
 * @param addr the inet address of the client
 * @param sockfd the socket descriptor this client is communicating via
 */
static void createControlClient(struct sockaddr_in addr, int sockfd) {
  ClientElem_t *pClient = newElement();
  pClient->sockfd = sockfd;
  pClient->addr = addr;
  pClient->type = TYPE_CONTROL;
  pthread_create(&pClient->thread, NULL, ctrlClientFunc, (void*) pClient);
  g_liveClients++;
}

//
// Client functions
//

/**
 * Marks all clients as dying.
 */
static void killAllClients() {
  ClientElem_t *pCurClient = pClientListHead;
  while (pCurClient != NULL) {
    pCurClient->running = 0;
    pCurClient = pCurClient->pNext;
  }
}

/**
 * Waits until all clients has died gracefully.
 */
static void awaitAllClients() {
  ClientElem_t *pCurClient = pClientListHead;
  while (pCurClient != NULL) {
    pthread_join(pCurClient->thread, NULL);
    pCurClient = pCurClient->pNext;
  }
}

/**
 * Frees all clients remaining in list.
 */
static void freeAllClients() {
  ClientElem_t *pCurClient = pClientListHead;
  while (pCurClient != NULL) {
    ClientElem_t *pNextClient = pCurClient->pNext;
    free(pCurClient);
    pCurClient = pNextClient;
  }
}

static void setNonBlocking(int sock) {
  int opts;

  opts = fcntl(sock, F_GETFL);
  if (opts < 0) {
    INFO("fcntl(F_GETFL)");
    exit(EXIT_FAILURE);
  }
  opts = (opts | O_NONBLOCK);
  if (fcntl(sock, F_SETFL, opts) < 0) {
    INFO("fcntl(F_SETFL)");
    exit(EXIT_FAILURE);
  }
}

static int openServer(int port) {
  int serverSocket;
  int clientSocket;
  socklen_t clilen;
  struct sockaddr_in serverAddress;
  struct sockaddr_in clientAddress;

  struct timeval time;
  fd_set set;

  /* Setup server socket */
  serverSocket = socket(AF_INET, SOCK_STREAM, 0);
  if (serverSocket < 0) {
    INFO("ERROR opening socket");
    return EXIT_FAILURE;
  }

  //setNonBlocking(serverSocket);

  bzero((char *) &serverAddress, sizeof(serverAddress));
  serverAddress.sin_family = AF_INET;
  serverAddress.sin_addr.s_addr = INADDR_ANY;
  serverAddress.sin_port = htons(port);
  if (bind(serverSocket, (struct sockaddr *) &serverAddress,
      sizeof(serverAddress)) < 0) {
    INFO("ERROR binding");
    return EXIT_FAILURE;
  }
  listen(serverSocket, 5);

  clilen = sizeof(clientAddress);
  /* Start accepting clients */
  do {
    FD_ZERO(&set);
    FD_SET(serverSocket, &set);

    time.tv_sec = 1;
    time.tv_usec = 0;

    if (select(serverSocket + 1, &set, NULL, NULL, &time) > 0) {
      clientSocket = accept(serverSocket,
          (struct sockaddr *) &clientAddress, &clilen);
      if (clientSocket < 0) {
        INFO("ERROR on accept");
        g_serverRunning = 0;
      } else {
        //setNonBlocking(clientSocket);
        createControlClient(clientAddress, clientSocket);
      }
    }
  } while (g_serverRunning);

  /* Close server socket */
  DBG_PRINT("Server closed");
  close(serverSocket);

  /* Tidy */
  killAllClients();
  awaitAllClients();
  freeAllClients();
  return EXIT_SUCCESS;
}

static void openTerminalClient(int argc, char **args, int keepOpen) {
  ClientElem_t client;
  int res;
  int i;
  int ix = 0;
  int argIx[CMD_BUF_LEN/2];
  int try = 0;

  memset(&client, 0, sizeof(client));

  // Use termios to turn off line buffering, we'll do our own
  struct termios term;
  tcgetattr(STDIN_FILENO, &term);
  term.c_lflag &= ~ICANON;
  tcsetattr(STDIN_FILENO, TCSANOW, &term);

  client.sockfd = STDIN_FILENO;
  client.type = TYPE_BASH;
  client.running = 1;
  for (i = 2; i < argc; i++) {
    argIx[i - 2] = ix;
    strcpy(&client.cmdbuf[ix], args[i]);
    ix += strlen(args[i]);
    client.cmdbuf[ix++] = '\0';
  }
  if (openDevice(&client, &client.cmdbuf[argIx[0]]) != 0) {
    return;
  }
  if (setUART(&client, argIx, argc-2) != 0) {
    close(client.ttyfd);
    return;
  }

  client.bashBuf = malloc(BASH_BUFFER_MAX);
  client.bashIxBuf = malloc(BASH_IX_BUFFER_MAX * sizeof(int));
  memset(client.bashIxBuf, 0xff, BASH_IX_BUFFER_MAX * sizeof(int));
  client.bashIxBuf[client.bashIx++] = 0;
  client.keepOpen = keepOpen;

  do {
    pipeData(&client);
    if (client.keepOpen) {
      int res;
      close(client.ttyfd);
      BASHOUT(BASH_CLEARLN"Connection lost, retry %i...", ++try);
      sleep(1);
      res = openDevice(&client, client.deviceString);
      if (res < 0) {
        break;
      } else if (res == 0) {
        res = tcsetattr(client.ttyfd, TCSADRAIN, &client.termSettings);
        if (res < 0) {
          break;
        }
        INFO("Reconnected");
        try = 0;
        client.running = 1;
      }
    }
  } while (client.keepOpen);

  free(client.bashBuf);
  free(client.bashIxBuf);

  close(client.ttyfd);
}

/**
 * Program entry function.
 */
int main(int argc, char **args) {
  int port;
  int res = EXIT_SUCCESS;

  port = 5000;

  if (argc == 2) {
    port = atoi(args[1]);
  } else if (argc >= 3 && strcmp("-o", args[1]) == 0) {
    openTerminalClient(argc, args, 0);
  } else if (argc >= 3 && strcmp("-O", args[1]) == 0) {
    openTerminalClient(argc, args, 1);
  } else {
    INFO("uartsocket "VERSION);
    INFO("usage: uartsocket -[o|O] <device> (<settings>)");
    INFO("       uartsocket <port>");
    INFO("       where -o simply opens port, and -O tries to hold port open if device fails");
    INFO("       where <settings> can be any combination of:");
    INFO("       B<baudrate> | D<databits> | S<stopbits> | P<parity (n|o|e)>");
    INFO("   ex: uartsocket -o /dev/ttyUSB0 B115200 D8 S1 Pn");
    INFO("         -- opens ttyUSB0 at 115200 bps, 8 databits, 1 stopbit and no parity in terminal");
    INFO("   ex: uartsocket 8000");
    INFO("         -- starts a uartsocket server listening to port 8000");
    INFO("         -- connect as control channel by 'nc localhost 8000'");
    INFO("            enter 'I' to find this channels ID");
    INFO("            enter 'U <port settings>' to configure port");
    INFO("            enter 'O /dev/ttyNNN' to open port");
    INFO("         -- now, connect as data channel by 'nc localhost 8000'");
    INFO("            attach to data stream by entering 'A <ctrl channel id>");
  }

  if (argc == 2) {
    res = openServer(port);
  }

  DBG_PRINT("exit...");

  return res;
}
