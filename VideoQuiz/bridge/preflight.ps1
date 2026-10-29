# 卷灵电脑端前置条件自检
# 用法：双击 preflight.cmd，或在本目录执行 pwsh -File preflight.ps1
# 只读检查，不改任何配置。

$ErrorActionPreference = 'Continue'

$ok = 0
$bad = 0
$warn = 0

function Pass($msg, $detail) {
    Write-Host "  [通过] $msg" -ForegroundColor Green
    if ($detail) { Write-Host "         $detail" -ForegroundColor DarkGray }
    $script:ok++
}

function Fail($msg, $fix) {
    Write-Host "  [缺失] $msg" -ForegroundColor Red
    if ($fix) { Write-Host "         -> $fix" -ForegroundColor Yellow }
    $script:bad++
}

function Warn($msg, $detail) {
    Write-Host "  [注意] $msg" -ForegroundColor Yellow
    if ($detail) { Write-Host "         $detail" -ForegroundColor DarkGray }
    $script:warn++
}

Write-Host ""
Write-Host "卷灵 · 电脑端前置条件自检" -ForegroundColor Cyan
Write-Host ("=" * 62)
Write-Host ""

# ── 1. Node.js ─────────────────────────────────────────────
Write-Host "1) Node.js 运行时"
$node = Get-Command node -ErrorAction SilentlyContinue
if ($node) {
    $ver = (& node --version) 2>$null
    $major = 0
    if ($ver -match 'v(\d+)') { $major = [int]$Matches[1] }
    if ($major -ge 20) {
        Pass "Node.js $ver" $node.Source
    } else {
        Fail "Node.js 版本过低（$ver）" "桥接需要 Node.js 20 或更高；去 nodejs.org 装 LTS"
    }
} else {
    Fail "没有找到 node 命令" "安装 Node.js LTS：https://nodejs.org/"
}

# ── 2. DSH 是否在运行 ──────────────────────────────────────
Write-Host ""
Write-Host "2) DSH（DeepSeek Harness）"
$dsh = Get-NetTCPConnection -LocalPort 3080 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($dsh) {
    Pass "DSH 网页服务在 3080 端口监听"
} else {
    Fail "3080 端口没有监听" "先启动 DSH（dsh web），浏览器能打开 http://127.0.0.1:3080 才继续"
}

# ── 3. 会话管理插件 ────────────────────────────────────────
Write-Host ""
Write-Host "3) 会话管理插件（@zhuobichen/dsh-agent-supervisor）"
if ($dsh) {
    $probe = $null
    try {
        $body = @{ op = 'list' } | ConvertTo-Json -Compress
        $probe = Invoke-RestMethod 'http://127.0.0.1:3080/dsh-agent-supervisor/command' `
            -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 15
    } catch {
        $probe = $null
    }

    if ($probe) {
        Pass "插件已加载，能列出 $($probe.count) 个会话" "路由 /dsh-agent-supervisor/command 正常"
    } else {
        Fail "插件没有响应" @"
这不是官方内置功能，必须自己装：
  1. 拿到 @zhuobichen/dsh-agent-supervisor 这个包
  2. 放到 DSH 的 profile（dsh/home/profiles/web/node_modules/@zhuobichen/）
  3. 在 cordis.yml 里注册该插件
  4. 重启 DSH
没有它，手机端的「选会话 / 建会话 / 派发 / 追问」全部不可用。
"@
    }
} else {
    Warn "跳过（DSH 没在跑）" "先启动 DSH 再重跑本检查"
}

# ── 4. 桥接服务 ────────────────────────────────────────────
Write-Host ""
Write-Host "4) 桥接服务（本仓库的 bridge/server.mjs）"
$bridge = Get-NetTCPConnection -LocalPort 8765 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
$tokenFile = Join-Path $PSScriptRoot 'data\token.txt'

if ($bridge) {
    if (Test-Path $tokenFile) {
        $token = (Get-Content $tokenFile -Raw -Encoding UTF8).Trim()
        try {
            $health = Invoke-RestMethod 'http://127.0.0.1:8765/api/health' `
                -Headers @{ Authorization = "Bearer $token" } -TimeoutSec 10
            Pass "桥接在 8765 监听，令牌有效"
        } catch {
            Warn "桥接在跑，但带令牌请求失败" "手机端可能也会连不上；检查 data/token.txt"
        }
    } else {
        Warn "桥接在跑，但没有找到 data/token.txt" "首次启动桥接会自动生成"
    }
} else {
    Fail "8765 端口没有监听" "双击本目录的 start.cmd 启动桥接"
}

