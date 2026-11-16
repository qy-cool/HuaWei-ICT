# 企业 OIDC 登录

管理台支持企业身份服务的 Authorization Code + PKCE S256 登录。企业身份由固定发行方 `issuer` 和该发行方的 `sub` 共同确定，按字节精确匹配。平台沿用本地账号审核、项目成员和环境权限；变更企业用户的邮箱或显示名不影响关联。默认启用的本地密码登录可供管理员在身份服务停机时恢复访问。

## 部署

1. 在企业身份服务注册机密 Web 客户端，授权范围为 `openid`，启用 `client_secret_basic`、S256、RS256 和带 `kid` 的公钥 JWKS。回调精确登记为管理台根页面的 HTTPS URL。管理台和中控 API 采用同源反向代理。浏览器回调页面避免增加第三方脚本，并让代理的访问日志排除该页面的查询字符串。
2. 从受信任的 IdP 配置取得下列端点，写入中控环境。示例中的密钥由部署方的凭据系统注入。应用在每次换取身份时从指定的 JWKS 地址读取公钥，轮换期间企业 IdP 应保留旧签名验证公钥直至已签发令牌过期。

```dotenv
OIDC_ENABLED=true
OIDC_ISSUER=https://id.example.invalid/realms/company
OIDC_AUTHORIZATION_ENDPOINT=https://id.example.invalid/realms/company/protocol/openid-connect/auth
OIDC_TOKEN_ENDPOINT=https://id.example.invalid/realms/company/protocol/openid-connect/token
OIDC_JWKS_URI=https://id.example.invalid/realms/company/protocol/openid-connect/certs
OIDC_CLIENT_ID=cloide
OIDC_REDIRECT_URI=https://control.example.invalid/
```

3. 设置 `OIDC_CLIENT_SECRET` 并重启中控，读取 `GET /api/auth/oidc/status` 确认 `enabled`。管理员使用现有账号登录，把已审核的本地用户与其企业身份绑定。请求使用管理员 Bearer，参数 `userId` 来自平台，`subject` 来自企业 IdP。一个企业身份只能绑定一个本地账号，修改映射时数据库检查唯一约束。管理员可调用 `DELETE /api/admin/oidc/mappings/{userId}` 撤销企业登录。

```http
PUT /api/admin/oidc/mappings/{userId}
Authorization: Bearer <administrator-token>
Content-Type: application/json

{"subject":"<subject-from-the-enterprise-identity-provider>"}
```

4. 用户点击“使用企业账号登录”。平台把 PKCE verifier 放入五分钟的 Secure、HttpOnly、SameSite=Lax、host-only Cookie；数据库记录 state、verifier 和 nonce 的摘要。验签前已消费浏览器绑定的 state。ID Token 必须具有正确的 issuer、audience、nonce、时间声明和可验证的签名。当 token 带有多个 audience 时必须有匹配的 authorized party。浏览器取得平台 JWT 后按原有 API 鉴权。

## 撤销与恢复

删除映射阻止今后的 OIDC 登录。对已签发的平台会话直接复用本地账号停用/审核状态和现有登出吊销策略。值班人员应独立保管本地管理员账号的密码，在身份服务异常时通过同一管理台恢复企业端点或账户映射。发行方、密钥、回调、目录用户或环境权限变化后重新执行一次登录与撤销验收。

本地验收程序使用临时 MySQL、真实 Java 中控和会签发 RSA 令牌的回环 IdP。在[编码端到端验收](../logs/20261112-编码任务端到端验收.md)的命令前设置 `CLOIDE_TEST_OIDC=1`。协议字段见[项目 OIDC 契约](../openapi/openapi-oidc.json)。认证步骤依据 [OpenID Connect Core](https://openid.net/specs/openid-connect-core-1_0.html) 与 [RFC 7636](https://www.rfc-editor.org/rfc/rfc7636)。
