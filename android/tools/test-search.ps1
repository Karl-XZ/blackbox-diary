param([string]$Serial='emulator-5580',[string]$OutputDirectory=$PSScriptRoot)
$ErrorActionPreference='Stop'
$adb=Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$package='com.blackbox.diary.benchmark'
$outputRoot=(Resolve-Path -LiteralPath $OutputDirectory).Path
foreach($operation in @('prepare','measure')) {
    $runKey=[guid]::NewGuid().ToString()
    & $adb -s $Serial shell am force-stop $package | Out-Null
    & $adb -s $Serial shell am start -n "$package/com.blackbox.diary.BenchmarkActivity" --es operation $operation --es run $runKey | Out-Null
    $started=Get-Date
    do {
        Start-Sleep -Seconds 3
        & $adb -s $Serial pull "/sdcard/Android/data/$package/files/benchmark-run.json" (Join-Path $outputRoot 'benchmark-run.json') 2>&1 | Out-Null
        if(!(Test-Path (Join-Path $outputRoot 'benchmark-run.json'))){continue}
        $report=Get-Content (Join-Path $outputRoot 'benchmark-run.json') -Raw | ConvertFrom-Json
        if($report.run -ne $runKey){continue}
        if($report.stage -eq 'FAILED'){throw $report.error}
        Write-Output "$operation $($report.stage) $($report.rows) rows $($report.queries) queries"
        if(((Get-Date)-$started).TotalMinutes -gt 30){throw 'BENCHMARK_TIMEOUT'}
    } while($report.stage -ne 'SUCCEEDED' -or $report.run -ne $runKey)
}
& $adb -s $Serial pull "/sdcard/Android/data/$package/files/search-benchmark.json" (Join-Path $outputRoot 'search-benchmark.json')
