# 云服务器部署说明

本项目分两部分：

- `server/`：斗地主 WebSocket 服务端，部署到云服务器。
- `app/`：Android 客户端，用 Android Studio 打包安装到手机。

## 1. 上传到 GitHub

在本机完成 Git 提交后，去 GitHub 新建一个空仓库，例如：

```text
openclaw-game
```

然后在 `F:\game` 执行：

```powershell
git remote add origin https://github.com/你的GitHub用户名/openclaw-game.git
git branch -M main
git push -u origin main
```

如果 GitHub 要求登录，按网页或终端提示完成登录即可。

## 2. 云服务器拉取代码

登录云服务器后执行：

```bash
git clone https://github.com/你的GitHub用户名/openclaw-game.git
cd openclaw-game/server
npm install
npm start
```

服务端默认监听：

```text
ws://0.0.0.0:8080
```

阿里云安全组需要放行 TCP `8080` 端口。

## 3. 后台常驻运行

临时测试可以直接：

```bash
npm start
```

正式一点可以用 systemd。创建文件：

```bash
sudo nano /etc/systemd/system/openclaw.service
```

写入：

```ini
[Unit]
Description=OpenClaw Dou Dizhu Server
After=network.target

[Service]
Type=simple
WorkingDirectory=/home/admin/openclaw-game/server
ExecStart=/usr/bin/npm start
Restart=always
RestartSec=3
Environment=PORT=8080

[Install]
WantedBy=multi-user.target
```

然后执行：

```bash
sudo systemctl daemon-reload
sudo systemctl enable openclaw
sudo systemctl start openclaw
sudo systemctl status openclaw
```

如果你的项目路径不是 `/home/admin/openclaw-game/server`，把 `WorkingDirectory` 改成真实路径。

## 4. 手机端连接云服务器

Android App 首页服务器地址填写：

```text
ws://你的云服务器公网IP:8080
```

模拟器连接本机服务端时才使用：

```text
ws://10.0.2.2:8080
```

## 5. 部署后自测

在云服务器上：

```bash
cd openclaw-game/server
npm test
```

本地测试云服务器：

```powershell
cd F:\game\server
$env:WS_URL="ws://你的云服务器公网IP:8080"
npm test
```
