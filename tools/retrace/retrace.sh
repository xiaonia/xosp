#!/bin/sh

#delete output file
outputFile=./stacktrace.txt
rm -r -d -f ${outputFile}

# find mapping file
mappingFile=`find ./ -name '*mapping*'`
echo ${mappingFile}

# find logcat file
logFile=`find ./ \( -name '*logcat*' -o -name '*log*' -o -name '*crash*' \)`
echo ${logFile}

#java -jar ./retrace.jar -verbose ${mappingFile} ${logFile} > ${outputFile}

java -jar ./retrace.jar ${mappingFile} ${logFile} > ${outputFile}

#read -p 'Press any key to continue...'
