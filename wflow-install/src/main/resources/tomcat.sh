#!/bin/sh

export JAVA_OPTS="-Xmx768M -Dfile.encoding=UTF-8 -Dwflow.home=./wflow/ -javaagent:./wflow/aspectjweaver-1.9.22.jar"

apache-tomcat-9.0.97/bin/catalina.sh $*
