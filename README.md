# OpenClaw 游戏平台

这是一个手机端游戏平台 MVP，当前已先完成「斗地主」：

- Android 原生客户端：平台首页、斗地主大厅、创建/加入房间、房间座位、叫地主、选牌、出牌、过牌。
- Node.js WebSocket 服务端：房间管理、三人发牌、叫地主流程、斗地主基础牌型校验、出牌同步、测试机器人。
- 其他游戏入口：中国象棋、广东麻将、画我猜、谁是卧底已作为平台入口预留，后续可以逐个接入。

## 目录

```text
F:\game
├── app/                 Android 客户端
├── server/              斗地主 WebSocket 服务端
├── build.gradle
├── settings.gradle
└── README.md
```

## 启动服务端

本地开发：

```powershell
cd F:\game\server
npm install
npm start
```

默认监听：

```text
ws://0.0.0.0:8080
```

服务端自测：

```powershell
cd F:\game\server
node scripts/smoke.js
```

如果要测试云服务器：

```powershell
$env:WS_URL="ws://你的服务器公网IP:8080"
node scripts/smoke.js
```

## Android Studio 打开

1. 用 Android Studio 打开 `F:\game`。
2. 等 Gradle Sync 完成。
3. 运行 `app` 到模拟器或真机。
4. 在首页填写 WebSocket 地址：

```text
Android 模拟器连本机服务端: ws://10.0.2.2:8080
真机连电脑服务端:       ws://电脑局域网IP:8080
真机连云服务器:         ws://云服务器公网IP:8080
```

阿里云部署时记得在安全组里放行 TCP `8080` 端口。Linux 服务器可以用 `pm2` 或 systemd 常驻运行服务端。

更完整的云服务器部署步骤见 [DEPLOY.md](DEPLOY.md)。

## 当前斗地主能力

- 三人房间，满 3 人自动开局。
- 支持「补一个机器人」，方便单人调试。
- 支持叫地主/不叫，最终叫地主者拿底牌。
- 支持单张、对子、三张、三带一、三带二、顺子、连对、飞机、炸弹、王炸的基础识别和比较。
- 服务端权威校验出牌，客户端只负责展示和发送操作。

## 后续建议

下一步可以优先补：断线重连、账号登录、房间列表、聊天、计分结算、完整抢地主倍数、托管 AI、正式 UI 资源和 HTTPS/WSS 部署。
