# VideoQuiz LAN Bridge

This service accepts one complete video in one authenticated HTTP request, stores it locally, and exposes task/result endpoints for the VideoQuiz Android app. It does not split or reassemble the upload and does not expose the DSH web or backend ports.

## Start

Run `start.cmd` on the computer. Node.js 20+ is required. On first start it prints the LAN URL and creates `data/token.txt`; enter both in VideoQuiz > Interface Settings and enable the Harness computer mode.

The server binds only to the active `WLAN` IPv4 address when available. Override with `VIDEOQUIZ_BIND_HOST` only when needed. Default port is 8765 and maximum upload size is 2 GiB (`VIDEOQUIZ_MAX_BYTES` can override it).

Keep this terminal open while using the app. The current Wi-Fi network is marked Public on this computer. Windows Firewall may block inbound TCP 8765; allow this Node process/port only on the current private/trusted LAN, or switch the Wi-Fi profile to Private before creating a narrow firewall rule. Do not open ports 3080 or 9877.

## Harness handoff

After a complete upload is stored, the bridge automatically posts a task to the loopback-only `http://127.0.0.1:3080/dsh-agent-supervisor/chat` route. The management session then uses its default-restricted `dsh_session_send` tool to queue the task into the configured exact target session. The task includes the local video path, result directory, and a path to the pairing token file; the token value is not copied into DSH chat history.

Configure `VIDEOQUIZ_TARGET_SESSION_ID` and, if needed, `VIDEOQUIZ_SUPERVISOR_URL` before starting the bridge. Defaults target the VideoQuiz work session and the local DSH management route. The bridge does not expose ports 3080 or 9877 to the phone or LAN.

The receiving session reads the video from `videoPath`, creates result files under `resultsPath`, and posts `POST /api/jobs/complete` with JSON:

```json
{"id":"<job id>","text":"Result shown in the app","message":"Harness processing complete","files":[{"name":"answers.pdf","mimeType":"application/pdf"},{"name":"answers.txt","mimeType":"text/plain"}]}
```

All phone-facing endpoints except `/api/health` require `Authorization: Bearer <pairing token>`. The app polls the job and downloads result files after completion. `GET /api/jobs` is available for local troubleshooting; failed supervisor notification can be retried with authenticated `POST /api/jobs/<id>/forward`.
