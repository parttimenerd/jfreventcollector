#!/bin/sh

cd "$(dirname $0)" || exit

java -jar target/jfreventcollector-1.0-full.jar $1