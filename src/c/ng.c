/*   

  Copyright 2004, Martian Software, Inc.

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
  
*/

/**
 * @author <a href="http://www.martiansoftware.com/contact.html">Marty Lamb</a>
 */
 
#include <arpa/inet.h>
#include <netdb.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>

#ifdef WINDOWS
#define FILE_SEPARATOR '\\'
#else
#define FILE_SEPARATOR '/'
#endif

#ifndef MIN
#define MIN(a,b) ((a<b)?(a):(b))
#endif

#define NAILGUN_CLIENT_NAME "ng"
#define NAILGUN_PORT_DEFAULT "2113"
#define CHUNK_HEADER_LEN (5)

/* buffer used for reading and writing chunk headers */
char header[CHUNK_HEADER_LEN];

#define NAILGUN_SOCKET_FAILED (999)
#define NAILGUN_CONNECT_FAILED (998)
#define NAILGUN_UNEXPECTED_CHUNKTYPE (997)
#define NAILGUN_EXCEPTION_ON_SERVER (996)
#define NAILGUN_CONNECTION_BROKEN (995)

#define CHUNKTYPE_STDIN '0'
#define CHUNKTYPE_STDOUT '1'
#define CHUNKTYPE_STDERR '2'
#define CHUNKTYPE_STDIN_EOF '.'
#define CHUNKTYPE_ARG 'A'
#define CHUNKTYPE_ENV 'E'
#define CHUNKTYPE_DIR 'D'
#define CHUNKTYPE_CMD 'C'
#define CHUNKTYPE_EXIT 'X'

#define BUFSIZE (2048)

/* buffer used for reading an writing chunk data */
char buf[BUFSIZE];

/* the socket connected to the nailgun server */
int nailgunsocket = 0;

/**
 * Sends everything in the specified buffer to the specified
 * socket.  This is lifted character-for-character from the
 * excellent "Beej's Guide to Network Programming"
 *
 * @param s the socket descriptor
 * @param buf the buffer containing the data to send
 * @param len the number of bytes to send.  Also used to
 *            return the number of bytes sent.
 * @return total bytes written or 0 if failure
 */
int sendall(unsigned int s, char *buf, int len) {
  int total = 0;      
  int bytesleft = len; 
  int n;

  while(total < len) {
    n = send(s, buf+total, bytesleft, 0);
    if (n == -1) { break; }
      total += n;
      bytesleft -= n;
    }

    return n==-1?0:total; 
}

/**
 * Sends a chunk header noting the specified payload size and chunk type.
 * 
 * @param size the payload size
 * @param chunkType the chunk type identifier
 */
void sendHeader(unsigned int size, char chunkType) {
	header[0] = (size >> 24) & 0xff;
	header[1] = (size >> 16) & 0xff;
	header[2] = (size >> 8) & 0xff;
	header[3] = size & 0xff;
	header[4] = chunkType;
	sendall(nailgunsocket, header, CHUNK_HEADER_LEN);
}

/**
 * Sends a null-terminated string with the specified chunk type.
 *
 * @param chunkType the chunk type identifier
 * @param text the null-terminated string to send
 */
void sendText(char chunkType, char *text) {
	int len = strlen(text);
	sendHeader(len, chunkType);
	sendall(nailgunsocket, text, len);
}

/**
 * Exits the client if the nailgun server ungracefully shut down the connection.
 */
void handleSocketClose() {
	close(nailgunsocket);
	exit(NAILGUN_CONNECTION_BROKEN);
}

/**
 * Receives len bytes from the nailgun socket and copies them to the specified file descriptor.
 * Used to route data to stdout or stderr on the client.
 *
 * @param destFD the destination file descriptor (stdout or stderr)
 * @param len the number of bytes to copy
 */
void recvToFD(int destFD, unsigned long len) {
	unsigned long bytesRead = 0;
	int bytesCopied;
	
	while (bytesRead < len) {
		unsigned long bytesRemaining = len - bytesRead;
		int bytesToRead = (BUFSIZE < bytesRemaining) ? BUFSIZE : bytesRemaining;
		int thisPass = recv(nailgunsocket, buf, bytesToRead, MSG_WAITALL);
		if (thisPass < 0) {
			handleSocketClose();
		}
		bytesRead += thisPass;

		bytesCopied = 0;
		while(bytesCopied < thisPass) {
			bytesCopied += write(destFD, buf + bytesCopied, thisPass - bytesCopied);
		}	
	}
}

