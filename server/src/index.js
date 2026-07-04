const WebSocket = require("ws");

const PORT = Number(process.env.PORT || 8080);
const wss = new WebSocket.Server({ port: PORT });
const rooms = new Map();
const players = new Map();

const RANKS = ["3", "4", "5", "6", "7", "8", "9", "T", "J", "Q", "K", "A", "2", "X", "D"];
const SUITS = ["S", "H", "C", "D"];

let nextPlayerId = 1;
let nextBotId = 1;

wss.on("connection", (ws) => {
  const player = {
    id: `p${nextPlayerId++}`,
    name: "玩家",
    ws,
    roomCode: null,
    hand: [],
    bot: false,
    score: 0,
    autoPlay: false,
    disconnected: false,
    closeTimer: null
  };
  players.set(player.id, player);

  ws.on("message", (raw) => {
    let message;
    try {
      message = JSON.parse(raw.toString());
    } catch (error) {
      send(player, { type: "error", message: "消息格式不是 JSON" });
      return;
    }
    handleMessage(player, message);
  });

  ws.on("close", () => {
    if (player.ws !== ws) {
      return;
    }
    handleDisconnect(player);
  });

  send(player, { type: "notice", message: "欢迎来到 OpenClaw 联机服务器" });
});

console.log(`OpenClaw game server listening on ws://0.0.0.0:${PORT}`);

function handleMessage(player, message) {
  switch (message.type) {
    case "hello":
      resumePlayer(player, message);
      break;
    case "createRoom":
      joinRoom(player, createRoom("friend", "doudizhu"));
      break;
    case "createChessRoom":
      joinRoom(player, createRoom("friend", "chess"));
      break;
    case "quickJoin":
      joinRoom(player, findJoinableRoom("friend", "doudizhu") || createRoom("friend", "doudizhu"));
      break;
    case "goldMatch":
      goldMatch(player);
      break;
    case "joinRoom":
      joinRoomByCode(player, message.code);
      break;
    case "joinChessRoom":
      joinChessRoomByCode(player, message.code);
      break;
    case "leaveRoom":
      leaveRoom(player, false);
      break;
    case "addChessBot":
      addChessBot(player);
      break;
    case "chessMove":
      moveChess(player, message);
      break;
    case "chessUndo":
      undoChess(player);
      break;
    case "chessRestart":
      restartChess(player);
      break;
    case "addBot":
      addBot(player);
      break;
    case "bid":
      bid(player, Boolean(message.call));
      break;
    case "play":
      playCards(player, Array.isArray(message.cards) ? message.cards : []);
      break;
    case "pass":
      passTurn(player);
      break;
    case "restart":
      restart(player);
      break;
    case "toggleAuto":
      toggleAuto(player, Boolean(message.enabled));
      break;
    default:
      send(player, { type: "error", message: "未知操作" });
  }
}

function resumePlayer(player, message) {
  const resumeId = String(message.resumeId || "");
  const existing = players.get(resumeId);
  if (existing && existing !== player && !existing.bot && existing.roomCode) {
    const room = rooms.get(existing.roomCode);
    if (room) {
      const index = room.players.findIndex((item) => item.id === existing.id);
      if (index !== -1) {
        clearTimeout(existing.closeTimer);
        players.delete(player.id);
        player.id = existing.id;
        player.name = cleanName(message.name || existing.name);
        player.roomCode = existing.roomCode;
        player.hand = existing.hand;
        player.bot = false;
        player.score = existing.score;
        player.autoPlay = false;
        player.disconnected = false;
        player.closeTimer = null;
        room.players[index] = player;
        players.set(player.id, player);
        send(player, { type: "notice", message: "已恢复到原房间" });
        sendSnapshot(room);
        scheduleBot(room);
        return;
      }
    }
  }

  player.name = cleanName(message.name);
  player.disconnected = false;
  send(player, { type: "notice", message: `${player.name} 已上线` });
}

function handleDisconnect(player) {
  player.ws = null;
  player.disconnected = true;
  const room = player.roomCode ? rooms.get(player.roomCode) : null;
  if (!room) {
    players.delete(player.id);
    return;
  }
  if (room.game === "chess") {
    leaveRoom(player, true);
    players.delete(player.id);
    return;
  }
  if (room.status === "waiting" || room.status === "ended") {
    leaveRoom(player, true);
    players.delete(player.id);
    return;
  }
  player.autoPlay = true;
  sendSnapshot(room, `${player.name} 断线，已进入托管`);
  scheduleBot(room);
  player.closeTimer = setTimeout(() => {
    if (player.disconnected) {
      leaveRoom(player, true);
      players.delete(player.id);
    }
  }, 60000);
}

