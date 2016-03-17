#!/bin/bash

set -e

function log () {
    echo "$1" >&2
}

function install_artifact () {
    log "Installing artifact $2"
    mvn install:install-file -DgroupId="$1" -DartifactId="$2" -Dversion="$3" -Dfile="$4" \
        -Dpackaging=jar -DgeneratePom=true
}

R="${HOME}/.m2/repository"
V="1.47.1"
U="http://storage.googleapis.com/gdata-java-client-binaries/gdata-src.java-${V}.zip"

if test -r "${R}/com/google/gdata/gdata-core/1.0/gdata-core-1.0.jar" \
        -a -r "${R}/com/google/gdata/gdata-spreadsheet/3.0/gdata-spreadsheet-3.0.jar";
then
    log "Artifacts up-to-date"
    exit 0
fi

log "Downloading $U"
cd $(mktemp -d)
wget "${U}"
unzip "gdata-src.java-${V}.zip"

install_artifact com.google.gdata gdata-core 1.0 gdata/java/lib/gdata-core-1.0.jar

install_artifact com.google.gdata gdata-spreadsheet 3.0 gdata/java/lib/gdata-spreadsheet-3.0.jar