/**
 * Processes an exit chunk from the server.  This is just a string
 * containing the exit code in decimal format.  It should fit well
 * within our buffer, so assume that it does.
 *
 * @param len the current length of the buffer containing the exit code.
 */
void processExit(unsigned long len) {
	int exitcode;
	int bytesToRead = (BUFSIZE - 1 < len) ? BUFSIZE - 1 : len;
	int bytesRead = recv(nailgunsocket, buf, bytesToRead, MSG_WAITALL);
	if (bytesRead < 0) {
		handleSocketClose();
	}
	buf[bytesRead] = 0;
	exitcode = atoi(buf);
	close(nailgunsocket);
	exit(exitcode);
}

/**
 * Processes data from the nailgun server.
 */
void processnailgunstream() {
	int bytesRead = 0;
	unsigned long len;
	char chunkType;
	
	bytesRead = recv(nailgunsocket, buf, CHUNK_HEADER_LEN, MSG_WAITALL);
	if (bytesRead < CHUNK_HEADER_LEN) {
		handleSocketClose();
	}
	
	len = (buf[0] << 24)
		+ (buf[1] << 16)
		+ (buf[2] << 8)
		+ (buf[3]);
	
	chunkType = buf[4];
	
	switch(chunkType) {
		case CHUNKTYPE_STDOUT:	recvToFD(STDOUT_FILENO, len);
					break;
		case CHUNKTYPE_STDERR: 	recvToFD(STDERR_FILENO, len);
					break;
		case CHUNKTYPE_EXIT: 	processExit(len);
					break;
		default:	fprintf(stderr, "Unexpected chunk type %d ('%c')\n", chunkType, chunkType);
				exit(NAILGUN_UNEXPECTED_CHUNKTYPE);
	}
}

/**
 * Sends len bytes from buf to the nailgun server in a stdin chunk.
 *
 * @param len the number of bytes to send
 */
void sendStdin(unsigned int len) {
	sendHeader(len, CHUNKTYPE_STDIN);
	sendall(nailgunsocket, buf, len);
}

/**
 * Sends a stdin-eof chunk to the nailgun server
 */
void processEof() {
	sendHeader(0, CHUNKTYPE_STDIN_EOF);
}

/**
 * Reads from stdin and transmits it to the nailgun server in a stdin chunk.
 * Sends a stdin-eof chunk if necessary.
 *
 * @return zero if eof has been reached.
 */
int processStdin() {
	int bytesread = read(STDIN_FILENO, buf, BUFSIZE);
	if (bytesread > 0) {
		sendStdin(bytesread);
	} else if (bytesread == 0) {
		processEof();
	}
	return(bytesread);
}


/**
 * Trims any path info from the beginning of argv[0] to determine
 * the name used to launch the client.
 *
 * @param s argv[0]
 */
char *shortClientName(char *s) {
	char *result = strrchr(s, FILE_SEPARATOR);
	return ((result == NULL) ? s : result + 1);
}

/**
 * Returns true if the specified string is the name of the nailgun
 * client.  The comparison is made case-insensitively for windows.
 *
 * @param s the program name to check
 */
int isNailgunClientName(char *s) {
	#ifdef WINDOWS
		return(!strcasecmp(s, NAILGUN_CLIENT_NAME));
	#else
		return(!strcmp(s, NAILGUN_CLIENT_NAME));
	#endif
}

/**
 * Displays usage info and bails
 */
void usage() {
	fprintf(stderr, "Usage: ng [--nailgun-server server] [--nailgun-port port] command [arg1...]\n");
	exit(-1);
}