function toggleAuto(player, enabled) {
  const room = currentRoom(player);
  if (!room) {
    return;
  }
  if (player.bot) {
    return;
  }
  player.autoPlay = enabled;
  player.disconnected = false;
  sendSnapshot(room, enabled ? `${player.name} 开启托管` : `${player.name} 取消托管`);
  scheduleBot(room);
}

function createRoom(mode = "friend", game = "doudizhu") {
  let code = "";
  do {
    code = String(Math.floor(1000 + Math.random() * 9000));
  } while (rooms.has(code));

  const room = {
    code,
    game,
    mode,
    players: [],
    status: "waiting",
    bottom: [],
    landlordId: null,
    turnIndex: 0,
    bidTurns: 0,
    bidderId: null,
    lastPlay: null,
    passCount: 0,
    winnerId: null,
    winnerName: "",
    roundId: 0,
    baseScore: 1,
    multiplier: 1,
    lastSettlement: [],
    chessBoard: null,
    chessTurnColor: "red",
    chessHistory: [],
    redPlayerId: "",
    blackPlayerId: "",
    chessWinnerColor: ""
  };
  rooms.set(code, room);
  return room;
}

function findJoinableRoom(mode = "friend", game = "doudizhu") {
  for (const room of rooms.values()) {
    const maxPlayers = room.game === "chess" ? 2 : 3;
    if (room.mode === mode && room.game === game && room.status === "waiting" && room.players.length < maxPlayers) {
      return room;
    }
  }
  return null;
}

function joinRoomByCode(player, code) {
  const room = rooms.get(String(code || "").trim());
  if (!room) {
    send(player, { type: "error", message: "房间不存在" });
    return;
  }
  joinRoom(player, room);
}

function joinChessRoomByCode(player, code) {
  const room = rooms.get(String(code || "").trim());
  if (!room) {
    send(player, { type: "error", message: "房间不存在" });
    return;
  }
  if (room.game !== "chess") {
    send(player, { type: "error", message: "这不是象棋房间" });
    return;
  }
  joinRoom(player, room);
}

function goldMatch(player) {
  const room = findJoinableRoom("gold", "doudizhu") || createRoom("gold", "doudizhu");
  joinRoom(player, room);
  const joinedRoom = player.roomCode ? rooms.get(player.roomCode) : null;
  if (joinedRoom && joinedRoom.status === "waiting") {
    const need = 3 - joinedRoom.players.length;
    sendSnapshot(joinedRoom, need > 0 ? `金币场等待 ${need} 位真人玩家加入` : "金币场已配桌，准备开局");
  }
}

function joinRoom(player, room) {
  if (player.roomCode === room.code) {
    sendRoomSnapshot(room);
    return;
  }
  const maxPlayers = room.game === "chess" ? 2 : 3;
  if (room.players.length >= maxPlayers) {
    send(player, { type: "error", message: "房间已满" });
    return;
  }
  if (room.status !== "waiting") {
    send(player, { type: "error", message: "房间已经开局" });
    return;
  }

  leaveRoom(player, false);
  player.roomCode = room.code;
  player.hand = [];
  room.players.push(player);
  normalizeSeats(room);
  if (room.game === "chess") {
    assignChessSides(room);
    sendChessSnapshot(room, `${player.name} 加入了象棋房间`);
    if (room.players.length === 2) {
      startChess(room);
    }
    return;
  }
  sendSnapshot(room, `${player.name} 加入了房间`);
  if (room.players.length === maxPlayers) {
    startGame(room);
  }
}

function leaveRoom(player, silent) {
  if (!player.roomCode) {
    return;
  }
  const room = rooms.get(player.roomCode);
  player.roomCode = null;
  player.hand = [];
  if (!room) {
    return;
  }

  room.players = room.players.filter((item) => item.id !== player.id);
  normalizeSeats(room);
  if (room.players.length === 0 || room.players.every((item) => item.bot)) {
    destroyRoom(room);
    return;
  }
  if (room.turnIndex >= room.players.length) {
    room.turnIndex = 0;
  }

  if (room.game === "chess") {
    if (room.status === "playing") {
      room.status = "ended";
      room.winnerName = "对局因玩家离开结束";
    } else {
      room.status = "waiting";
    }
    assignChessSides(room);
    sendChessSnapshot(room, silent ? "" : `${player.name} 离开了房间`);
    return;
  }

  if (room.status === "bidding" || room.status === "playing") {
    room.status = "ended";
    room.winnerName = "对局因玩家离开结束";
    room.turnIndex = 0;
  } else {
    room.status = "waiting";
  }
  sendSnapshot(room, silent ? "" : `${player.name} 离开了房间`);
}

function destroyRoom(room) {
  for (const item of room.players) {
    item.roomCode = null;
    item.hand = [];
    if (item.bot) {
      players.delete(item.id);
    }
  }
  rooms.delete(room.code);
}

