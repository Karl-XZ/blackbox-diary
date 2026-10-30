$ErrorActionPreference='Stop'
$sdk=Join-Path $env:LOCALAPPDATA 'Android/Sdk'
$cmake=Join-Path $sdk 'cmake/3.22.1/bin/cmake.exe'
$nativeSource=Join-Path $PSScriptRoot '../native/spark-sme2'
$nativeBuild=Join-Path $PSScriptRoot '../build/native-sme2'
$toolchain=Join-Path $sdk 'ndk/27.0.12077973/build/cmake/android.toolchain.cmake'
& $cmake -S $nativeSource -B $nativeBuild -G Ninja "-DCMAKE_MAKE_PROGRAM=$sdk/cmake/3.22.1/bin/ninja.exe" "-DCMAKE_TOOLCHAIN_FILE=$toolchain" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-34 -DCMAKE_BUILD_TYPE=Release -DANDROID_STL=c++_shared
if($LASTEXITCODE -ne 0){throw 'Native configuration failed'}
& $cmake --build $nativeBuild --parallel 2
if($LASTEXITCODE -ne 0){throw 'Native compilation failed'}
