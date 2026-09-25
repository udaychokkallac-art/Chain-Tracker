if (!(Test-Path "target/classes/com/chaintracker/ChainTrackerApp.class")) {
    & .\build.ps1
}

java -cp target/classes com.chaintracker.ChainTrackerApp config.properties
