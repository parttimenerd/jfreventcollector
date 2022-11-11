#!/bin/sh

cd "$(dirname $0)" || exit

mvn package assembly:single