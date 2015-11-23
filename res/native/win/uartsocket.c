/*
 ============================================================================
 Name        : uartsocket.c
 Author      : Peter Andersson
 Version     : 1.1
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

 Description : Pipes a serial socket to a TCP socket.
               
 ============================================================================
 */
/* Windows programming... holy crap!
 * What took me three hours on linux took me three days on windows.
 * RIP win32...
 */

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif

#include <windows.h>
#include <winsock2.h>
#include <ws2tcpip.h>
#include <iphlpapi.h>
#include <stdlib.h>
#include <stdio.h>

#pragma comment(lib, "Ws2_32.lib")

//
// Definitions
//

#define TYPE_CONTROL 	0
#define TYPE_DATA		1

#define CMD_BUF_LEN 	256

#define PIPE_BUF_SIZE 	1024

#define DBG 0

#ifndef DBG
#define DBG 0
#endif

#if DBG
#define DBG_PRINT(x, ...) printf(x"\n", ## __VA_ARGS__)
#else
#define DBG_PRINT(x, ...)
#endif

/** ring buffer for rd/wr threads */
struct RingBuf_s {
	char *rbBuf;
	int rbPos;
	int rbLen;
	HANDLE rbMutex;
	HANDLE rbSigWrite;
	HANDLE rbSigRead;
};

typedef struct RingBuf_s RingBuf_t;

/**
 * Client struct, defines a client being either a control channel
 * or a data channel.
 */
struct ClientElem_s {
	/** flag indicating if client is running or should die */
	volatile int running;
	/** type of client, either data or control */
	int type;
	/** the socket this client is communicating via */
	SOCKET sockfd;
	/** the thread hosting this client */
	HANDLE thread; // FrSo
	/** extra threads needed on windows version */
	HANDLE threadToSo;
	HANDLE threadFrSe;
	HANDLE threadToSe;
	/** the descriptor this client is piping */
	HANDLE serialHdl;
	/** serial settings */
	DCB serialstats;
	/** port numerator */
	int portNumerator;
	/** uart status */
	int curStatus;
	/** ringbuf used from serial to socket */
	RingBuf_t rbSeSo;
	/** ringbuf used from socket to serial */
	RingBuf_t rbSoSe;

	OVERLAPPED ovRd;
    OVERLAPPED ovWr;
    OVERLAPPED ovCommRd;
	int pendingCommRd;
	volatile DWORD commMaskRd;

