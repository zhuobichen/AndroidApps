@echo off
cd /d "%~dp0"
set "VIDEOQUIZ_WORKSPACE_ID=00811c90-6b22-4294-b725-ecd67f07d702"
set "VIDEOQUIZ_BIND_HOST=0.0.0.0"
set "VIDEOQUIZ_SEGMENT_SESSION_ID=session-93174534-2a03-40e2-a1c8-2147e36cba42"
start "" /b "D:\Node_js\node.exe" server.mjs >> bridge.out.log 2>> bridge.err.log
