# 凭据处理说明（MapMate）

审计发现：AMap Android Key 与 release keystore 口令曾以明文写在 `AndroidManifest.xml` 与
`app/build.gradle.kts` 里，并随提交进入仓库历史。

## 现在的做法

| 凭据 | 位置 | 说明 |
|---|---|---|
| `AMAP_KEY` | `local.properties` 或同名环境变量 | 由 `manifestPlaceholders` 注入 Manifest 的 `${AMAP_KEY}` |
| `RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD` | 同上 | 缺任意一项则不注册 release 签名配置，构建时告警而不是静默使用旧口令 |

`local.properties` 与 `/keystore/` 都已在 `.gitignore` 中，且从未被 git 跟踪。

## 仍必须由你完成的两件事

1. **轮换 AMap Key**：旧 Key `dae386…a475` 已进入提交历史，等同于公开。请到高德控制台
   作废并重建，绑定包名 `com.zhaowen.mapmate` + 发布证书 SHA1。
2. **轮换 release 签名口令**：`mapmate2026` 也已公开。签名口令无法"改"，只能重建 keystore
   （会改变签名指纹，已发布的安装包将无法直接覆盖升级），或至少换一套新 keystore 用于后续版本。

> 注意：仅仅"从最新代码里删掉"并不能撤销泄露——历史提交里仍然存在。撤销的唯一方式是轮换。

## 明文流量

`android:usesCleartextTraffic` 已改为 `false`。若确有某个内网 HTTP 目标需要放行，请添加
`res/xml/network_security_config.xml` 只对那个域名开放，而不是整体打开：

```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">192.168.1.10</domain>
    </domain-config>
</network-security-config>
```

并在 `<application>` 上加 `android:networkSecurityConfig="@xml/network_security_config"`。

## 建议的防回归

- 仓库开启 GitHub secret scanning / push protection
- 本地 pre-commit 加 `gitleaks` 或 `detect-secrets`