# ── 5. 网络地址 ────────────────────────────────────────────
Write-Host ""
Write-Host "5) 手机连接用的地址"
$ipv4 = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.InterfaceAlias -match 'WLAN|Wi-Fi|以太网' -and $_.IPAddress -notlike '169.254.*' } |
    Select-Object -First 1
# IPv6 里只有 SuffixOrigin=Link 的才是基于网卡固定的，随机地址重启就变。
$ipv6All = Get-NetIPAddress -AddressFamily IPv6 -ErrorAction SilentlyContinue |
    Where-Object { $_.InterfaceAlias -match 'WLAN|Wi-Fi|以太网' -and $_.PrefixOrigin -eq 'RouterAdvertisement' }
$ipv6 = $ipv6All | Where-Object { $_.SuffixOrigin -eq 'Link' } | Select-Object -First 1
if (-not $ipv6) { $ipv6 = $ipv6All | Select-Object -First 1 }

if ($ipv4) {
    Pass "IPv4：http://$($ipv4.IPAddress):8765"
} else {
    Warn "没有找到局域网 IPv4 地址" "手机和电脑可能不在同一网络"
}
if ($ipv6) {
    $stable = if ($ipv6.SuffixOrigin -eq 'Link') { '固定地址（重启不变）' } else { '临时地址（可能变）' }
    Pass "IPv6：http://[$($ipv6.IPAddress)]:8765" "校园网/公共 WiFi 常只通 IPv6；$stable"
    $others = $ipv6All | Where-Object { $_.IPAddress -ne $ipv6.IPAddress }
    if ($others) {
        Write-Host "         其它 IPv6（临时的，一般不用）：" -ForegroundColor DarkGray
        foreach ($o in $others) { Write-Host "           $($o.IPAddress)" -ForegroundColor DarkGray }
    }
}

# ── 6. 工作区 ──────────────────────────────────────────────
Write-Host ""
Write-Host "6) 目标工作区"
if ($bridge) {
    if (Test-Path $tokenFile) {
        $token = (Get-Content $tokenFile -Raw -Encoding UTF8).Trim()
        try {
            $sessions = Invoke-RestMethod 'http://127.0.0.1:8765/api/sessions' `
                -Headers @{ Authorization = "Bearer $token" } -TimeoutSec 15
            if ($sessions.workspace) {
                Pass "已锁定工作区 $($sessions.workspace)" "该工作区内有 $($sessions.total) 个会话"
            } else {
                Warn "桥接没有锁定工作区" "它会按锚点会话反推，容易投错地方；在 start.cmd 里设 VIDEOQUIZ_WORKSPACE_ID"
            }
        } catch {
            Warn "查询会话失败：$($_.Exception.Message)"
        }
    }
} else {
    Warn "跳过（桥接没在跑）"
}

# ── 7. 防火墙 ──────────────────────────────────────────────
Write-Host ""
Write-Host "7) Windows 防火墙"
$portRules = Get-NetFirewallPortFilter -ErrorAction SilentlyContinue |
    Where-Object { $_.LocalPort -eq '8765' } |
    ForEach-Object { Get-NetFirewallRule -AssociatedNetFirewallPortFilter $_ -ErrorAction SilentlyContinue } |
    Where-Object { $_ -and $_.Enabled -eq 'True' -and $_.Direction -eq 'Inbound' }

if ($portRules) {
    $names = ($portRules | Select-Object -ExpandProperty DisplayName -Unique) -join '、'
    Pass "入站 8765 已放行" $names
} else {
    Warn "没有找到放行入站 8765 的规则" "手机连不上时，右键以管理员运行 allow-firewall.cmd"
}

# ── 汇总 ───────────────────────────────────────────────────
Write-Host ""
Write-Host ("=" * 62)
Write-Host "结果：通过 $ok 项，缺失 $bad 项，注意 $warn 项" -ForegroundColor Cyan

if ($bad -gt 0) {
    Write-Host ""
    Write-Host "有缺失项，按上面的提示补齐后重跑。" -ForegroundColor Yellow
} else {
    Write-Host ""
    Write-Host "电脑端看起来齐了。打开手机 App，把上面的地址和令牌填进设置。" -ForegroundColor Green
}
Write-Host ""
