const WebSocket = require("ws");

const url = process.env.WS_URL || "ws://127.0.0.1:8080";
const ws = new WebSocket(url);
let acted = false;

ws.on("error", (error) => {
  console.error(`smoke failed: cannot connect to ${url}`);
  console.error(error.message);
  process.exit(1);
});

ws.on("open", () => {
  ws.send(JSON.stringify({ type: "hello", name: "Smoke" }));
  ws.send(JSON.stringify({ type: "createRoom" }));
});

ws.on("message", (raw) => {
  const message = JSON.parse(raw.toString());
  if (message.type !== "snapshot") {
    console.log(message.type, message.message || "");
    return;
  }

  const room = message.room;
  console.log("snapshot", room.code, room.status, room.players.length, "turn", room.turnPlayerId, "me", message.you, "hand", message.hand.length);

  if (room.status === "waiting" && room.players.length === 1) {
    ws.send(JSON.stringify({ type: "addBot" }));
    ws.send(JSON.stringify({ type: "addBot" }));
  }
  if (room.status === "bidding" && room.turnPlayerId === message.you) {
    ws.send(JSON.stringify({ type: "bid", call: true }));
  }
  if (room.status === "playing" && room.turnPlayerId === message.you && !acted) {
    acted = true;
    if (room.lastPlay) {
      ws.send(JSON.stringify({ type: "pass" }));
    } else {
      ws.send(JSON.stringify({ type: "play", cards: [message.hand[0]] }));
    }
    setTimeout(() => ws.close(), 500);
  }
});

setTimeout(() => process.exit(0), 7000);
