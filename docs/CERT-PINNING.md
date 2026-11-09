# Commercial certificate pinning

商业配置与匿名统计使用独立的 `CommercialHttp` 客户端。客户端默认只使用 Android 系统信任链；
`commercial_config_pin_primary` 与 `commercial_config_pin_backup` 两个字符串资源都填入有效且不同的
`sha256/<SPKI base64>` 值后，才会对 `commercial_config_url` 的主机启用 `CertificatePinner`。

当前源码故意不填真实 pin。没有人工确认当前证书和备用证书策略前，不要把占位值写入发布分支，
也不要只填一个 pin。单 pin 会把证书轮换变成全量设备同时失联的事件。

## 获取 SPKI pin

在已确认目标域名和证书链的环境中执行，输出值只用于人工审核和资源文件配置：

```bash
openssl s_client -connect tv2.910501.xyz:443 -servername tv2.910501.xyz </dev/null 2>/dev/null \
  | openssl x509 -pubkey -noout \
  | openssl pkey -pubin -outform der \
  | openssl dgst -sha256 -binary \
  | openssl base64 -A
```

把结果加上 `sha256/` 前缀，分别写入：

```xml
<string name="commercial_config_pin_primary" translatable="false">sha256/当前证书公钥摘要</string>
<string name="commercial_config_pin_backup" translatable="false">sha256/备用证书公钥摘要</string>
```

不要把证书私钥、服务器 `.env` 或其他凭据写入源码。

## 轮换步骤

1. 在当前证书仍有效时，确认新旧两个 SPKI pin，并先发布同时包含“当前 + 新证书”的 APK。
2. 等待已分发设备完成覆盖升级，并用真实商业配置请求确认新包能正常连接。
3. 在服务端切换到新证书；旧 pin 仍保留在已升级包中，因此切换期间不会因 pin 失配中断。
4. 下一版把新证书设为 primary，再填入下一张经过人工确认的备用 pin；不要在没有备用 pin 时发布。
5. 每次轮换都重复 scan-secrets、未签名构建和 Android TV 真机覆盖安装验证。

如果当前证书已经失效或设备尚未完成第一步升级，不要强行删除旧 pin；先准备回滚证书或人工迁移方案。
