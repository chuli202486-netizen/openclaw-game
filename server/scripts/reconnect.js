const WebSocket = require("ws");

const url = process.env.WS_URL || "ws://127.0.0.1:8080";
let resumeId = "";
let roomCode = "";
let closedOnce = false;

function connect(name, onSnapshot) {
  const ws = new WebSocket(url);
  ws.on("error", (error) => {
    console.error(`reconnect failed: ${error.message}`);
    process.exit(1);
  });
  ws.on("open", () => {
    ws.send(JSON.stringify({ type: "hello", name, resumeId }));
    if (!resumeId) {
      ws.send(JSON.stringify({ type: "createRoom" }));
    }
  });
  ws.on("message", (raw) => {
    const message = JSON.parse(raw.toString());
    if (message.type === "snapshot") {
      resumeId = message.you;
      roomCode = message.room.code;
      onSnapshot(ws, message);
    }
  });
  return ws;
}

connect("Reconnect", (ws, message) => {
  const room = message.room;
  console.log("first", room.code, room.status, room.players.length, message.you);
  if (room.players.length === 0) {
    return;
  }
  if (room.status === "waiting" && room.players.length === 1) {
    ws.send(JSON.stringify({ type: "addBot" }));
    ws.send(JSON.stringify({ type: "addBot" }));
  }
  if (!closedOnce && room.status === "bidding") {
    closedOnce = true;
    ws.close();
    setTimeout(() => {
      connect("Reconnect", (nextWs, nextMessage) => {
        console.log("resumed", nextMessage.room.code, nextMessage.room.status, nextMessage.you);
        if (nextMessage.you !== resumeId || nextMessage.room.code !== roomCode) {
          console.error("resume mismatch");
          process.exit(1);
        }
        nextWs.close();
        setTimeout(() => process.exit(0), 300);
      });
    }, 500);
  }
});

setTimeout(() => {
  console.error("reconnect test timed out");
  process.exit(1);
}, 10000);
