# LandanAndroidClient

这是蓝丹检测手持端 Android 客户端。它不是网页，而是原生 Android App。

默认电脑端 WebSocket 地址已经设置为：

```text
ws://172.16.45.117:8000/ws_detect
```

## 工作原理

```text
Android 手持仪 / 手机摄像头预览
    ↓
点击“检测当前画面”或开启“连续检测”
    ↓
App 抓拍当前画面并压缩为 JPG
    ↓
通过 WebSocket 发送给电脑端 LandanServer
    ↓
电脑端 YOLO 推理、标注
    ↓
电脑端返回 JSON 指标 + JPG 标注图
    ↓
App 显示 OK / NG、检测指标、标注图
```

## 使用前提

1. 电脑端 `LandanServer` 已启动。
2. 电脑端能打开：

```text
http://127.0.0.1:8000/health
```

3. 手机 / 手持仪和电脑必须网络互通。通常要求两台设备在同一个局域网 / 同一个 WiFi / 同一个热点下。
4. Windows 防火墙需要放行 8000 端口。
5. 手机端连接地址不要写 `127.0.0.1`，要写电脑 IP，例如：

```text
ws://172.16.45.117:8000/ws_detect
```

## 用 GitHub Actions 构建 APK

你不需要本地安装 Android Studio。

### 方法一：GitHub 网页上传

1. 新建一个 GitHub 仓库。
2. 把本项目里的所有文件上传到仓库根目录。
3. 进入仓库的 `Actions` 页面。
4. 选择 `Build Android APK`。
5. 点击 `Run workflow`。
6. 构建完成后，在页面底部 `Artifacts` 下载：

```text
LandanClient-debug-apk
```

里面的文件是：

```text
app-debug.apk
```

把这个 APK 安装到 Android 手机或手持仪即可。

### 方法二：Git 命令上传

```bash
git init
git add .
git commit -m "add landan android client"
git branch -M main
git remote add origin https://github.com/你的用户名/你的仓库名.git
git push -u origin main
```

然后到 GitHub Actions 下载 APK。

## 修改电脑 IP

默认地址在：

```text
app/src/main/java/com/landan/client/MainActivity.java
```

找到：

```java
private static final String DEFAULT_WS_URL = "ws://172.16.45.117:8000/ws_detect";
```

如果电脑 IP 变了，改这里即可。也可以在 App 界面上直接编辑地址再连接。

## App 功能

- 摄像头实时预览
- 连接电脑端 WebSocket
- 检测当前画面
- 低帧率连续检测
- 显示 OK / NG
- 显示蓝点数量、异常数量、坏窗口、面积比例、均匀性等指标
- 显示电脑端返回的标注图

## 注意

这个 APK 是 debug 版本，适合测试和现场验证。正式交付建议后续做 release 签名版 APK。
