# GitHub Actions 自动发布配置指南

## 概述

本项目配置了 GitHub Actions 自动构建和发布流程：
- 推送 tag（如 `v1.133`）时自动构建 release 版本
- 使用 keystore 签名 APK
- 自动创建 GitHub Release 并上传 APK

## 设置步骤

### 1. 生成 Keystore

在本地运行生成脚本：

```bash
chmod +x generate-keystore.sh
./generate-keystore.sh
```

或者手动生成：

```bash
keytool -genkey -v \
  -keystore release.keystore \
  -alias byd-dlink \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

**重要信息**（请妥善保存）：
- Keystore 文件：`release.keystore`
- Key Alias：`byd-dlink`
- Keystore Password：你设置的密码
- Key Password：通常与 Keystore Password 相同

### 2. 配置 GitHub Secrets

在 GitHub 仓库中设置以下 Secrets：

1. 进入仓库 → Settings → Secrets and variables → Actions
2. 点击 "New repository secret"
3. 添加以下 4 个 secrets：

#### KEYSTORE_BASE64
将 keystore 文件转换为 base64：
```bash
base64 -w 0 release.keystore
```
将输出的整个字符串作为 secret 值。

#### KEYSTORE_PASSWORD
Keystore 的密码。

#### KEY_ALIAS
Key 的别名（默认：`byd-dlink`）。

#### KEY_PASSWORD
Key 的密码（通常与 KEYSTORE_PASSWORD 相同）。

### 3. 保护 Keystore 文件

**绝对不要**将 `release.keystore` 提交到 Git 仓库！

已在 `.gitignore` 中排除：
```
*.keystore
```

### 4. 触发发布

推送 tag 即可触发自动发布：

```bash
# 递增版本号（在 app/build.gradle 中）
git add app/build.gradle
git commit -m "Bump version to 1.134"

# 创建并推送 tag
git tag v1.134
git push origin master
git push origin v1.134
```

GitHub Actions 会自动：
1. 构建 release APK
2. 使用 keystore 签名
3. 创建 GitHub Release
4. 上传签名的 APK 到 Release

## 手动触发

也可以手动触发 workflow：
1. 进入仓库 → Actions → Build and Release
2. 点击 "Run workflow"
3. 选择分支并运行

注意：手动触发不会创建 Release，只会上传 artifact。

## 版本号管理

每次发布前需要手动更新 `app/build.gradle`：

```gradle
versionCode 135        // 递增整数
versionName "1.134"    // 版本号字符串
```

## 故障排查

### 构建失败：Keystore 解码错误
- 检查 `KEYSTORE_BASE64` secret 是否完整（无换行）
- 在本地测试：`echo "$KEYSTORE_BASE64" | base64 --decode > test.keystore`

### 签名失败：密码错误
- 验证 `KEYSTORE_PASSWORD` 和 `KEY_PASSWORD` 是否正确
- 在本地测试：`keytool -list -v -keystore release.keystore`

### Release 未创建
- 确认推送的是 tag（如 `v1.134`），不是普通 commit
- 检查 tag 格式：`git tag -l`

## 安全建议

1. **备份 Keystore**：将 `release.keystore` 保存到安全位置（如密码管理器）
2. **不要丢失**：丢失 keystore 意味着无法更新已发布的应用
3. **限制访问**：只有仓库管理员应该能看到 GitHub Secrets
4. **定期轮换**：建议每 1-2 年重新生成 keystore（需要新包名）
