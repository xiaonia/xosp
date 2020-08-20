@ECHO OFF

setlocal EnableDelayedExpansion

@REM delete output file
set outputFile=stacktrace.txt
del /q /s %outputFile%

@REM find mapping file
set inputDir=./
for /r %%g in (%inputDir%*mapping*) do (
    set "mappingFile=%inputDir%%%~nxg"
)
echo !mappingFile!

@REM find logcat file
for /r %%g in (%inputDir%*logcat*) do (
    set "logFile=%inputDir%%%~nxg"
)
for /r %%g in (%inputDir%*log*) do (
    set "logFile=%inputDir%%%~nxg"
)
for /r %%g in (%inputDir%*crash*) do (
    set "logFile=%inputDir%%%~nxg"
)
echo !logFile!

REM java -jar ./retrace.jar -verbose !mappingFile! !logFile! > %outputFile%

java -jar ./retrace.jar !mappingFile! !logFile! > %outputFile%

REM pause