function addBot(player) {
  const room = currentRoom(player);
  if (!room) {
    return;
  }
  if (room.status !== "waiting") {
    send(player, { type: "error", message: "开局后不能补机器人" });
    return;
  }
  if (room.mode === "gold") {
    send(player, { type: "error", message: "金币场只匹配真人玩家，不能补机器人" });
    return;
  }
  if (room.players.length >= 3) {
    send(player, { type: "error", message: "房间已满" });
    return;
  }
  const bot = addBotToRoom(room);
  sendSnapshot(room, `${bot.name} 加入了房间`);
  if (room.players.length === 3) {
    startGame(room);
  }
}

function addBotToRoom(room) {
  const bot = {
    id: `b${nextBotId++}`,
    name: `机器人${nextBotId - 1}`,
    ws: null,
    roomCode: room.code,
    hand: [],
    bot: true,
    score: 0,
    autoPlay: true,
    disconnected: false,
    closeTimer: null
  };
  players.set(bot.id, bot);
  room.players.push(bot);
  normalizeSeats(room);
  return bot;
}

function addChessBot(player) {
  const room = currentRoom(player);
  if (!room || room.game !== "chess") {
    return;
  }
  if (room.status !== "waiting") {
    send(player, { type: "error", message: "开局后不能补人机" });
    return;
  }
  if (room.players.length >= 2) {
    send(player, { type: "error", message: "房间已满" });
    return;
  }
  const bot = {
    id: `b${nextBotId++}`,
    name: `棋手${nextBotId - 1}`,
    ws: null,
    roomCode: room.code,
    hand: [],
    bot: true,
    score: 0,
    autoPlay: true,
    disconnected: false,
    closeTimer: null
  };
  players.set(bot.id, bot);
  room.players.push(bot);
  normalizeSeats(room);
  assignChessSides(room);
  sendChessSnapshot(room, `${bot.name} 加入了象棋房间`);
  startChess(room);
}

function assignChessSides(room) {
  room.redPlayerId = room.players[0] ? room.players[0].id : "";
  room.blackPlayerId = room.players[1] ? room.players[1].id : "";
}

function startChess(room) {
  assignChessSides(room);
  room.chessBoard = createChessBoard();
  room.chessTurnColor = "red";
  room.chessHistory = [];
  room.chessWinnerColor = "";
  room.winnerName = "";
  room.status = "playing";
  room.roundId += 1;
  sendChessSnapshot(room, "象棋开局，红方先行");
  scheduleChessBot(room);
}

function restartChess(player) {
  const room = currentRoom(player);
  if (!room || room.game !== "chess") {
    return;
  }
  if (room.players.length < 2) {
    room.status = "waiting";
    sendChessSnapshot(room, "等待对手加入");
    return;
  }
  startChess(room);
}

function moveChess(player, message) {
  const room = currentRoom(player);
  if (!room || room.game !== "chess" || room.status !== "playing") {
    return;
  }
  const turnPlayerId = chessTurnPlayerId(room);
  if (turnPlayerId !== player.id) {
    send(player, { type: "error", message: "还没轮到你" });
    return;
  }
  const fromRow = Number(message.fromRow);
  const fromCol = Number(message.fromCol);
  const toRow = Number(message.toRow);
  const toCol = Number(message.toCol);
  if (!legalChessMove(room.chessBoard, fromRow, fromCol, toRow, toCol)) {
    send(player, { type: "error", message: "这一步不符合象棋规则" });
    return;
  }
  const piece = room.chessBoard[fromRow][fromCol];
  if (chessPieceColor(piece) !== room.chessTurnColor) {
    send(player, { type: "error", message: "请选择自己的棋子" });
    return;
  }
  const captured = room.chessBoard[toRow][toCol];
  room.chessHistory.push({
    board: copyChessBoard(room.chessBoard),
    turnColor: room.chessTurnColor,
    status: room.status,
    winnerName: room.winnerName,
    chessWinnerColor: room.chessWinnerColor
  });
  room.chessBoard[toRow][toCol] = piece;
  room.chessBoard[fromRow][fromCol] = null;
  if (generalsFace(room.chessBoard)) {
    const previous = room.chessHistory.pop();
    restoreChess(room, previous);
    send(player, { type: "error", message: "将帅不能直接照面" });
    return;
  }
  if (captured === "帅" || captured === "将") {
    room.status = "ended";
    room.chessWinnerColor = room.chessTurnColor;
    room.winnerName = chessPlayerName(room, room.chessTurnColor) + " 获胜";
    sendChessSnapshot(room, room.winnerName);
    return;
  }
  room.chessTurnColor = room.chessTurnColor === "red" ? "black" : "red";
  sendChessSnapshot(room, captured ? `${player.name} 吃掉 ${captured}` : `${player.name} 落子`);
  scheduleChessBot(room);
}

