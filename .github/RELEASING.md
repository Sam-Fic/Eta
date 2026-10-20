# Eta 发布流程

## 配置签名 Secrets

发布证书和密码不得提交到 Git。首次使用前，在仓库的
`Settings > Secrets and variables > Actions` 中添加：

- `ETA_RELEASE_KEYSTORE_BASE64`：发布证书的 Base64 文本
- `ETA_RELEASE_STORE_PASSWORD`：KeyStore 密码
- `ETA_RELEASE_KEY_ALIAS`：Key alias
- `ETA_RELEASE_KEY_PASSWORD`：Key 密码

macOS 可以用下面的命令复制证书的 Base64 文本：

```bash
base64 < /path/to/eta-release.p12 | tr -d '\n' | pbcopy
```

也可以使用 GitHub CLI。密码类 Secret 不要直接写在命令参数中，运行命令后按提示输入：

```bash
base64 < /path/to/eta-release.p12 | gh secret set ETA_RELEASE_KEYSTORE_BASE64
gh secret set ETA_RELEASE_STORE_PASSWORD
gh secret set ETA_RELEASE_KEY_ALIAS
gh secret set ETA_RELEASE_KEY_PASSWORD
```

## 构建与发布

发布证书不要提交到仓库。本地把 `keystore.properties` 放到仓库根目录，或把同一份 PKCS12 配进 Actions Secrets。

`Eta Build` 工作流（`.github/workflows/android-release.yml`）在以下情况运行：

- 推送 `v*` 标签（发版）
- 在 GitHub 的 `Actions > Eta Build` 中手动运行（日常试构建）

工作流分三个 job：

1. `Lint 与单元测试`：跑 `:app:lintDebug` 与 `:app:testDebugUnitTest`，**不通过则整条发版链路中止**。
2. `构建 Debug 与 Release APK`：构建并用 `apksigner` 校验签名后的 Release APK，作为 Artifact 保存 14 天。
3. `创建 Release 草稿`：仅在标签推送时运行，下载 Release APK 并创建 **草稿** Release（附 `app-release.apk`，标题为标签名，说明自动生成）。

草稿不会出现在 `releases/latest`，App 内的更新检查要等草稿正式发布后才可见。

## 发版步骤

正式发布前先更新 `versionCode` 和 `versionName`，再创建与 `versionName` 一致的标签。
工作流会校验标签与构建产物的 `versionName` 是否一致，不一致直接失败。

`versionCode` 规则是 `yyyyMMdd + 当日两位序号`（见 `app/build.gradle.kts`），发版时随 `versionName` 一起手动递增。
例如发布 `5.3.12`：

```bash
# 1. 改 app/build.gradle.kts 的 versionCode / versionName，提交并推送
git commit -am "build: 发版 5.3.12（versionCode 2026102101）"
git push origin main

# 2. 打与 versionName 对应的标签
git tag -a v5.3.12 -m "Eta 5.3.12"
git push origin v5.3.12
```

标签推送后等待 `Eta Build` 工作流跑完，然后：

1. 打开草稿 Release，把自动生成的说明替换成正式 Release Notes。
2. 确认附件是 `app-release.apk`，标题与标签一致。
3. 由维护者手动发布（`gh release edit v5.3.12 --draft=false`）。

发布后可用 `gh release view v5.3.12` 复核标签、说明与附件。
