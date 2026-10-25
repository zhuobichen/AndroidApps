# MapMate 设备端验收脚本
# 前置：模拟器已由用户启动（adb devices 能看到 device），服务端已在 8000 端口运行
$ErrorActionPreference = "Continue"
$adb = "D:\Android\Sdk\platform-tools\adb.exe"
$env:TMP = "D:\Android\MapMate\emuhome\tmp"; $env:TEMP = $env:TMP
$apk = "D:\Android\MapMate\app\build\outputs\apk\debug\app-debug.apk"
$out = "D:\Android\MapMate\acceptance"
New-Item -ItemType Directory -Force -Path $out | Out-Null

function Step($n) { "`n=== $n ===" }

Step "0. 设备"
& $adb devices
for ($i = 0; $i -lt 60; $i++) {
  $b = (& $adb shell getprop sys.boot_completed 2>$null) -join ""
  if ($b.Trim() -eq "1") { "boot_completed=1"; break }
  Start-Sleep 3
}

Step "1. 安装"
& $adb install -r $apk

Step "2. 权限"
& $adb shell pm grant com.zhaowen.mapmate android.permission.ACCESS_FINE_LOCATION
& $adb shell pm grant com.zhaowen.mapmate android.permission.RECORD_AUDIO

Step "3. 起点定位"
& $adb emu geo fix 113.2644 23.1291
Start-Sleep 3
& $adb emu geo fix 113.2644 23.1291
Start-Sleep 8

Step "4. 启动并发起导航"
& $adb logcat -c
& $adb shell am force-stop com.zhaowen.mapmate
& $adb shell am start -n com.zhaowen.mapmate/.MainActivity --es cmd "导航到广州塔"
Start-Sleep 28
& $adb shell screencap -p /sdcard/acc1.png; & $adb pull /sdcard/acc1.png "$out\01-route.png"

Step "5. 沿路线行驶（注入 6 个点）"
$pts = @(
  @(113.2655, 23.1290), @(113.2718, 23.1309), @(113.2842, 23.1311),
  @(113.3106, 23.1316), @(113.3151, 23.1115), @(113.3210, 23.1080)
)
foreach ($p in $pts) { & $adb emu geo fix $p[0] $p[1]; Start-Sleep -Milliseconds 2500 }
& $adb shell screencap -p /sdcard/acc2.png; & $adb pull /sdcard/acc2.png "$out\02-driving.png"

Step "6. 到达终点（用 App 实际解析到的坐标）"
$destLine = (& $adb logcat -d | Select-String -Pattern "MapMate.*dest " | Select-Object -Last 1)
if ($destLine -match "dest ([0-9.]+),([0-9.]+)") {
  $dlat = $matches[1]; $dlng = $matches[2]
  "app dest = $dlat,$dlng"
  & $adb emu geo fix $dlng $dlat
} else {
  "dest not found in log, fallback"
  & $adb emu geo fix 113.324521 23.106428
}
Start-Sleep 10
& $adb shell screencap -p /sdcard/acc3.png; & $adb pull /sdcard/acc3.png "$out\03-arrived.png"

Step "7. 结束导航"
& $adb shell am start -n com.zhaowen.mapmate/.MainActivity --es cmd "结束导航"
Start-Sleep 8
& $adb shell screencap -p /sdcard/acc4.png; & $adb pull /sdcard/acc4.png "$out\04-stopped.png"

Step "8. 日志摘录"
& $adb logcat -d | Select-String -Pattern "MapMateWS|MapMateRoute|MapMateGuide" | Select-Object -Last 500 | ForEach-Object { $_.Line } | Tee-Object "$out\log.txt"

Step "9. 判定"
$log = Get-Content "$out\log.txt" -Raw
$checks = [ordered]@{
  "IoT 指令收到"   = $log -match "iot command: navigate"
  "路线规划成功"   = $log -match "route ok:"
  "指引启动"       = $log -match "guidance started"
  "定位回调"       = $log -match "fix "
  "语音播报"       = $log -match "speak:"
  "到达判定"       = $log -match "到达目的地附近"
}
$checks.GetEnumerator() | ForEach-Object { "{0,-14} {1}" -f $_.Key, $(if ($_.Value) { "PASS" } else { "FAIL" }) }