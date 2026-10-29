@echo off
rem 卷灵桥接启动器：带上工作区锁定与双栈监听。
rem 由计划任务 VideoQuizBridge 在登录时调用，也可以手动双击。
setlocal

cd /d "%~dp0"

rem 只在这个工作区里投递（App 内的「视频成卷」）
set "VIDEOQUIZ_WORKSPACE_ID=00811c90-6b22-4294-b725-ecd67f07d702"
rem 手机走 IPv6，必须监听全部地址
set "VIDEOQUIZ_BIND_HOST=0.0.0.0"

node "%~dp0server.mjs"