	char resbuf[CMD_BUF_LEN * 2];
	char cmdbuf[CMD_BUF_LEN];

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

/** winsock */
WSADATA wsaData;

//
// Ring buffer functions
//

static void initRingBuf(RingBuf_t *prb) {
	prb->rbBuf = malloc(PIPE_BUF_SIZE);
	prb->rbLen = 0;
	prb->rbPos = 0;
	prb->rbMutex = CreateMutex(NULL, FALSE, NULL);
	prb->rbSigRead = CreateEvent(NULL, TRUE, FALSE, NULL);
	prb->rbSigWrite = CreateEvent(NULL, TRUE, FALSE, NULL);
}

static void tidyRingBuf(RingBuf_t *prb) {
	free(prb->rbBuf);
	CloseHandle(prb->rbMutex);
	CloseHandle(prb->rbSigRead);
	CloseHandle(prb->rbSigWrite);
}

static int rbRead(RingBuf_t *prb, char *pData, int maxLen, int timeout) {
	int res;

	while (1) {
		res = WaitForSingleObject(prb->rbMutex, timeout);
		if (res == WAIT_TIMEOUT) {
			return 0;
		} else if (res != WAIT_OBJECT_0) {
			return -1;
		}
		if (prb->rbLen == 0) {
			ReleaseMutex(prb->rbMutex);
			res = WaitForSingleObject(prb->rbSigWrite, timeout);
			ResetEvent(prb->rbSigWrite);
			if (res == WAIT_TIMEOUT) {
				return 0;
			} else if (res != WAIT_OBJECT_0) {
				return -1;
			}
		} else {
			break;
		}
	}

	{
		int dataToRead = prb->rbLen > maxLen ? maxLen : prb->rbLen;
		if (prb->rbPos + dataToRead > PIPE_BUF_SIZE) {
			dataToRead = PIPE_BUF_SIZE - prb->rbPos;
		}

		memcpy(pData, &prb->rbBuf[prb->rbPos], dataToRead);
		prb->rbPos += dataToRead;
		if (prb->rbPos >= PIPE_BUF_SIZE) {
			prb->rbPos -= PIPE_BUF_SIZE;
		}
		prb->rbLen -= dataToRead;
		ReleaseMutex(prb->rbMutex);
		SetEvent(prb->rbSigRead);
		return dataToRead;
	}
}

static int rbWrite(RingBuf_t *prb, char *pData, int len, int timeout) {
	int res;

	while (1) {
		res = WaitForSingleObject(prb->rbMutex, timeout);
		if (res == WAIT_TIMEOUT) {
			return 0;
		} else if (res != WAIT_OBJECT_0) {
			return -1;
		}
		if (prb->rbLen >= PIPE_BUF_SIZE) {
			ReleaseMutex(prb->rbMutex);
			res = WaitForSingleObject(prb->rbSigRead, timeout);
			ResetEvent(prb->rbSigRead);
			if (res == WAIT_TIMEOUT) {
				return 0;
			} else if (res != WAIT_OBJECT_0) {
				return -1;
			}
		} else {
			break;
		}
	}
	{
		int dataToWrite;
		int wrix = prb->rbPos + prb->rbLen;
		if (wrix >= PIPE_BUF_SIZE) {
			wrix -= PIPE_BUF_SIZE;
		}

		dataToWrite = 
			len > PIPE_BUF_SIZE - prb->rbLen ? PIPE_BUF_SIZE - prb->rbLen : len;
		if (wrix + dataToWrite >= PIPE_BUF_SIZE) {
			dataToWrite = PIPE_BUF_SIZE - wrix;
		}
		memcpy(&prb->rbBuf[wrix], pData, dataToWrite);
		prb->rbLen += dataToWrite;
		ReleaseMutex(prb->rbMutex);
		SetEvent(prb->rbSigWrite);

		return dataToWrite;
	}
}

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
		pClient->portNumerator = -1;
		pClient->serialHdl = INVALID_HANDLE_VALUE;

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

static int getSpeed(const char *pSpeed) {
	if (0 == strcmp("50", pSpeed)) {
		return 50;
	} else if (0 == strcmp("75", pSpeed)) {
		return 75;
	} else if (0 == strcmp("110", pSpeed)) {
		return 110;
	} else if (0 == strcmp("134", pSpeed)) {
		return 134;
	} else if (0 == strcmp("150", pSpeed)) {
		return 150;
	} else if (0 == strcmp("200", pSpeed)) {
		return 200;
	} else if (0 == strcmp("300", pSpeed)) {
		return 300;
	} else if (0 == strcmp("600", pSpeed)) {
		return 600;
	} else if (0 == strcmp("1200", pSpeed)) {
		return 1200;
	} else if (0 == strcmp("1800", pSpeed)) {
		return 1800;
	} else if (0 == strcmp("2400", pSpeed)) {
		return 2400;
	} else if (0 == strcmp("4800", pSpeed)) {
		return 4800;
	} else if (0 == strcmp("9600", pSpeed)) {
		return 9600;
	} else if (0 == strcmp("19200", pSpeed)) {
		return 19200;
	} else if (0 == strcmp("38400", pSpeed)) {
		return 38400;
	} else if (0 == strcmp("57600", pSpeed)) {
		return 57600;
	} else if (0 == strcmp("115200", pSpeed)) {
		return 115200;
	} else if (0 == strcmp("230400", pSpeed)) {
		return 230400;
	} else if (0 == strcmp("460800", pSpeed)) {
		return 460800;
	} else if (0 == strcmp("500000", pSpeed)) {
		return 500000;
	} else if (0 == strcmp("576000", pSpeed)) {
		return 576000;
	} else if (0 == strcmp("921600", pSpeed)) {
		return 921600;
	}
	return 0;
}


#define SEND(m, ...) \
	do { \
		int n = sprintf(pClient->resbuf, m "\n", ## __VA_ARGS__); \
		send(pClient->sockfd, (const char*)&pClient->resbuf, n, 0); \
	} while (0);
#define RTS (1<<0)
#define DTR (1<<1)

int setUART(ClientElem_t *pClient, int *argIx, int argc) {
	int i;
	char *pCmd = pClient->cmdbuf;
	DCB *dcb = &pClient->serialstats;
	int dirtyDCB = 0;
	int dirtyLines = 0;

	/* parse arguments */
	for (i = 1; i < argc; i++) {
		DBG_PRINT("UART ARG %i : %s", i, &pCmd[argIx[i]]);
		switch (pCmd[argIx[i]]) {
		/* set baud rate */
		case 'B': {
			int b = getSpeed(&pCmd[argIx[i] + 1]);
			if (b == 0) {
				SEND("ERROR Baud rate not supported");
				return -1;
			}
			dirtyDCB = 1;
			dcb->BaudRate = b;
		}
		break;
		/* set data bits */
		case 'D': {
			int ds;
			switch(pCmd[argIx[i] + 1]) {
			case '8': ds = 8; break;
			case '7': ds = 7; break;
			case '6': ds = 6; break;
			case '5': ds = 5; break;
			default:
				SEND("ERROR Number of databits not supported [5,6,7,8]");
				return -1;
			}
			dirtyDCB = 1;
			dcb->ByteSize = ds;
		}
		break;
		/* set stop bits */
		case 'S': {
			int sb;
			switch(pCmd[argIx[i] + 1]) {
			case '1': sb = 0; break;
			case '2': sb = 2; break;
			default:
				SEND("ERROR Number of stopbits not supported [1,2]");
				return -1;
			}
			dirtyDCB = 1;
			dcb->StopBits = sb;
		}
		break;
		/* set parity */
		case 'P': {
			int p;
			switch(pCmd[argIx[i] + 1]) {
			case 'n': /* none */
				p = 0;
				break;
			case 'o': /* odd */
				p = 1;
				break;
			case 'e': /* even */
				p = 2;
				break;
			default:
				SEND("ERROR Parity not supported [n,o,e]");
				return -1;
			}
			dirtyDCB = 1;
			dcb->Parity = p;
		}
		break;
		/* set timeout */
		case 'T': {
			// not supported, will make bridge lag
			//dirtyDCB = 1;
		}
		break;
		/* set vmin */
		case 'M': {
			// not supported, no windows correspondance
			//dirtyDCB = 1;
		}
		break;
		/* set RTS */
		case 'r': {
			switch(pCmd[argIx[i] + 1]) {
			case '1': pClient->curStatus |= RTS; break;
			case '0': pClient->curStatus &= ~RTS; break;
			default:
				SEND("ERROR RTS setting not supported [0,1]");
				return -1;
			}
			dirtyLines = 1;
		}
		break;
		/* set DTR */
		case 'd': {
			switch(pCmd[argIx[i] + 1]) {
			case '1': pClient->curStatus |= DTR; break;
			case '0': pClient->curStatus &= ~DTR; break;
			default:
				SEND("ERROR DTR setting not supported [0,1]");
				return -1;
			}
			dirtyLines = 1;
		}
		break;
		}
	}

	/* configure device */
	if (dirtyDCB && !SetCommState(pClient->serialHdl, dcb)) {
		SEND("ERROR Could not configure device");
		return -1;
	}

	/* set lines */
	if (dirtyLines && !EscapeCommFunction(pClient->serialHdl,
		(pClient->curStatus & RTS) ? CLRRTS : SETRTS) ||
		!EscapeCommFunction(pClient->serialHdl,
		(pClient->curStatus & DTR) ? CLRDTR : SETDTR)) {
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
 * @param pRunning pointer to flag defining if read is interrupted
 * @return length of line
 */
static int readLine(SOCKET fd, char *pBuf, int len, int *pArgIx, int *pArgs, volatile int *pRunning) {
	struct timeval time;
	fd_set set;
	int n;
	char i;
	int clen = 0;
	*pArgs = 0;
	pArgIx[0] = 0;

	while (*pRunning) {
		FD_ZERO(&set);
		FD_SET(fd, &set);
		time.tv_sec = 1;
		time.tv_usec = 0;

		if (select(fd+1, &set, NULL, NULL, &time) > 0) {
			n = recv(fd, &i, 1, 0);
			if (n < 0) {
				DBG_PRINT("ERROR readLine");
				*pRunning = 0;
			} else {
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
			}
		}
	}

	return clen;
}

static int listDevices(ClientElem_t *pClient) {
	int res = 0;
	int size = 8192;
	TCHAR *pszDevices = 0;
	long dwChars = 0;
	TCHAR *ptr;

	while (res == 0 && dwChars == 0) {
		pszDevices = malloc(size);
		if (pszDevices != 0) {
			dwChars = QueryDosDevice(NULL, pszDevices, size/sizeof(TCHAR));
			if (dwChars == 0) {
				DWORD err = GetLastError();
				if (err == ERROR_INSUFFICIENT_BUFFER) {
					size *= 2;
					if (size > 1024*1024) {
						SEND("ERROR cannot list com ports, QueryDosDevice too hungry");
						res = -1;
					} else {
						free(pszDevices);
					}
				} else {
					SEND("ERROR cannot list com ports, %i", err);
					res = -1;
				}
			}
		} else {
			SEND("ERROR cannot list com ports, out of memory");
			res = -1;
		}
	}
	ptr = pszDevices;
	while (res >= 0 && dwChars > 0) {
		int port;
		TCHAR *pTmp;
		if (swscanf(ptr, TEXT("COM%i"), &port) == 1) {
			SEND("COM%i", port);
		}
		pTmp = wcschr(ptr, 0);
		dwChars -= (DWORD)((pTmp - ptr) / sizeof(TCHAR) + 1);
		ptr = pTmp + 1;
	}
	free(pszDevices);
	return res;
}

static int openDevice(ClientElem_t *pClient, int *argIx, int argCount, int cmdlen) {
	char *pCmd = pClient->cmdbuf;
	size_t origsize;
	size_t convertedChars = 0;
	wchar_t wcstring[100];
	HANDLE serialHdl = INVALID_HANDLE_VALUE;
	char portname[100] = "\\\\.\\";

	/* Open port */
	strcpy(&portname[4], &pCmd[argIx[1]]);
	origsize = strlen(portname) + 1;
	mbstowcs_s(&convertedChars, wcstring, origsize, portname, _TRUNCATE);

	serialHdl = CreateFile( wcstring,
                 GENERIC_READ | GENERIC_WRITE,
                 0,    // must be opened with exclusive-access
                 NULL, // no security attributes
                 OPEN_EXISTING, // must use OPEN_EXISTING
                 FILE_FLAG_OVERLAPPED, // overlapped I/O or not
                 NULL  // no template for comm devices
                 );
	if (serialHdl == INVALID_HANDLE_VALUE) {
		SEND("ERROR could not open \"%s\", %ld", &portname[0], GetLastError());
		return -1;
	}

	pClient->serialHdl = serialHdl;
	pClient->portNumerator = atoi(&portname[4+3]);

	/* Define timeouts */
	{
		COMMTIMEOUTS comTimeOut;                   
		comTimeOut.ReadIntervalTimeout = MAXDWORD;
		comTimeOut.ReadTotalTimeoutMultiplier = 0;
		comTimeOut.ReadTotalTimeoutConstant = 0;
		comTimeOut.WriteTotalTimeoutMultiplier = 0;
		comTimeOut.WriteTotalTimeoutConstant = 0;
		if (!SetCommTimeouts(serialHdl, &comTimeOut)) {
			SEND("ERROR could not configure \"%s\", %ld", &portname[0], GetLastError());
			CloseHandle(serialHdl);
			return -1;
		}
	}

	{
		/* Get current settings */
		GetCommState(serialHdl, &pClient->serialstats);

		pClient->serialstats.DCBlength = sizeof(DCB);
		pClient->serialstats.fBinary					=	TRUE;
		pClient->serialstats.fDsrSensitivity			=	FALSE;
		pClient->serialstats.fParity					=	FALSE;
		pClient->serialstats.fOutX						=	FALSE;
		pClient->serialstats.fInX						=	FALSE;
		pClient->serialstats.fNull						=	FALSE;
		pClient->serialstats.fOutxCtsFlow				=	FALSE;
		pClient->serialstats.fOutxDsrFlow				=	FALSE;
		pClient->serialstats.fDtrControl				=	DTR_CONTROL_DISABLE;
		pClient->serialstats.fDsrSensitivity			=	FALSE;
		pClient->serialstats.fRtsControl				=	RTS_CONTROL_DISABLE;
		pClient->serialstats.fOutxCtsFlow				=	FALSE;
		pClient->serialstats.fOutxCtsFlow				=	FALSE;
		pClient->serialstats.fAbortOnError				=	FALSE;
		pClient->serialstats.fErrorChar					=	FALSE;

		if (!SetCommState(serialHdl, &pClient->serialstats)) {
			SEND("ERROR could not configure \"%s\", %ld", &portname[0], GetLastError());
			CloseHandle(serialHdl);
			return -1;
		}
	}

	if (!SetCommMask(serialHdl, EV_RXCHAR)) {
			SEND("ERROR could not define events for \"%s\", %ld", &portname[0], GetLastError());
			CloseHandle(serialHdl);
			return -1;
	}

	return 0;
}

/**
 * Parses a line of data given to a control channel.
 * @param pClient the client of the channel
 * @param argIx array with start index of each argument
 * @param argCount number of arguments
 * @return 0 on success, a negative value otherwise
 */
static int ctrlParse(ClientElem_t *pClient, int *argIx, int argCount, int cmdlen) {
	int res = -1;
	char *pCmd = pClient->cmdbuf;

	switch (pCmd[0]) {
		 /* Identify this control channel */
		case 'I': {
			SEND("%i", getIndexByElement(pClient))
			res = 0;
			break;
		}
		/* Attach this channel to given control channel and make it a data channel */
		case 'A': {
			int ix = atoi(&pCmd[argIx[1]]);
			ClientElem_t *pOtherClient = getElementByIndex(ix);
			if (pOtherClient == NULL) {
				SEND("ERROR no such channel")
			} else if (pClient == pOtherClient) {
				SEND("ERROR cannot attach to self")
			} else if (pOtherClient->portNumerator == -1) {
				SEND("ERROR channel not connected to device")
			} else {
				pClient->serialHdl = pOtherClient->serialHdl;
				pClient->portNumerator = pOtherClient->portNumerator;
				pClient->type = TYPE_DATA;
				res = 0;
			}
			break;
		}
		/* List devices */
		case 'L': {
			res = listDevices(pClient);
			break;
		}
		/* Open device */
		case 'O': {
			if (openDevice(pClient, argIx, argCount, cmdlen) >= 0) {
				res = 0;
			}
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
			SEND("ERROR unkown command: %s", pCmd)
		}
	}
	if (res == 0) {
		SEND("OK")
	}
	return res;
}

//
// Data pipe functionality
//

static BOOL readSerial(ClientElem_t *pClient, char* buffer, int maxRead, int* pRead, int timeout) {
	BOOL res;
	if (!pClient->pendingCommRd) {
		res = WaitCommEvent(pClient->serialHdl, &pClient->commMaskRd, &pClient->ovCommRd);
		if (!res) {
			int err = GetLastError();
			if (err != ERROR_IO_PENDING) {
				// readfile call error
				return FALSE;
			} else {
				pClient->pendingCommRd = TRUE;
				*pRead = 0;
				return TRUE;
			}
		}
	}
	res = WaitForSingleObject(pClient->ovCommRd.hEvent, timeout);
	ResetEvent(pClient->ovCommRd.hEvent);
	if (res == WAIT_TIMEOUT) {
		*pRead = 0;
		return TRUE;
	} else if (res != WAIT_OBJECT_0) {
		return FALSE;
	} 
	pClient->pendingCommRd = FALSE;

	res = ReadFile(pClient->serialHdl, buffer, maxRead, pRead, &pClient->ovRd);
	if (!res) {
		int err = GetLastError();
		if (err != ERROR_IO_PENDING) {
			// readfile call error
			return FALSE;
		} else {
			// async op
			res = WaitForSingleObject(pClient->ovRd.hEvent, timeout);
			if (res == WAIT_TIMEOUT) {
				*pRead = 0;
				return TRUE;
			} else if (res != WAIT_OBJECT_0) {
				return FALSE;
			} 
			res = GetOverlappedResult(pClient->serialHdl, &pClient->ovRd, pRead, FALSE);
			if (!res) {
				int err = GetLastError();
				return FALSE;
			}
			ResetEvent(pClient->ovRd.hEvent);

		}
	} else {
		// sync op
	}
	
	DBG_PRINT("uart-->%i", *pRead);
	return TRUE;
}

static BOOL writeSerial(ClientElem_t *pClient, const char* buffer, int len, int* pWritten, int timeout) {
	BOOL res;

	res = WriteFile(pClient->serialHdl, buffer, len, pWritten, &pClient->ovWr);
	if (!res) {
		int err = GetLastError();
		if (err != ERROR_IO_PENDING) {
			// writefile call error
			return FALSE;
		} else {
			// async
			res = WaitForSingleObject(pClient->ovWr.hEvent, timeout);
			if (res == WAIT_TIMEOUT) {
				*pWritten = 0;
				return TRUE;
			} else if (res != WAIT_OBJECT_0) {
				return FALSE;
			} 
			ResetEvent(pClient->ovWr.hEvent);
			res = GetOverlappedResult(pClient->serialHdl, &pClient->ovWr, pWritten, TRUE);
			if (!res) {
				int err = GetLastError();
					// writefile call error
				return FALSE;
			}
		}
	} else {
		// synced
	}
	
	DBG_PRINT("uart<--%i", *pWritten);
	return TRUE;
}

static void pipeDataFrSo(ClientElem_t *pClient) {
	struct timeval time;
	fd_set set;
	char *buf = malloc(PIPE_BUF_SIZE);

	while (pClient->running) {
		FD_ZERO(&set);
		FD_SET(pClient->sockfd, &set);
		time.tv_sec = 1;
		time.tv_usec = 0;
		if (select(0, &set, NULL, NULL, &time) > 0) {
			int nBytesRead = 0;
			nBytesRead = recv(pClient->sockfd, &buf[0], PIPE_BUF_SIZE, 0);
			if (nBytesRead == SOCKET_ERROR) {
				DBG_PRINT("ERROR sockread pipe");
				pClient->running = 0;
			} else if (nBytesRead > 0) {
				int wrIx = 0;
				int wrLen = 0;
				DBG_PRINT("SO-> put %i bytes in buf", nBytesRead);
				while (nBytesRead > 0 && wrLen >= 0 && pClient->running) {
					wrLen = rbWrite(&pClient->rbSoSe, &buf[wrIx], nBytesRead, 1000);
					nBytesRead -= wrLen;
					wrIx += wrLen;
				}
				if (wrLen < 0) {
					DBG_PRINT("ERROR sockwrbuf pipe");
					pClient->running = 0;
				}
			}
		}
	}
	DBG_PRINT("SO->X");
	free(buf);
}

static DWORD WINAPI pipeDataToSe(LPVOID pVClient) {
	char *buf = malloc(PIPE_BUF_SIZE);
	ClientElem_t *pClient = (ClientElem_t *)pVClient;
	while (pClient->running) {
		int nBytesRead = rbRead(&pClient->rbSoSe, buf, PIPE_BUF_SIZE, 1000);
		if (nBytesRead > 0) 
			DBG_PRINT("->SE got %i bytes from buf", nBytesRead);
		else 
			DBG_PRINT("->SE");
		if (nBytesRead < 0) {
			DBG_PRINT("ERROR uartrdbuf pipe");
			pClient->running = 0;
		} else if (nBytesRead > 0) {
			int wrIx = 0;
			int wrLen = 0;
			while (nBytesRead > 0 && wrLen >= 0 && pClient->running) {
				if (!writeSerial(pClient, (const char*)&buf[wrIx], nBytesRead, &wrLen, 1000)) {
					DBG_PRINT("ERROR uartwrite pipe %ld, hdl %ld", GetLastError(), pClient->serialHdl);
					pClient->running = 0;
				} else {
					wrIx += wrLen;
					nBytesRead -= wrLen;
				}
			}
		}
	}
	free(buf);
	DBG_PRINT("X->SE");
	return 0;
}


static DWORD WINAPI pipeDataFrSe(LPVOID pVClient) {
	char *buf = malloc(PIPE_BUF_SIZE);
	ClientElem_t *pClient = (ClientElem_t *)pVClient;
	while (pClient->running) {
		int nBytesRead;
		if (!readSerial(pClient, &buf[0], PIPE_BUF_SIZE, &nBytesRead, 1000)) {
			DBG_PRINT("ERROR uartread pipe %ld, hdl %ld", GetLastError(), pClient->serialHdl);
			pClient->running = 0;
		} else if (nBytesRead > 0) {
			int wrIx = 0;
			int wrLen = 0;
			DBG_PRINT("SE-> put %i bytes in buf", nBytesRead);
			while (nBytesRead > 0 && wrLen >= 0 && pClient->running) {
				wrLen = rbWrite(&pClient->rbSeSo, &buf[wrIx], nBytesRead, 1000);
				nBytesRead -= wrLen;
				wrIx += wrLen;
			}
			if (wrLen < 0) {
				DBG_PRINT("ERROR uartwrbuf pipe");
				pClient->running = 0;
			}
		}
	}
	free(buf);
	DBG_PRINT("SE->X");
	return 0;
}

static DWORD WINAPI pipeDataToSo(LPVOID pVClient) {
	char *buf = malloc(PIPE_BUF_SIZE);
	ClientElem_t *pClient = (ClientElem_t *)pVClient;
	while (pClient->running) {
		int nBytesRead = rbRead(&pClient->rbSeSo, buf, PIPE_BUF_SIZE, 1000);
		if (nBytesRead > 0)
			DBG_PRINT("->SO got %i bytes from buf", nBytesRead);
		else
			DBG_PRINT("->SO");
		if (nBytesRead < 0) {
			DBG_PRINT("ERROR uartrdbuf pipe");
			pClient->running = 0;
		} else if (nBytesRead > 0) {
			int wrIx = 0;
			int wrLen = 0;
			while (nBytesRead > 0 && wrLen >= 0 && pClient->running) {
				wrLen = send(pClient->sockfd, (const char*)&buf[wrIx], nBytesRead, 0);
				if (wrLen == SOCKET_ERROR) {
					DBG_PRINT("ERROR sockwrite pipe");
					pClient->running = 0;
				} else {
					wrIx += wrLen;
					nBytesRead -= wrLen;
				}
			}
		}
	}
	free(buf);
	DBG_PRINT("X->SO");
	return 0;
}



/**
 * Closes all data channels connected to given portnumber
 * @param portNumber the descriptor identifying channels to close
 */
static void closeDataChannels(int portNumber) {
	ClientElem_t *pCurClient = pClientListHead;
	while (pCurClient != NULL) {
		if (pCurClient->type == TYPE_DATA && pCurClient->portNumerator == portNumber) {
			DBG_PRINT("dataclient 0x%08X closing", (int)pCurClient);
			pCurClient->running = 0;
		}
		pCurClient = pCurClient->pNext;
	}
}

static void startDataClient(ClientElem_t *pClient) {
	pClient->pendingCommRd = FALSE;

	initRingBuf(&pClient->rbSoSe);
	initRingBuf(&pClient->rbSeSo);

	ZeroMemory(&pClient->ovRd, sizeof(pClient->ovRd));
	ZeroMemory(&pClient->ovWr, sizeof(pClient->ovWr));
	ZeroMemory(&pClient->ovCommRd, sizeof(pClient->ovCommRd));
	pClient->ovRd.hEvent = CreateEvent(NULL, TRUE, FALSE, NULL);
	pClient->ovWr.hEvent = CreateEvent(NULL, TRUE, FALSE, NULL);
	pClient->ovCommRd.hEvent = CreateEvent(NULL, TRUE, FALSE, NULL);

	pClient->threadToSo = CreateThread(NULL, 0, pipeDataToSo, pClient, 0, NULL);
	pClient->threadFrSe = CreateThread(NULL, 0, pipeDataFrSe, pClient, 0, NULL);
	pClient->threadToSe = CreateThread(NULL, 0, pipeDataToSe, pClient, 0, NULL);
	pipeDataFrSo(pClient);
}

static void closeDataClient(ClientElem_t *pClient) {
	WaitForSingleObject(pClient->threadToSo, INFINITE);
	WaitForSingleObject(pClient->threadFrSe, INFINITE);
	WaitForSingleObject(pClient->threadToSe, INFINITE);

	CloseHandle(pClient->ovRd.hEvent);
	CloseHandle(pClient->ovWr.hEvent);
	CloseHandle(pClient->ovCommRd.hEvent);

	CloseHandle(pClient->threadToSo);
	CloseHandle(pClient->threadFrSe);
	CloseHandle(pClient->threadToSe);

	tidyRingBuf(&pClient->rbSoSe);
	tidyRingBuf(&pClient->rbSeSo);
}

/**
 * Main thread entry function for a data/control channel.
 */
static DWORD WINAPI ctrlClientFunc(LPVOID pVClient) {
	ClientElem_t *pClient = (ClientElem_t *) pVClient;
	int n;
	int argIx[CMD_BUF_LEN/2];
	int argCount = 0;
	pClient->running = 1;

	DBG_PRINT("client running 0x%08X", (int)pClient);

	/* Control channel */
	while (pClient->running && pClient->type == TYPE_CONTROL) {
		n = readLine(pClient->sockfd, pClient->cmdbuf, CMD_BUF_LEN,
				argIx, &argCount, &pClient->running);
		if (n == 0) {
			pClient->running = 0;
		} else if (pClient->running) {
			n = ctrlParse(pClient, argIx, argCount, n);
		}
	}

	/* Request to turn this into a data channel? */
	if (pClient->running && pClient->type == TYPE_DATA) {
		startDataClient(pClient);
		closeDataClient(pClient);
	}

	/* Cleanup */
	closesocket(pClient->sockfd);
	if (pClient->type == TYPE_CONTROL && pClient->portNumerator != -1) {
		if (pClient->serialHdl != INVALID_HANDLE_VALUE) {
			CloseHandle(pClient->serialHdl);
		}
		closeDataChannels(pClient->portNumerator);
	}

	DBG_PRINT("client dead 0x%08X: %s", (int)pClient, pClient->type == TYPE_DATA ? "DATA" : "CTRL");
	CloseHandle(pClient->thread);
	removeElementByAddress(pClient);

	g_liveClients--;
	return 0;
}

/**
 * Creates a brand new control client, adds it to the list and starts a thread for it.
 * @param addr the inet address of the client
 * @param sockfd the socket descriptor this client is communicating via
 */
static void createControlClient(int addr, SOCKET sockfd) {
	ClientElem_t *pClient = newElement();
	pClient->sockfd = sockfd;
	pClient->type = TYPE_CONTROL;
	pClient->thread = 
		CreateThread(NULL, //Choose default security
					 0, //Default stack size
					 ctrlClientFunc,
					 pClient, //Thread parameter
					 0, //Immediately run the thread
					 NULL);
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
		WaitForSingleObject(pCurClient->thread, INFINITE);
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

int main(int argc, char *args[]) {
	int port;
	int res;
	struct addrinfo *result = NULL, hints;
	SOCKET serverSocket = INVALID_SOCKET;
	char portbuf[16];

	struct timeval time;
	fd_set set;

	port = 5000;
	if (argc >= 2) {
		port = atoi(args[1]);
	}
	DBG_PRINT("server @ port %i", port);

	/* Initialize Winsock */
	res = WSAStartup(MAKEWORD(2,2), &wsaData);
	if (res != 0) {
	    DBG_PRINT("WSAStartup failed: %i", res);
	    return 1;
	}
	
	/* Setup server socket */
	ZeroMemory(&hints, sizeof (hints));
	hints.ai_family = AF_INET;
	hints.ai_socktype = SOCK_STREAM;
	hints.ai_protocol = IPPROTO_TCP;
	hints.ai_flags = AI_PASSIVE;

	/* Resolve the local address and port to be used by the server */
	res = getaddrinfo(NULL, itoa(port, portbuf, 10), &hints, &result);
	if (res != 0) {
		DBG_PRINT("getaddrinfo failed: %i", res);
		goto error_server_setup0;
	}

	/* Create a SOCKET for the server to listen for client connections */
	serverSocket = socket(result->ai_family, result->ai_socktype, result->ai_protocol);
	if (serverSocket == INVALID_SOCKET) {
		printf("error at socket(): %ld", WSAGetLastError());
		goto error_server_setup1;
	}

	/* Setup the TCP listening socket */
    res = bind(serverSocket, result->ai_addr, (int)result->ai_addrlen);
    if (res == SOCKET_ERROR) {
		DBG_PRINT("bind failed with error: %ld", WSAGetLastError());
		goto error_server_setup2;
    }

	if ( listen( serverSocket, SOMAXCONN ) == SOCKET_ERROR ) {
		DBG_PRINT("listen failed with error: %ld", WSAGetLastError() );
		goto error_server_setup2;
	}

	/* Start accepting clients */
	do {
		FD_ZERO(&set);
		FD_SET(serverSocket, &set);

		time.tv_sec = 1;
		time.tv_usec = 0;
		if (select(0, &set, NULL, NULL, &time) > 0) {
			SOCKET clientSocket = INVALID_SOCKET;
			clientSocket = accept(serverSocket, NULL, NULL);
			if (clientSocket == INVALID_SOCKET) {
				DBG_PRINT("accept failed: %d", WSAGetLastError());
			} else {
				createControlClient(0, clientSocket);
			}
		}
	} while (g_serverRunning);

error_server_setup2:
    closesocket(serverSocket);
error_server_setup1:
    freeaddrinfo(result);
error_server_setup0:
	WSACleanup();

	/* Tidy */
	killAllClients();
	awaitAllClients();
	freeAllClients();

	DBG_PRINT("EXIT");

	return 0;
}
