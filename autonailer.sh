#!/bin/bash
NAILGUN_HOME=$(dirname "$0")
SOCKET=$HOME/.ng-socket

if [[ ! -S "$SOCKET" ]]; then
    java -Dorg.newsclub.net.unix.library.path="$NAILGUN_HOME/junixsocket-1.1/lib-native" \
         -jar "$NAILGUN_HOME"/nailgun-?.?.?.jar "$SOCKET" &
    for i in `seq 1 10`; do
        [[ -S "$SOCKET" ]] && break
        sleep 1;
    done
fi 

exec "$NAILGUN_HOME/ng" --nailgun-unix-socket "$SOCKET" "$@"