function undoChess(player) {
  const room = currentRoom(player);
  if (!room || room.game !== "chess") {
    return;
  }
  const previous = room.chessHistory.pop();
  if (!previous) {
    send(player, { type: "error", message: "当前没有可悔棋的步数" });
    return;
  }
  restoreChess(room, previous);
  sendChessSnapshot(room, `${player.name} 悔棋`);
}

function restoreChess(room, state) {
  room.chessBoard = copyChessBoard(state.board);
  room.chessTurnColor = state.turnColor;
  room.status = state.status;
  room.winnerName = state.winnerName;
  room.chessWinnerColor = state.chessWinnerColor;
}

function scheduleChessBot(room) {
  const roundId = room.roundId;
  const player = room.players.find((item) => item.id === chessTurnPlayerId(room));
  if (!player || !player.bot || room.status !== "playing") {
    return;
  }
  setTimeout(() => {
    const latest = rooms.get(room.code);
    if (!latest || latest.roundId !== roundId || latest.status !== "playing") {
      return;
    }
    const current = latest.players.find((item) => item.id === chessTurnPlayerId(latest));
    if (!current || !current.bot) {
      return;
    }
    const move = findChessBotMove(latest);
    if (move) {
      moveChess(current, move);
    }
  }, 800);
}

function findChessBotMove(room) {
  const moves = [];
  for (let fromRow = 0; fromRow < 10; fromRow += 1) {
    for (let fromCol = 0; fromCol < 9; fromCol += 1) {
      const piece = room.chessBoard[fromRow][fromCol];
      if (!piece || chessPieceColor(piece) !== room.chessTurnColor) {
        continue;
      }
      for (let toRow = 0; toRow < 10; toRow += 1) {
        for (let toCol = 0; toCol < 9; toCol += 1) {
          if (legalChessMove(room.chessBoard, fromRow, fromCol, toRow, toCol)) {
            const copy = copyChessBoard(room.chessBoard);
            copy[toRow][toCol] = piece;
            copy[fromRow][fromCol] = null;
            if (!generalsFace(copy)) {
              moves.push({ fromRow, fromCol, toRow, toCol });
            }
          }
        }
      }
    }
  }
  moves.sort((left, right) => {
    const leftCapture = room.chessBoard[left.toRow][left.toCol] ? 1 : 0;
    const rightCapture = room.chessBoard[right.toRow][right.toCol] ? 1 : 0;
    return rightCapture - leftCapture;
  });
  return moves[0] || null;
}

function createChessBoard() {
  const board = Array.from({ length: 10 }, () => Array(9).fill(null));
  const blackBack = ["車", "馬", "象", "士", "将", "士", "象", "馬", "車"];
  const redBack = ["车", "马", "相", "仕", "帅", "仕", "相", "马", "车"];
  for (let col = 0; col < 9; col += 1) {
    board[0][col] = blackBack[col];
    board[9][col] = redBack[col];
  }
  board[2][1] = "砲";
  board[2][7] = "砲";
  board[7][1] = "炮";
  board[7][7] = "炮";
  for (let col = 0; col < 9; col += 2) {
    board[3][col] = "卒";
    board[6][col] = "兵";
  }
  return board;
}

function copyChessBoard(board) {
  return board.map((row) => [...row]);
}

function fillBots(room) {
  while (room.status === "waiting" && room.players.length < 3) {
    addBotToRoom(room);
  }
  if (room.status === "waiting" && room.players.length === 3) {
    sendSnapshot(room, "金币场已配桌，准备开局");
    startGame(room);
  }
}

function startGame(room) {
  room.roundId += 1;
  const deck = shuffle(createDeck());
  for (const player of room.players) {
    player.hand = [];
  }
  for (let i = 0; i < 51; i += 1) {
    room.players[i % 3].hand.push(deck[i]);
  }
  for (const player of room.players) {
    sortCards(player.hand);
  }
  room.bottom = deck.slice(51);
  room.status = "bidding";
  room.landlordId = null;
  room.turnIndex = Math.floor(Math.random() * 3);
  room.bidTurns = 0;
  room.bidderId = null;
  room.lastPlay = null;
  room.passCount = 0;
  room.winnerId = null;
  room.winnerName = "";
  room.multiplier = 1;
  room.lastSettlement = [];
  sendSnapshot(room, "新一局开始，进入叫地主");
  scheduleBot(room);
}

function bid(player, call) {
  const room = currentRoom(player);
  if (!room || room.status !== "bidding") {
    return;
  }
  if (currentPlayer(room).id !== player.id) {
    send(player, { type: "error", message: "还没轮到你" });
    return;
  }
  if (call) {
    room.bidderId = player.id;
  }
  room.bidTurns += 1;
  if (room.bidTurns >= 3) {
    if (!room.bidderId) {
      startGame(room);
      return;
    }
    const landlord = room.players.find((item) => item.id === room.bidderId);
    room.landlordId = landlord.id;
    landlord.hand.push(...room.bottom);
    sortCards(landlord.hand);
    room.status = "playing";
    room.turnIndex = room.players.findIndex((item) => item.id === landlord.id);
    sendSnapshot(room, `${landlord.name} 成为地主`);
    scheduleBot(room);
    return;
  }
  nextTurn(room);
  sendSnapshot(room, call ? `${player.name} 叫地主` : `${player.name} 不叫`);
  scheduleBot(room);
}

