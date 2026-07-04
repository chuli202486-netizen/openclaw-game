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

  send(player, { type: "notice", message: "欢迎来到 OpenClaw 斗地主服务器" });
});

console.log(`OpenClaw Dou Dizhu server listening on ws://0.0.0.0:${PORT}`);

function handleMessage(player, message) {
  switch (message.type) {
    case "hello":
      resumePlayer(player, message);
      break;
    case "createRoom":
      joinRoom(player, createRoom("friend"));
      break;
    case "quickJoin":
      joinRoom(player, findJoinableRoom("friend") || createRoom("friend"));
      break;
    case "goldMatch":
      goldMatch(player);
      break;
    case "joinRoom":
      joinRoomByCode(player, message.code);
      break;
    case "leaveRoom":
      leaveRoom(player, false);
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

function createRoom(mode = "friend") {
  let code = "";
  do {
    code = String(Math.floor(1000 + Math.random() * 9000));
  } while (rooms.has(code));

  const room = {
    code,
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
    lastSettlement: []
  };
  rooms.set(code, room);
  return room;
}

function findJoinableRoom(mode = "friend") {
  for (const room of rooms.values()) {
    if (room.mode === mode && room.status === "waiting" && room.players.length < 3) {
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

function goldMatch(player) {
  const room = findJoinableRoom("gold") || createRoom("gold");
  joinRoom(player, room);
  const joinedRoom = player.roomCode ? rooms.get(player.roomCode) : null;
  if (joinedRoom && joinedRoom.status === "waiting") {
    const need = 3 - joinedRoom.players.length;
    sendSnapshot(joinedRoom, need > 0 ? `金币场等待 ${need} 位真人玩家加入` : "金币场已配桌，准备开局");
  }
}

function joinRoom(player, room) {
  if (player.roomCode === room.code) {
    sendSnapshot(room);
    return;
  }
  if (room.players.length >= 3) {
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
  sendSnapshot(room, `${player.name} 加入了房间`);
  if (room.players.length === 3) {
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
