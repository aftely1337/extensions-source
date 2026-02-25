# 禁漫天堂 API 版插件

基于移动端 API 实现的 Tachiyomi 扩展，支持完整的登录功能。

## 功能特性

### ✅ 已实现
- **完整登录系统**：用户名密码登录，自动管理会话
- **API 协议栈**：Token 签名、AES 响应解密、Cookie 管理
- **图片解密**：复用原插件的图片混淆解密算法
- **漫画浏览**：搜索、分类、热门、最新
- **章节阅读**：完整的章节列表和图片加载
- **域名管理**：支持多个 API 域名切换
- **限流控制**：可配置的请求频率限制

### 🔐 登录功能
- 用户名密码登录
- Cookie 持久化存储
- 自动会话管理
- 登录状态检测
- 一键登出

## 与原版插件的区别

| 特性 | 原版（网页解析） | API版（本插件） |
|------|-----------------|----------------|
| 登录功能 | ❌ 不支持 | ✅ 完整支持 |
| 数据来源 | HTML 页面解析 | 移动端 API |
| 需要登录的内容 | ❌ 无法访问 | ✅ 可以访问 |
| Cloudflare 影响 | ⚠️ 可能被拦截 | ✅ 几乎不受影响 |
| 响应速度 | 较慢（HTML解析） | 较快（JSON） |

**建议**：两个插件可以同时安装，原版用于公开浏览，API版用于登录后的内容。

## 编译方法

### 前置要求
- JDK 17 或更高版本
- Android SDK
- Git

### 编译步骤

1. **克隆仓库**（如果还没有）
```bash
git clone https://github.com/keiyoushi/extensions-source.git
cd extensions-source
```

2. **复制插件代码**
将 `jinmantiantangapi` 文件夹复制到 `src/zh/` 目录下。

3. **编译插件**
```bash
# Windows
.\gradlew.bat assembleRelease

# Linux/Mac
./gradlew assembleRelease
```

4. **查找生成的 APK**
编译完成后，APK 文件位于：
```
build/outputs/apk/release/tachiyomi-zh.jinmantiantangapi-v1.apk
```

### 快速编译（仅编译此插件）
```bash
# Windows
.\gradlew.bat :extensions:individual:zh:jinmantiantangapi:assembleRelease

# Linux/Mac
./gradlew :extensions:individual:zh:jinmantiantangapi:assembleRelease
```

## 安装方法

### 在 Tachiyomi/Mihon 中安装

1. 打开 Tachiyomi/Mihon
2. 进入 **设置 → 扩展**
3. 点击右上角的 **安装 APK**
4. 选择编译好的 APK 文件
5. 安装完成后，在扩展列表中启用插件

### 在 iOS (Tachimanga) 中使用

Tachimanga 使用的是 Tachiyomi 的扩展格式，但需要注意：
1. 确保 Tachimanga 版本支持自定义扩展
2. 将 APK 文件传输到设备
3. 在 Tachimanga 中导入扩展

## 使用说明

### 首次使用

1. **配置登录信息**
   - 打开插件设置
   - 输入禁漫天堂的用户名和密码
   - 点击"测试登录"验证

2. **选择 API 域名**
   - 如果默认域名无法访问
   - 在设置中切换到其他可用域名
   - 切换后需要重启应用

3. **开始浏览**
   - 登录成功后即可访问需要登录的内容
   - 支持搜索、分类浏览、收藏等功能

### 常见问题

**Q: 登录失败怎么办？**
- 检查用户名密码是否正确
- 尝试切换 API 域名
- 检查网络连接

**Q: 图片加载失败？**
- 检查是否已登录
- 尝试切换 API 域名
- 调整限流设置（降低请求频率）

**Q: 与原版插件冲突？**
- 两个插件可以同时安装
- 它们使用不同的数据源，互不影响

**Q: 如何更新插件？**
- 重新编译新版本
- 卸载旧版本
- 安装新版本 APK

## 技术架构

### 核心组件

1. **JmCryptoTool** - 加密工具
   - MD5 哈希
   - AES-ECB 解密
   - Token 签名生成

2. **JmCookieJar** - Cookie 管理
   - 持久化存储
   - 自动过期处理
   - 域名隔离

3. **ApiSignatureInterceptor** - 请求签名
   - 自动添加 token/tokenparam
   - 时间戳管理

4. **ApiResponseInterceptor** - 响应解密
   - 自动解密 API 响应
   - JSON 数据提取

5. **AuthManager** - 登录管理
   - 用户认证
   - 会话维护
   - 状态检测

6. **JmApiClient** - API 客户端
   - 封装所有 API 调用
   - 数据模型转换

7. **ScrambledImageInterceptor** - 图片解密
   - 图片混淆还原
   - 支持多种混淆算法

### API 协议

禁漫天堂移动端 API 使用以下安全机制：

1. **Token 签名**
   ```
   token = MD5(timestamp + secret)
   tokenparam = "timestamp,version"
   ```

2. **响应加密**
   ```
   加密流程：明文 → AES-ECB → Base64
   解密流程：Base64 → AES-ECB → 明文
   密钥：MD5(timestamp + secret)
   ```

3. **Cookie 认证**
   - 登录后获取 AVS cookie
   - 所有请求携带 Cookie

## 开发说明

### 项目结构
```
jinmantiantangapi/
├── src/eu/kanade/tachiyomi/extension/zh/jinmantiantangapi/
│   ├── JmConstants.kt              # API 常量配置
│   ├── JmCryptoTool.kt             # 加密工具类
│   ├── JmCookieJar.kt              # Cookie 管理
│   ├── ApiSignatureInterceptor.kt  # 请求签名拦截器
│   ├── ApiResponseInterceptor.kt   # 响应解密拦截器
│   ├── AuthManager.kt              # 登录管理器
│   ├── JmApiClient.kt              # API 客户端
│   ├── ScrambledImageInterceptor.kt # 图片解密
│   ├── JinmantiantangApi.kt        # 主 Source 类
│   └── JinmantiantangApiPreferences.kt # 设置界面
├── res/                            # 图标资源
├── build.gradle                    # 构建配置
└── AndroidManifest.xml             # Android 清单
```

### 添加新功能

如需添加新功能（如收藏、评论等），请：

1. 在 `JmConstants.kt` 中添加 API 端点
2. 在 `JmApiClient.kt` 中实现 API 调用
3. 在 `JinmantiantangApi.kt` 中暴露功能
4. 更新设置界面（如需要）

## 致谢

- 原版插件作者：提供了图片解密算法
- [JMComic-Crawler-Python](https://github.com/hect0x7/JMComic-Crawler-Python)：API 协议参考
- Tachiyomi/Mihon 开发团队

## 许可证

本插件遵循 Apache License 2.0 开源协议。

## 免责声明

本插件仅供学习交流使用，请遵守当地法律法规。使用本插件产生的任何后果由使用者自行承担。