function playCards(player, cards) {
  const room = currentRoom(player);
  if (!room || room.status !== "playing") {
    return;
  }
  if (currentPlayer(room).id !== player.id) {
    send(player, { type: "error", message: "还没轮到你" });
    return;
  }
  const normalized = [...new Set(cards)].filter(Boolean);
  if (normalized.length === 0) {
    send(player, { type: "error", message: "请选择要出的牌" });
    return;
  }
  if (!ownsCards(player.hand, normalized)) {
    send(player, { type: "error", message: "手牌不匹配" });
    return;
  }
  const combo = analyze(normalized);
  if (!combo) {
    send(player, { type: "error", message: "这个牌型暂不合法" });
    return;
  }
  if (room.lastPlay && room.lastPlay.playerId !== player.id && !beats(combo, room.lastPlay.combo)) {
    send(player, { type: "error", message: "没有压过上一手牌" });
    return;
  }

  removeCards(player.hand, normalized);
  if (combo.type === "bomb" || combo.type === "rocket") {
    room.multiplier *= 2;
  }
  room.lastPlay = {
    playerId: player.id,
    playerName: player.name,
    cards: normalized,
    combo
  };
  room.passCount = 0;

  if (player.hand.length === 0) {
    room.status = "ended";
    room.winnerId = player.id;
    room.winnerName = player.name;
    settleRoom(room, player);
    sendSnapshot(room, `${player.name} 出完手牌，获得胜利`);
    return;
  }

  nextTurn(room);
  sendSnapshot(room, `${player.name} 出牌`);
  scheduleBot(room);
}

function passTurn(player) {
  const room = currentRoom(player);
  if (!room || room.status !== "playing") {
    return;
  }
  if (currentPlayer(room).id !== player.id) {
    send(player, { type: "error", message: "还没轮到你" });
    return;
  }
  if (!room.lastPlay || room.lastPlay.playerId === player.id) {
    send(player, { type: "error", message: "当前不能过牌" });
    return;
  }
  room.passCount += 1;
  if (room.passCount >= 2) {
    room.lastPlay = null;
    room.passCount = 0;
  }
  nextTurn(room);
  sendSnapshot(room, `${player.name} 过牌`);
  scheduleBot(room);
}

function restart(player) {
  const room = currentRoom(player);
  if (!room) {
    return;
  }
  if (room.game === "chess") {
    restartChess(player);
    return;
  }
  if (room.players.length < 3) {
    room.status = "waiting";
    sendSnapshot(room, "等待玩家加入");
    return;
  }
  startGame(room);
}

function settleRoom(room, winner) {
  const landlordWon = winner.id === room.landlordId;
  const delta = room.baseScore * room.multiplier;
  room.lastSettlement = room.players.map((player) => {
    const isLandlord = player.id === room.landlordId;
    const change = isLandlord
      ? (landlordWon ? delta * 2 : -delta * 2)
      : (landlordWon ? -delta : delta);
    player.score += change;
    return {
      playerId: player.id,
      name: player.name,
      change,
      score: player.score,
      role: isLandlord ? "地主" : "农民"
    };
  });
}

function scheduleBot(room) {
  const roundId = room.roundId;
  const player = currentPlayer(room);
  if (!player || (!player.bot && !player.autoPlay && !player.disconnected) || room.status === "waiting" || room.status === "ended") {
    return;
  }
  const delay = player.bot ? 900 : 1600;
  setTimeout(() => {
    const latest = rooms.get(room.code);
    if (!latest || latest.roundId !== roundId || !currentPlayer(latest) || currentPlayer(latest).id !== player.id) {
      return;
    }
    if (latest.status === "bidding") {
      bid(player, player.bot ? shouldBotBid(player) : false);
    } else if (latest.status === "playing") {
      botPlay(player, latest);
    }
  }, delay);
}

function botPlay(player, room) {
  if (!room.lastPlay || room.lastPlay.playerId === player.id) {
    playCards(player, [smallestCard(player.hand)]);
    return;
  }
  const response = findBotResponse(player.hand, room.lastPlay.combo);
  if (response.length > 0) {
    playCards(player, response);
  } else {
    passTurn(player);
  }
}

function shouldBotBid(player) {
  const power = player.hand.reduce((sum, card) => sum + (cardValue(card) >= 11 ? 1 : 0), 0);
  return power >= 5 || Math.random() > 0.72;
}

