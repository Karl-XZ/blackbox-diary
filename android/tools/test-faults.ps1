param([string]$Serial='emulator-5580',[int]$Repeats=20,[string]$OutputDirectory=$PSScriptRoot)
$ErrorActionPreference='Stop'
$adb=Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$pkg='com.blackbox.diary.fault'
$runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
$results=@()
$outputRoot=(Resolve-Path -LiteralPath $OutputDirectory).Path
foreach($n in 1..12) {
  $point='FP{0:D2}' -f $n
  foreach($iteration in 1..$Repeats) {
    $crash=& $adb -s $Serial shell am instrument -w -e point $point -e class 'com.blackbox.diary.FaultRecoveryTest#exercise' $runner 2>&1 | Out-String
    $receipt=& $adb -s $Serial shell run-as $pkg cat no_backup/fault-hit.txt 2>&1 | Out-String
    if($receipt.Trim() -ne $point) {throw "Checkpoint not reached: $point $iteration`n$crash`n$receipt"}
    $recovered=& $adb -s $Serial shell am instrument -w -e class 'com.blackbox.diary.FaultRecoveryTest#recover' $runner 2>&1 | Out-String
    $passed=$recovered -match 'OK \(1 test\)'
    $results+=[pscustomobject]@{point=$point;iteration=$iteration;passed=$passed;recoveredAt=(Get-Date).ToUniversalTime().ToString('o')}
    $results | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $outputRoot 'fault-results.json')
    if(!$passed) {throw "Recovery failed: $point $iteration`n$recovered"}
    Write-Output "$point $iteration/$Repeats passed"
  }
}