int main(int argc, char *argv[], char *env[]) {
	int i;
	struct sockaddr_in server_addr;
	int eof = 0;
	fd_set readfds;
	char *nailgun_server;        /* server as specified by user */
	char *nailgun_port;          /* port as specified by user */
	char *cwd;
	int port;                    /* int version of port */
   	struct hostent *hostinfo;
	char *cmd;
	int firstArgIndex;           /* the first argument _to pass to the server_ */
	
	/* start with environment variable.  default to localhost if not defined. */
	nailgun_server = getenv("NAILGUN_SERVER");
	if (nailgun_server == NULL) {
		nailgun_server = "127.0.0.1";
	}
	
	/* start with environment variable.  default to normal nailgun port if not defined */
	nailgun_port = getenv("NAILGUN_PORT");
	if (nailgun_port == NULL) {
		nailgun_port = NAILGUN_PORT_DEFAULT;
	}
	
	/* look at the command used to launch this program.  if it was "ng", then the actual
	   command to issue to the server must be specified as another argument.  if it
	   wasn't ng, assume that the desired command name was symlinked to ng in the user's
	   filesystem, and use the symlink name (without path info) as the command for the server. */
	cmd = shortClientName(argv[0]);
	if (isNailgunClientName(cmd)) {
		cmd = NULL;
	}
	
	/* quite possibly the lamest commandline parsing ever. 
	   look for the two args we care about (--nailgun-server and
	   --nailgun-port) and NULL them and their parameters after
	   reading them if found.  later, when we send args to the
	   server, skip the null args. */
	for (i = 1; i < argc; ++i) {
		if (!strcmp("--nailgun-server", argv[i])) {
			if (i == argc - 1) usage();
			nailgun_server = argv[i + 1];
			argv[i] = argv[i + 1] = NULL;
			++i;
		} else if(!strcmp("--nailgun-port", argv[i])) {
			if (i == argc - 1) usage();
			nailgun_port = argv[i + 1];
			argv[i] = argv[i + 1]= NULL;
			++i;
		} else if (cmd == NULL) {
			cmd = argv[i];
			firstArgIndex = i + 1;
		}
	}

	/* jump through a series of connection hoops */	
	hostinfo = gethostbyname(nailgun_server);
	if (hostinfo == NULL) {
		fprintf(stderr, "Unknown host: %s\n", nailgun_server);
		exit(NAILGUN_CONNECT_FAILED);
	}
 
	port = atoi(nailgun_port);

	if ((nailgunsocket = socket(AF_INET, SOCK_STREAM, 0)) == -1) {
		perror("socket");
		exit(NAILGUN_SOCKET_FAILED);
	}

	server_addr.sin_family = AF_INET;    
	server_addr.sin_port = htons(port);
	server_addr.sin_addr = *(struct in_addr *) hostinfo->h_addr;
	
	memset(&(server_addr.sin_zero), '\0', 8);

	if (connect(nailgunsocket, (struct sockaddr *)&server_addr,
                                     sizeof(struct sockaddr)) == -1) {
		perror("connect");
		exit(NAILGUN_CONNECT_FAILED);
  	}
	
	
	/* ok, now we're connected.  first send all of the command line
	   arguments for the server, if any.  remember that we may have
	   marked some arguments NULL if we read them to specify the
	   nailgun server and/or port */
	for(i = firstArgIndex; i < argc; ++i) {
		if (argv[i] != NULL) sendText(CHUNKTYPE_ARG, argv[i]);
	}

	/* now send environment */	
	for(i = 0; env[i]; ++i) {
		sendText(CHUNKTYPE_ENV, env[i]);
	}
	
	/* now send the working directory */
	cwd = getcwd(NULL, 0);
	sendText(CHUNKTYPE_DIR, cwd);
	free(cwd);
	
	/* and finally send the command.  this marks the point at which
	   streams are linked between client and server. */
	sendText(CHUNKTYPE_CMD, cmd);

	/* stream forwarding loop */	
	while(1) {
		FD_ZERO(&readfds);

		/* don't select on stdin if we've already reached its end */
		if (!eof) {
			FD_SET(STDIN_FILENO, &readfds);
		}

		FD_SET(nailgunsocket, &readfds);
		if (select (nailgunsocket + 1, &readfds, NULL, NULL, NULL) == -1) {
			perror("select");
		}
	  
		if (FD_ISSET(nailgunsocket, &readfds)) {
			processnailgunstream();
		} else if (FD_ISSET(STDIN_FILENO, &readfds)) {
	  		if (!processStdin()) {
				FD_CLR(STDIN_FILENO, &readfds);
				eof = 1;
			}
	  	}

	}  

	/* normal termination is triggered by the server, and so occurs in processExit(), above */
}