function findBotResponse(hand, combo) {
  const groups = groupByValue(hand);
  if (combo.type === "single") {
    for (const card of [...hand].sort(compareCards)) {
      if (cardValue(card) > combo.main) {
        return [card];
      }
    }
  }
  if (combo.type === "pair") {
    for (const group of groups) {
      if (group.value > combo.main && group.cards.length >= 2) {
        return group.cards.slice(0, 2);
      }
    }
  }
  if (combo.type === "triple") {
    for (const group of groups) {
      if (group.value > combo.main && group.cards.length >= 3) {
        return group.cards.slice(0, 3);
      }
    }
  }
  for (const group of groups) {
    if (group.cards.length === 4) {
      return group.cards;
    }
  }
  if (hand.includes("XR") && hand.includes("DB")) {
    return ["XR", "DB"];
  }
  return [];
}

function sendSnapshot(room, notice = "") {
  for (const player of room.players) {
    if (!player.ws || player.ws.readyState !== WebSocket.OPEN) {
      continue;
    }
    send(player, {
      type: "snapshot",
      you: player.id,
      message: notice,
      room: publicRoom(room),
      hand: player.hand,
      suggestion: suggestionFor(player, room)
    });
  }
}

function sendRoomSnapshot(room, notice = "") {
  if (room.game === "chess") {
    sendChessSnapshot(room, notice);
  } else {
    sendSnapshot(room, notice);
  }
}

function sendChessSnapshot(room, notice = "") {
  for (const player of room.players) {
    if (!player.ws || player.ws.readyState !== WebSocket.OPEN) {
      continue;
    }
    send(player, {
      type: "chessSnapshot",
      you: player.id,
      message: notice,
      room: publicChessRoom(room)
    });
  }
}

function publicChessRoom(room) {
  return {
    code: room.code,
    game: "chess",
    mode: room.mode || "friend",
    status: room.status,
    board: room.chessBoard || createChessBoard(),
    turnColor: room.chessTurnColor || "red",
    turnPlayerId: chessTurnPlayerId(room),
    redPlayerId: room.redPlayerId,
    blackPlayerId: room.blackPlayerId,
    winnerName: room.winnerName,
    historyCount: room.chessHistory ? room.chessHistory.length : 0,
    players: room.players.map((player) => ({
      id: player.id,
      name: player.name,
      seat: player.seat,
      side: player.id === room.redPlayerId ? "red" : player.id === room.blackPlayerId ? "black" : "",
      bot: player.bot,
      disconnected: player.disconnected
    }))
  };
}

function chessTurnPlayerId(room) {
  return room.chessTurnColor === "red" ? room.redPlayerId : room.blackPlayerId;
}

function chessPlayerName(room, color) {
  const id = color === "red" ? room.redPlayerId : room.blackPlayerId;
  const player = room.players.find((item) => item.id === id);
  return player ? player.name : color === "red" ? "红方" : "黑方";
}

function legalChessMove(board, fromRow, fromCol, toRow, toCol) {
  if (!insideChessBoard(fromRow, fromCol) || !insideChessBoard(toRow, toCol)) {
    return false;
  }
  const piece = board[fromRow][fromCol];
  if (!piece || (fromRow === toRow && fromCol === toCol)) {
    return false;
  }
  const target = board[toRow][toCol];
  const red = chessPieceColor(piece) === "red";
  if (target && chessPieceColor(target) === chessPieceColor(piece)) {
    return false;
  }
  const rowDelta = toRow - fromRow;
  const colDelta = toCol - fromCol;
  const absRow = Math.abs(rowDelta);
  const absCol = Math.abs(colDelta);
  if (piece === "帅" || piece === "将") {
    if (fromCol === toCol && target && (target === "帅" || target === "将") && clearChessStraight(board, fromRow, fromCol, toRow, toCol)) {
      return true;
    }
    return inChessPalace(toRow, toCol, red) && absRow + absCol === 1;
  }
  if (piece === "仕" || piece === "士") {
    return inChessPalace(toRow, toCol, red) && absRow === 1 && absCol === 1;
  }
  if (piece === "相" || piece === "象") {
    const eyeRow = (fromRow + toRow) / 2;
    const eyeCol = (fromCol + toCol) / 2;
    const sameSide = red ? toRow >= 5 : toRow <= 4;
    return sameSide && absRow === 2 && absCol === 2 && !board[eyeRow][eyeCol];
  }
  if (piece === "马" || piece === "馬") {
    if (!((absRow === 2 && absCol === 1) || (absRow === 1 && absCol === 2))) {
      return false;
    }
    const legRow = fromRow + (absRow === 2 ? rowDelta / 2 : 0);
    const legCol = fromCol + (absCol === 2 ? colDelta / 2 : 0);
    return !board[legRow][legCol];
  }
  if (piece === "车" || piece === "車") {
    return clearChessStraight(board, fromRow, fromCol, toRow, toCol);
  }
  if (piece === "炮" || piece === "砲") {
    const screens = countChessBetween(board, fromRow, fromCol, toRow, toCol);
    return target ? screens === 1 : screens === 0;
  }
  if (piece === "兵" || piece === "卒") {
    const forward = red ? -1 : 1;
    if (rowDelta === forward && colDelta === 0) {
      return true;
    }
    const crossedRiver = red ? fromRow <= 4 : fromRow >= 5;
    return crossedRiver && rowDelta === 0 && absCol === 1;
  }
  return false;
}

