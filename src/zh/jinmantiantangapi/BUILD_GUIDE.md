# 禁漫天堂 API 版插件 - 编译指南

## 快速开始

### 1. 环境准备

确保已安装：
- **JDK 17+**
- **Android SDK** (API Level 33+)
- **Git**

### 2. 编译插件

```bash
cd D:\kuake\jmtt\extensions-source

# Windows
.\gradlew.bat :extensions:individual:zh:jinmantiantangapi:assembleRelease

# 如果上面的命令失败，尝试：
.\gradlew.bat assembleRelease
```

### 3. 查找生成的APK

编译成功后，APK位于：
```
build/outputs/apk/release/tachiyomi-zh.jinmantiantangapi-v1.apk
```

或者：
```
extensions/individual/zh/jinmantiantangapi/build/outputs/apk/release/
```

### 4. 安装到设备

#### Tachiyomi/Mihon (Android)
1. 打开应用
2. 设置 → 扩展 → 安装APK
3. 选择编译好的APK

#### Tachimanga (iOS)
1. 将APK传输到设备
2. 在Tachimanga中导入扩展

## 常见编译问题

### 问题1: Gradle版本不兼容
```bash
# 使用项目自带的Gradle Wrapper
.\gradlew.bat --version
```

### 问题2: Android SDK路径未配置
在项目根目录创建 `local.properties`：
```properties
sdk.dir=C\:\\Users\\YourName\\AppData\\Local\\Android\\Sdk
```

### 问题3: 依赖下载失败
检查网络连接，或配置镜像：
```gradle
// 在 build.gradle 中添加
repositories {
    maven { url 'https://maven.aliyun.com/repository/public/' }
    maven { url 'https://maven.aliyun.com/repository/google/' }
}
```

### 问题4: 内存不足
```bash
# 增加Gradle内存
set GRADLE_OPTS=-Xmx2048m
.\gradlew.bat assembleRelease
```

## 验证安装

安装后，在Tachiyomi扩展列表中应该看到：
- **名称**: 禁漫天堂(API)
- **语言**: 中文
- **版本**: v1
- **NSFW**: 是

## 下一步

1. 打开插件设置
2. 输入禁漫天堂用户名和密码
3. 点击"测试登录"
4. 登录成功后即可浏览需要登录的内容

## 技术支持

如遇问题，请检查：
1. 用户名密码是否正确
2. API域名是否可访问
3. 网络连接是否正常
4. 是否已登录（查看设置中的登录状态）
