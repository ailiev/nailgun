# incredibly simple makefile for the ng client ONLY
# this has only been tested on linux.

CC=gcc
CCFLAGS=-Wall -pedantic

ng: src/c/ng.c
	${CC} ${CFLAGS} -o ng src/c/ng.c

clean:
	rm ng