function insideChessBoard(row, col) {
  return Number.isInteger(row) && Number.isInteger(col) && row >= 0 && row < 10 && col >= 0 && col < 9;
}

function chessPieceColor(piece) {
  return "车马相仕帅炮兵".includes(piece) ? "red" : "black";
}

function inChessPalace(row, col, red) {
  return col >= 3 && col <= 5 && (red ? row >= 7 && row <= 9 : row >= 0 && row <= 2);
}

function clearChessStraight(board, fromRow, fromCol, toRow, toCol) {
  return countChessBetween(board, fromRow, fromCol, toRow, toCol) === 0;
}

function countChessBetween(board, fromRow, fromCol, toRow, toCol) {
  if (fromRow !== toRow && fromCol !== toCol) {
    return -1;
  }
  let count = 0;
  const rowStep = Math.sign(toRow - fromRow);
  const colStep = Math.sign(toCol - fromCol);
  let row = fromRow + rowStep;
  let col = fromCol + colStep;
  while (row !== toRow || col !== toCol) {
    if (board[row][col]) {
      count += 1;
    }
    row += rowStep;
    col += colStep;
  }
  return count;
}

function generalsFace(board) {
  let red = null;
  let black = null;
  for (let row = 0; row < 10; row += 1) {
    for (let col = 0; col < 9; col += 1) {
      if (board[row][col] === "帅") {
        red = { row, col };
      }
      if (board[row][col] === "将") {
        black = { row, col };
      }
    }
  }
  if (!red || !black || red.col !== black.col) {
    return false;
  }
  return countChessBetween(board, black.row, black.col, red.row, red.col) === 0;
}

function publicRoom(room) {
  return {
    code: room.code,
    mode: room.mode || "friend",
    status: room.status,
    landlordId: room.landlordId,
    turnPlayerId: currentPlayer(room) ? currentPlayer(room).id : "",
    bottom: room.status === "waiting" || room.status === "bidding" ? [] : room.bottom,
    lastPlay: room.lastPlay ? {
      playerId: room.lastPlay.playerId,
      playerName: room.lastPlay.playerName,
      cards: room.lastPlay.cards,
      combo: room.lastPlay.combo
    } : null,
    winnerId: room.winnerId,
    winnerName: room.winnerName,
    baseScore: room.baseScore,
    multiplier: room.multiplier,
    lastSettlement: room.lastSettlement,
    players: room.players.map((player) => ({
      id: player.id,
      name: player.name,
      seat: player.seat,
      bot: player.bot,
      cardCount: player.hand.length,
      score: player.score,
      autoPlay: player.autoPlay,
      disconnected: player.disconnected
    }))
  };
}

function suggestionFor(player, room) {
  if (room.status !== "playing" || !currentPlayer(room) || currentPlayer(room).id !== player.id) {
    return [];
  }
  if (!room.lastPlay || room.lastPlay.playerId === player.id) {
    return [smallestCard(player.hand)].filter(Boolean);
  }
  return findBotResponse(player.hand, room.lastPlay.combo);
}

function send(player, payload) {
  if (player.ws && player.ws.readyState === WebSocket.OPEN) {
    player.ws.send(JSON.stringify(payload));
  }
}

function currentRoom(player) {
  if (!player.roomCode || !rooms.has(player.roomCode)) {
    send(player, { type: "error", message: "你还没有进入房间" });
    return null;
  }
  return rooms.get(player.roomCode);
}

function currentPlayer(room) {
  return room.players[room.turnIndex] || null;
}

function nextTurn(room) {
  room.turnIndex = (room.turnIndex + 1) % room.players.length;
}

function normalizeSeats(room) {
  room.players.forEach((player, index) => {
    player.seat = index;
  });
}

function cleanName(name) {
  const value = String(name || "").trim();
  return value.length > 0 ? value.slice(0, 16) : "玩家";
}

function createDeck() {
  const deck = [];
  for (const rank of RANKS.slice(0, 13)) {
    for (const suit of SUITS) {
      deck.push(`${rank}${suit}`);
    }
  }
  deck.push("XR", "DB");
  return deck;
}

