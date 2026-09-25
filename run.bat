@echo off
if not exist target\classes\com\chaintracker\ChainTrackerApp.class (
    call build.bat
)

java -cp target\classes com.chaintracker.ChainTrackerApp config.properties
