# AI 翻译

一个界面简洁的原生 Android AI 翻译 App。只支持 **OpenAI 兼容接口**（Chat Completions），可添加多个接口配置并随时切换。

图标为汉字「译」+ 字母「A」的渐变设计。

## 功能

- **全屏单卡片翻译**：没有顶部标题，只保留左上角菜单和右上角历史；原文和译文在同一个框内，开始翻译后才出现中间横线
- **单页滚动**：输入和译文属于同一个滚动页面，查看长译文时原文会随页面一起向上滚动；左下角目标语言、相机和翻译按钮固定在底部不滚动；显示译文时自动定位到分隔线在 viewport 1/3 处，译文占屏幕下方 2/3，长译文可继续向下手动滚动；键盘弹出/收起时区域随 viewport 自然缩放并在光标/译文之间自动定位；点击编辑输入时焦点定位到键盘上方可视区中间
- **智能翻译**：纯中文输入自动翻译成英文；混合语言（含中文夹杂其他语言、纯外文）自动翻译成中文；源语言无需选择；同一文本重复翻译结果保持一致（temperature 0.1 + 确定性提示词）
- **手动目标语言**：左下角显示「智能」或具体语种，点击弹出语言列表；选择后立即重新翻译；可在「API 设置 → 自定义目标语言」里添加内置列表之外的语言（如粤语、菲律宾语），删除后自动退回「智能」
- **可编辑提示词**：设置页可分别查看和编辑「文本翻译提示词」「图片翻译提示词」，初始化显示内置默认内容，保存后完全以用户内容为准（内置提示词不再发送给模型）；用 `{{TARGET}}` 表示目标语言，支持一键恢复默认
- **刷新按钮**：右下角双箭头按钮，点击重新翻译当前内容
- **拍照翻译**：点击相机按钮拍摄图片，图片会压缩后按 OpenAI Vision 格式发送给模型，译文显示在同一个框内
- **字数不设上限**：输入框不再限制 2000 字
- **历史记录**：右上角入口，自动保存最近 200 条；点击文字记录可回填原文和译文，图片记录保留译文
- **API 配置**：左侧三横菜单 → API 设置，可添加多个 OpenAI 兼容接口（名称 / Base URL / API Key / 模型），点击卡片切换使用，长按编辑或删除
- **复制、分享与全屏**：译文可一键复制、调用系统分享，或进入只有返回/复制/分享的全屏页面查看
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

请求体为标准 OpenAI Chat Completions。文字翻译示例：

```json
{
  "model": "gpt-4o-mini",
  "stream": false,
  "messages": [
    {
      "role": "system",
      "content": "You are a professional translation engine. The input may be in any language, including English. Translate it into the target language given in the user message. Never return the source unchanged unless it is already in the target language. Output only the translation."
    },
    {
      "role": "user",
      "content": "Translate the text below into English. Output only the translation.\n\n<text>\n你好，世界\n</text>"
    }
  ]
}
```

拍照翻译使用 OpenAI Vision 的 `image_url` 格式（需要模型支持图片输入）：

```json
{
  "role": "user",
  "content": [
    {"type": "text", "text": "Read all text in this image and translate it."},
    {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,..."}}
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
├── MainActivity.kt        # 翻译主界面、抽屉、自动翻译、全屏入口
├── FullscreenActivity.kt  # 译文全屏查看（返回 / 复制 / 分享）
├── SettingsActivity.kt    # API 配置管理
├── HistoryActivity.kt     # 翻译历史
├── Prefs.kt               # 本机存储（接口配置、历史、语言）
├── TranslateClient.kt     # OpenAI 兼容 HTTP 请求（文字 + 图片）
├── PhotoUtils.kt          # 拍照图片压缩、EXIF 方向修正、Base64 编码
├── Languages.kt           # 语言列表与中文检测、智能目标语言
├── Models.kt              # 数据模型
├── ProfileAdapter.kt      # 接口列表适配器
└── HistoryAdapter.kt      # 历史列表适配器
```

## 说明

- 当前版本 **1.3.32**（versionCode 36）
- 自动翻译基于「输入停顿 900ms」触发，长文本会消耗较多 token
- 智能目标语言：纯中文（只有汉字，无英文字母/假名/韩文等）→ 翻译成英文；混合语言或纯外文 → 翻译成中文
- 拍照翻译会先用系统相机拍照，图片在本地按最长边 1600px、JPEG 85% 压缩，并按 EXIF 修正方向后上传；调用的是系统相机，不需要申请相机权限
- 图片翻译需要所选模型支持 Vision（`image_url`）。不支持图片输入的模型会返回错误，请改用支持视觉的模型
- 图片翻译历史只保留译文，不保存原图，回填时输入框为空
- 历史最多保留 200 条，超出后自动删除最旧记录
- 开启 `usesCleartextTraffic` 以支持局域网 HTTP 接口；如只使用 HTTPS，可在 `AndroidManifest.xml` 中关闭
