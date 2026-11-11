# AI 翻译

一个界面简洁的原生 Android AI 翻译 App。只支持 **OpenAI 兼容接口**（Chat Completions），可添加多个接口配置并随时切换。

图标为汉字「译」+ 字母「A」的渐变设计。

## 功能

- **单页翻译**：输入文字后停顿约 1 秒自动翻译，也可点击「立即翻译」
- **语言选择**：底部左侧源语言（支持自动检测）、右侧目标语言，中间一键交换
- **历史记录**：右上角入口，自动保存最近 200 条；点击记录可回填原文、译文和语言
- **API 配置**：左侧三横菜单 → API 设置，可添加多个 OpenAI 兼容接口（名称 / Base URL / API Key / 模型），点击卡片切换使用，长按编辑或删除
- **复制与分享**：译文可一键复制或调用系统分享
- **深色模式**：跟随系统自动切换
- **隐私**：API Key 与历史记录仅保存在本机应用私有目录，不会上传到第三方

## 接口要求

填写 **Base URL**，例如：

| 服务 | Base URL | 模型示例 |
|---|---|---|
| OpenAI 官方 | `https://api.openai.com/v1` | `gpt-4o-mini` |
| 任意中转站 | `https://example.com/v1` | 以服务商为准 |
| 本地服务 | `http://192.168.1.10:1234/v1` | 以服务商为准 |

App 会自动补全请求地址：

- `https://api.openai.com/v1` → `https://api.openai.com/v1/chat/completions`
- `https://api.openai.com` → `https://api.openai.com/v1/chat/completions`
- 已以 `/chat/completions` 结尾则原样使用

请求体为标准 OpenAI Chat Completions：

```json
{
  "model": "gpt-4o-mini",
  "stream": false,
  "messages": [
    {"role": "system", "content": "你是专业翻译引擎……"},
    {"role": "user", "content": "需要翻译的文字"}
  ]
}
```

API Key 可留空（适配本地无需鉴权的服务）。

## 构建

### GitHub Actions（推荐）

- 推送到 `main` 自动触发，或在 Actions 页面手动运行 **Build APK**
- 构建完成后在对应运行的 **Artifacts** 中下载 `AI-Translate-APK`

工作流：`.github/workflows/build.yml`，使用 JDK 17、Android SDK 34、Gradle 8.7。

### 本地构建

需要 JDK 17 与 Android SDK 34：

```bash
gradle assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

> Debug APK 使用 Android 默认调试签名，可直接安装；正式分发请自行配置 release 签名。

## 安装

1. 下载 `app-debug.apk`
2. 手机允许「安装未知来源应用」
3. 安装后打开 App → 左上角菜单 → API 设置 → 右下角 + 添加接口
4. 填入 Base URL、API Key、模型名，保存后即可翻译

## 项目结构

```
app/src/main/java/com/tyy/aitranslate/
├── MainActivity.kt        # 翻译主界面、抽屉、自动翻译
├── SettingsActivity.kt    # API 配置管理
├── HistoryActivity.kt     # 翻译历史
├── Prefs.kt               # 本机存储（接口配置、历史、语言）
├── TranslateClient.kt     # OpenAI 兼容 HTTP 请求
├── Languages.kt           # 语言列表
├── Models.kt              # 数据模型
├── ProfileAdapter.kt      # 接口列表适配器
└── HistoryAdapter.kt      # 历史列表适配器
```

## 说明

- 自动翻译基于「输入停顿 900ms」触发，长文本会消耗较多 token
- 历史最多保留 200 条，超出后自动删除最旧记录
- 开启 `usesCleartextTraffic` 以支持局域网 HTTP 接口；如只使用 HTTPS，可在 `AndroidManifest.xml` 中关闭