function shuffle(cards) {
  const deck = [...cards];
  for (let i = deck.length - 1; i > 0; i -= 1) {
    const j = Math.floor(Math.random() * (i + 1));
    [deck[i], deck[j]] = [deck[j], deck[i]];
  }
  return deck;
}

function sortCards(cards) {
  cards.sort(compareCards);
}

function compareCards(left, right) {
  const delta = cardValue(left) - cardValue(right);
  return delta === 0 ? left.localeCompare(right) : delta;
}

function cardValue(card) {
  return RANKS.indexOf(card[0]);
}

function ownsCards(hand, cards) {
  const copy = [...hand];
  for (const card of cards) {
    const index = copy.indexOf(card);
    if (index === -1) {
      return false;
    }
    copy.splice(index, 1);
  }
  return true;
}

function removeCards(hand, cards) {
  for (const card of cards) {
    const index = hand.indexOf(card);
    if (index !== -1) {
      hand.splice(index, 1);
    }
  }
}

function smallestCard(hand) {
  return [...hand].sort(compareCards)[0];
}

function groupByValue(cards) {
  const map = new Map();
  for (const card of cards) {
    const value = cardValue(card);
    if (!map.has(value)) {
      map.set(value, []);
    }
    map.get(value).push(card);
  }
  return [...map.entries()]
    .map(([value, groupCards]) => ({ value, cards: groupCards.sort(compareCards), count: groupCards.length }))
    .sort((left, right) => left.value - right.value);
}

function analyze(cards) {
  const sorted = [...cards].sort(compareCards);
  const length = sorted.length;
  const groups = groupByValue(sorted);
  const counts = groups.map((group) => group.count).sort((a, b) => b - a);

  if (length === 2 && sorted.includes("XR") && sorted.includes("DB")) {
    return { type: "rocket", main: 99, length };
  }
  if (length === 4 && counts[0] === 4) {
    return { type: "bomb", main: groups[0].value, length };
  }
  if (length === 1) {
    return { type: "single", main: groups[0].value, length };
  }
  if (length === 2 && counts[0] === 2) {
    return { type: "pair", main: groups[0].value, length };
  }
  if (length === 3 && counts[0] === 3) {
    return { type: "triple", main: groups[0].value, length };
  }
  if (length === 4 && counts[0] === 3) {
    return { type: "tripleSingle", main: groups.find((group) => group.count === 3).value, length };
  }
  if (length === 5 && counts[0] === 3 && counts[1] === 2) {
    return { type: "triplePair", main: groups.find((group) => group.count === 3).value, length };
  }
  if (isStraight(groups, 1, 5)) {
    return { type: "straight", main: groups[groups.length - 1].value, length };
  }
  if (length >= 6 && length % 2 === 0 && isStraight(groups, 2, 3)) {
    return { type: "pairStraight", main: groups[groups.length - 1].value, length };
  }

  const airplane = analyzeAirplane(groups, length);
  if (airplane) {
    return airplane;
  }
  return null;
}

function analyzeAirplane(groups, length) {
  const tripleGroups = groups.filter((group) => group.count === 3);
  for (let start = 0; start < tripleGroups.length; start += 1) {
    for (let end = start + 1; end < tripleGroups.length; end += 1) {
      const seq = tripleGroups.slice(start, end + 1);
      if (!isConsecutive(seq) || seq.some((group) => group.value >= 12)) {
        continue;
      }
      const wings = length - seq.length * 3;
      if (wings === 0) {
        return { type: "airplane", main: seq[seq.length - 1].value, length, wings: "none", units: seq.length };
      }
      if (wings === seq.length) {
        return { type: "airplaneSingle", main: seq[seq.length - 1].value, length, wings: "single", units: seq.length };
      }
      if (wings === seq.length * 2 && groups.filter((group) => !seq.includes(group)).every((group) => group.count === 2)) {
        return { type: "airplanePair", main: seq[seq.length - 1].value, length, wings: "pair", units: seq.length };
      }
    }
  }
  return null;
}

function isStraight(groups, count, minGroups) {
  if (groups.length < minGroups) {
    return false;
  }
  if (groups.some((group) => group.count !== count || group.value >= 12)) {
    return false;
  }
  return isConsecutive(groups);
}

function isConsecutive(groups) {
  for (let i = 1; i < groups.length; i += 1) {
    if (groups[i].value !== groups[i - 1].value + 1) {
      return false;
    }
  }
  return true;
}

function beats(candidate, previous) {
  if (candidate.type === "rocket") {
    return previous.type !== "rocket";
  }
  if (previous.type === "rocket") {
    return false;
  }
  if (candidate.type === "bomb" && previous.type !== "bomb") {
    return true;
  }
  if (candidate.type !== previous.type || candidate.length !== previous.length) {
    return false;
  }
  if (candidate.units && previous.units && candidate.units !== previous.units) {
    return false;
  }
  return candidate.main > previous.main;
}
