package com.openclaw.game;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity implements GameClient.Listener {
    private static final String DEFAULT_SERVER = "ws://8.130.210.86:8080";

    private static final int FELT_DARK = Color.rgb(18, 82, 60);
    private static final int FELT = Color.rgb(31, 128, 88);
    private static final int FELT_LIGHT = Color.rgb(47, 157, 104);
    private static final int GOLD = Color.rgb(244, 183, 66);
    private static final int ORANGE = Color.rgb(224, 91, 32);
    private static final int BLUE = Color.rgb(39, 98, 171);
    private static final int CREAM = Color.rgb(255, 247, 224);
    private static final int INK = Color.rgb(43, 35, 28);
    private static final int WHITE = Color.WHITE;
    private static final int CARD_RED = Color.rgb(195, 41, 38);
    private static final int CHESS_WOOD = Color.rgb(184, 125, 57);
    private static final int CHESS_WOOD_LIGHT = Color.rgb(242, 204, 127);
    private static final int CHESS_RED = Color.rgb(185, 32, 31);
    private static final int CHESS_BLACK = Color.rgb(36, 36, 32);

    private final GameClient client = new GameClient();
    private final Set<String> selectedCards = new HashSet<>();
    private final Handler handler = new Handler();

    private SharedPreferences preferences;
    private JSONObject snapshot;
    private JSONObject chessSnapshot;
    private JSONObject pendingAfterConnectMessage;
    private boolean connected;
    private String activeScreen = "home";
    private String playerId = "";
    private String lastNotice = "";

    private EditText nameInput;
    private EditText serverInput;
    private EditText roomCodeInput;
    private EditText chessRoomCodeInput;
    private String[][] chessBoard;
    private final List<String[][]> chessHistory = new ArrayList<>();
    private final List<Boolean> chessTurnHistory = new ArrayList<>();
    private boolean chessRedTurn = true;
    private boolean chessEnded = false;
    private int selectedChessRow = -1;
    private int selectedChessCol = -1;
    private String chessNotice = "红方先行";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        enterImmersiveMode();
        preferences = getSharedPreferences("openclaw", MODE_PRIVATE);
        renderHome();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterImmersiveMode();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        enterImmersiveMode();
        redrawCurrentScreen();
    }

    @Override
    protected void onDestroy() {
        client.close();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if ("chess".equals(activeScreen)) {
            if (chessSnapshot != null) {
                sendChessType("leaveRoom");
                chessSnapshot = null;
                resetChineseChess();
            }
            renderHome();
            return;
        }
        if ("game".equals(activeScreen)) {
            renderLobby();
            return;
        }
        if ("lobby".equals(activeScreen)) {
            renderHome();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onConnected() {
        connected = true;
        lastNotice = "服务器已连接";
        JSONObject hello = new JSONObject();
        put(hello, "type", "hello");
        put(hello, "name", getPlayerName());
        put(hello, "resumeId", preferences.getString("playerId", ""));
        client.send(hello);
        if (pendingAfterConnectMessage != null) {
            final JSONObject pending = pendingAfterConnectMessage;
            pendingAfterConnectMessage = null;
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    client.send(pending);
                }
            }, 250);
            renderChineseChess();
            return;
        }
        renderLobby();
    }

    @Override
    public void onDisconnected(String reason) {
        connected = false;
        lastNotice = reason == null || reason.length() == 0 ? "连接已断开" : reason;
        redrawCurrentScreen();
    }

    @Override
    public void onMessage(JSONObject message) {
        String type = message.optString("type");
        if ("snapshot".equals(type)) {
            snapshot = message;
            playerId = message.optString("you", playerId);
            preferences.edit().putString("playerId", playerId).apply();
            selectedCards.clear();
            renderGame();
            return;
        }
        if ("chessSnapshot".equals(type)) {
            chessSnapshot = message;
            playerId = message.optString("you", playerId);
            preferences.edit().putString("playerId", playerId).apply();
            applyChineseChessSnapshot(message);
            renderChineseChess();
            return;
        }
        if ("notice".equals(type) || "error".equals(type)) {
            lastNotice = message.optString("message", "");
            if ("error".equals(type)) {
                Toast.makeText(this, lastNotice, Toast.LENGTH_SHORT).show();
            }
            redrawCurrentScreen();
        }
    }

    @Override
    public void onError(String error) {
        lastNotice = error;
        Toast.makeText(this, error, Toast.LENGTH_SHORT).show();
        redrawCurrentScreen();
    }

    private void renderHome() {
        activeScreen = "home";
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        LinearLayout root = landscapeShell();
        root.addView(sideBrand("OpenClaw", "横屏联机游戏平台", "斗地主 / 象棋已开放"));

        LinearLayout content = contentArea();
        LinearLayout connection = glassPanel();
        connection.addView(title("连接牌桌", 22, WHITE));
        connection.addView(caption("先连服务器，再进入斗地主。模拟器默认地址已经填好。", CREAM));
        nameInput = edit("玩家昵称", preferences.getString("name", "玩家" + (System.currentTimeMillis() % 1000)));
        serverInput = edit("服务器地址", preferences.getString("server", DEFAULT_SERVER));
        if (compactPhone()) {
            LinearLayout inputs = row();
            inputs.addView(nameInput, weightParams(1, editHeight()));
            inputs.addView(serverInput, weightParams(2, editHeight()));
            connection.addView(inputs);
        } else {
            connection.addView(nameInput);
            connection.addView(serverInput);
        }

        LinearLayout row = row();
        Button connect = actionButton(connected ? "进入大厅" : "连接服务器", ORANGE);
        connect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                saveConnectionInputs();
                if (connected) {
                    renderLobby();
                } else {
                    client.connect(serverInput.getText().toString().trim(), MainActivity.this);
                }
            }
        });
        row.addView(connect, weightParams(1, mainButtonHeight()));
        Button offline = outlineButton("只看界面");
        offline.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                renderLobby();
            }
        });
        row.addView(offline, weightParams(1, mainButtonHeight()));
        connection.addView(row);
        Button editServer = outlineButton("修改服务器地址");
        editServer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                serverInput.setFocusable(true);
                serverInput.setFocusableInTouchMode(true);
                serverInput.setCursorVisible(true);
                serverInput.requestFocus();
                Toast.makeText(MainActivity.this, "现在可以输入云服务器地址", Toast.LENGTH_SHORT).show();
            }
        });
        connection.addView(editServer);
        content.addView(connection);

        LinearLayout games = row();
        games.addView(gameTile("斗地主", "横屏牌桌", true), weightParams(2, gameTileHeight()));
        games.addView(gameTile("中国象棋", "竖屏棋桌", true), weightParams(1, gameTileHeight()));
        games.addView(gameTile("广东麻将", "预留", false), weightParams(1, gameTileHeight()));
        games.addView(gameTile("画我猜", "预留", false), weightParams(1, gameTileHeight()));
        games.addView(gameTile("谁是卧底", "预留", false), weightParams(1, gameTileHeight()));
        content.addView(games);

        if (lastNotice.length() > 0) {
            content.addView(toastStrip(lastNotice));
        }
        root.addView(scrollContent(content), weightParams(1, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private void renderLobby() {
        activeScreen = "lobby";
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        LinearLayout root = landscapeShell();
        root.addView(sideBrand("斗地主", connected ? "已连接服务器" : "未连接服务器", connected ? "好友房 / 机器人测试" : "返回首页连接"));

        LinearLayout content = contentArea();
        LinearLayout top = row();
        top.addView(lobbyCard("创建房间", "生成 4 位房间号，邀请朋友加入", ORANGE, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendType("createRoom");
            }
        }), weightParams(1, lobbyCardHeight()));
        top.addView(lobbyCard("快速加入", "自动进入未满 3 人的牌桌", BLUE, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendType("quickJoin");
            }
        }), weightParams(1, lobbyCardHeight()));
        top.addView(lobbyCard("金币场", "真人匹配，满 3 人开局", GOLD, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendType("goldMatch");
            }
        }), weightParams(1, lobbyCardHeight()));
        content.addView(top);

        LinearLayout joinPanel = glassPanel();
        joinPanel.addView(title("加入指定房间", 20, WHITE));
        roomCodeInput = edit("输入 4 位房间号", "");
        roomCodeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        joinPanel.addView(roomCodeInput);
        LinearLayout buttons = row();
        Button join = actionButton("加入房间", GOLD);
        join.setTextColor(INK);
        join.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                JSONObject message = new JSONObject();
                put(message, "type", "joinRoom");
                put(message, "code", roomCodeInput.getText().toString().trim());
                client.send(message);
            }
        });
        buttons.addView(join, weightParams(1, mainButtonHeight()));
        Button back = outlineButton("返回平台");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                renderHome();
            }
        });
        buttons.addView(back, weightParams(1, mainButtonHeight()));
        joinPanel.addView(buttons);
        content.addView(joinPanel);

        if (!connected) {
            content.addView(toastStrip("还没连接服务器。回首页点“连接服务器”，地址填 ws://10.0.2.2:8080。"));
        }
        root.addView(scrollContent(content), weightParams(1, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private void renderGame() {
        activeScreen = "game";
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        JSONObject room = snapshot == null ? null : snapshot.optJSONObject("room");
        if (room == null) {
            renderLobby();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackground(tableBackground());

        LinearLayout board = new LinearLayout(this);
        board.setOrientation(LinearLayout.VERTICAL);
        board.setPadding(gameEdgePadding(), gameTopPadding(), gameEdgePadding(), gameTopPadding());
        root.addView(board, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        board.addView(topSeats(room), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, topSeatHeight()));
        board.addView(centerTable(room), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        board.addView(myArea(room), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, myAreaHeight()));

        root.addView(floatingActions(room), floatingRightParams());

        setContentView(root);
    }

    private void renderChineseChess() {
        activeScreen = "chess";
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        if (chessBoard == null) {
            resetChineseChess();
        }
        final JSONObject chessRoom = chessSnapshot == null ? null : chessSnapshot.optJSONObject("room");
        final boolean online = chessRoom != null;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(10));
        root.setBackground(chessBackground());

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(12), dp(10), dp(12), dp(10));
        top.setBackground(round(Color.argb(180, 81, 44, 21), dp(14), Color.argb(130, 255, 226, 158), dp(1)));
        TextView header = title("中国象棋", 25, Color.rgb(255, 238, 196));
        header.setGravity(Gravity.CENTER);
        top.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));
        TextView state = smallPill(chessStatusText(chessRoom),
                Color.argb(215, 255, 238, 184), INK);
        LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34));
        stateParams.setMargins(0, dp(8), 0, 0);
        top.addView(state, stateParams);
        if (online) {
            LinearLayout onlineRow = row();
            onlineRow.setPadding(0, dp(8), 0, 0);
            if ("waiting".equals(chessRoom.optString("status"))) {
                Button bot = outlineButton("补人机");
                bot.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        sendChessType("addChessBot");
                    }
                });
                onlineRow.addView(bot, weightParams(1, chessActionHeight()));
            }
            Button leave = outlineButton("离开房间");
            leave.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    sendChessType("leaveRoom");
                    chessSnapshot = null;
                    resetChineseChess();
                    renderChineseChess();
                }
            });
            onlineRow.addView(leave, weightParams(1, chessActionHeight()));
            top.addView(onlineRow);
        } else {
            chessRoomCodeInput = edit("输入房间号", "");
            chessRoomCodeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
            top.addView(chessRoomCodeInput);
            LinearLayout onlineRow = row();
            Button create = actionButton("联机开房", ORANGE);
            create.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    sendChessType("createChessRoom");
                }
            });
            onlineRow.addView(create, weightParams(1, chessActionHeight()));
            Button join = outlineButton("加入房间");
            join.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    JSONObject message = new JSONObject();
                    put(message, "type", "joinChessRoom");
                    put(message, "code", chessRoomCodeInput.getText().toString().trim());
                    sendChessMessage(message);
                }
            });
            onlineRow.addView(join, weightParams(1, chessActionHeight()));
            top.addView(onlineRow);
        }
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ChineseChessBoardView chessView = new ChineseChessBoardView();
        LinearLayout.LayoutParams boardParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, chineseChessBoardHeight());
        boardParams.setMargins(0, dp(10), 0, dp(10));
        root.addView(chessView, boardParams);

        LinearLayout actions = row();
        actions.setPadding(0, 0, 0, 0);
        Button restart = actionButton("重开", ORANGE);
        restart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (online) {
                    sendChessType("chessRestart");
                } else {
                    resetChineseChess();
                    renderChineseChess();
                }
            }
        });
        actions.addView(restart, weightParams(1, chessActionHeight()));

        Button undo = outlineButton("悔棋");
        undo.setEnabled(online ? chessRoom.optInt("historyCount", 0) > 0 : !chessHistory.isEmpty());
        undo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (online) {
                    sendChessType("chessUndo");
                } else {
                    undoChineseChess();
                }
            }
        });
        actions.addView(undo, weightParams(1, chessActionHeight()));

        Button back = outlineButton("返回平台");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                renderHome();
            }
        });
        actions.addView(back, weightParams(1, chessActionHeight()));
        root.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, chessActionHeight() + dp(10)));

        setContentView(root);
    }

    private void resetChineseChess() {
        chessBoard = new String[10][9];
        String[] blackBack = {"車", "馬", "象", "士", "将", "士", "象", "馬", "車"};
        String[] redBack = {"车", "马", "相", "仕", "帅", "仕", "相", "马", "车"};
        for (int col = 0; col < 9; col++) {
            chessBoard[0][col] = blackBack[col];
            chessBoard[9][col] = redBack[col];
        }
        chessBoard[2][1] = "砲";
        chessBoard[2][7] = "砲";
        chessBoard[7][1] = "炮";
        chessBoard[7][7] = "炮";
        for (int col = 0; col < 9; col += 2) {
            chessBoard[3][col] = "卒";
            chessBoard[6][col] = "兵";
        }
        chessRedTurn = true;
        chessEnded = false;
        chessHistory.clear();
        chessTurnHistory.clear();
        selectedChessRow = -1;
        selectedChessCol = -1;
        chessNotice = "红方先行";
    }

    private void applyChineseChessSnapshot(JSONObject message) {
        JSONObject room = message.optJSONObject("room");
        JSONArray board = room == null ? null : room.optJSONArray("board");
        if (board == null) {
            return;
        }
        chessBoard = new String[10][9];
        for (int row = 0; row < 10; row++) {
            JSONArray line = board.optJSONArray(row);
            for (int col = 0; col < 9; col++) {
                if (line != null && !line.isNull(col)) {
                    String piece = line.optString(col);
                    chessBoard[row][col] = piece.length() == 0 ? null : piece;
                }
            }
        }
        chessRedTurn = room.optString("turnColor", "red").equals("red");
        chessEnded = "ended".equals(room.optString("status"));
        selectedChessRow = -1;
        selectedChessCol = -1;
        chessNotice = message.optString("message", chessNotice);
    }

    private String chessStatusText(JSONObject room) {
        if (room == null) {
            return chessEnded ? chessNotice : (chessRedTurn ? "红方回合" : "黑方回合") + "  ·  " + chessNotice;
        }
        String status = room.optString("status");
        String code = room.optString("code");
        if ("waiting".equals(status)) {
            int count = room.optJSONArray("players") == null ? 0 : room.optJSONArray("players").length();
            return "房间 " + code + "  ·  等待对手 " + count + "/2";
        }
        if ("ended".equals(status)) {
            return "房间 " + code + "  ·  " + room.optString("winnerName", "本局结束");
        }
        String side = room.optString("turnColor", "red").equals("red") ? "红方" : "黑方";
        String mine = playerId.equals(room.optString("turnPlayerId")) ? "轮到你" : "等待对手";
        return "房间 " + code + "  ·  " + side + "回合  ·  " + mine;
    }

    private boolean isMyOnlineChessPiece(String piece, JSONObject room) {
        boolean redPiece = isRedPiece(piece);
        String sidePlayerId = redPiece ? room.optString("redPlayerId") : room.optString("blackPlayerId");
        return playerId.equals(sidePlayerId);
    }

    private void sendChessType(String type) {
        JSONObject message = new JSONObject();
        put(message, "type", type);
        sendChessMessage(message);
    }

    private void sendChessMessage(JSONObject message) {
        if (connected) {
            client.send(message);
            return;
        }
        pendingAfterConnectMessage = message;
        String server = preferences.getString("server", DEFAULT_SERVER);
        client.connect(server, MainActivity.this);
        Toast.makeText(this, "正在连接服务器", Toast.LENGTH_SHORT).show();
    }

    private void handleChessTap(int row, int col) {
        if (chessSnapshot != null) {
            handleOnlineChessTap(row, col);
            return;
        }
        if (chessEnded || !insideBoard(row, col)) {
            return;
        }
        String piece = chessBoard[row][col];
        if (selectedChessRow < 0) {
            if (piece == null) {
                chessNotice = "请选择要走的棋子";
            } else if (isRedPiece(piece) != chessRedTurn) {
                chessNotice = chessRedTurn ? "现在轮到红方" : "现在轮到黑方";
            } else {
                selectedChessRow = row;
                selectedChessCol = col;
                chessNotice = "已选中 " + piece;
            }
            renderChineseChess();
            return;
        }

        String selected = chessBoard[selectedChessRow][selectedChessCol];
        if (piece != null && isRedPiece(piece) == chessRedTurn) {
            selectedChessRow = row;
            selectedChessCol = col;
            chessNotice = "已改选 " + piece;
            renderChineseChess();
            return;
        }

        if (!legalChineseChessMove(selectedChessRow, selectedChessCol, row, col)) {
            chessNotice = "这一步不符合象棋规则";
            renderChineseChess();
            return;
        }

        String captured = chessBoard[row][col];
        String[][] beforeBoard = copyChineseChessBoard(chessBoard);
        boolean beforeTurn = chessRedTurn;
        chessBoard[row][col] = selected;
        chessBoard[selectedChessRow][selectedChessCol] = null;
        if (generalsFaceEachOther()) {
            chessBoard[selectedChessRow][selectedChessCol] = selected;
            chessBoard[row][col] = captured;
            chessNotice = "将帅不能直接照面";
            renderChineseChess();
            return;
        }
        chessHistory.add(beforeBoard);
        chessTurnHistory.add(beforeTurn);
        selectedChessRow = -1;
        selectedChessCol = -1;
        if ("帅".equals(captured) || "将".equals(captured)) {
            chessEnded = true;
            chessNotice = (chessRedTurn ? "红方" : "黑方") + "获胜";
        } else {
            chessRedTurn = !chessRedTurn;
            chessNotice = captured == null ? "落子完成" : "吃掉 " + captured;
        }
        renderChineseChess();
    }

    private void handleOnlineChessTap(int row, int col) {
        JSONObject room = chessSnapshot == null ? null : chessSnapshot.optJSONObject("room");
        if (room == null || !"playing".equals(room.optString("status")) || !insideBoard(row, col)) {
            return;
        }
        if (!playerId.equals(room.optString("turnPlayerId"))) {
            chessNotice = "还没轮到你";
            renderChineseChess();
            return;
        }
        String piece = chessBoard[row][col];
        if (selectedChessRow < 0) {
            if (piece == null) {
                chessNotice = "请选择要走的棋子";
            } else if (!isMyOnlineChessPiece(piece, room)) {
                chessNotice = "请选择自己的棋子";
            } else {
                selectedChessRow = row;
                selectedChessCol = col;
                chessNotice = "已选中 " + piece;
            }
            renderChineseChess();
            return;
        }
        if (piece != null && isMyOnlineChessPiece(piece, room)) {
            selectedChessRow = row;
            selectedChessCol = col;
            chessNotice = "已改选 " + piece;
            renderChineseChess();
            return;
        }
        if (!legalChineseChessMove(selectedChessRow, selectedChessCol, row, col)) {
            chessNotice = "这一步不符合象棋规则";
            renderChineseChess();
            return;
        }
        JSONObject message = new JSONObject();
        put(message, "type", "chessMove");
        put(message, "fromRow", selectedChessRow);
        put(message, "fromCol", selectedChessCol);
        put(message, "toRow", row);
        put(message, "toCol", col);
        selectedChessRow = -1;
        selectedChessCol = -1;
        sendChessMessage(message);
    }

    private void undoChineseChess() {
        int last = chessHistory.size() - 1;
        if (last < 0) {
            chessNotice = "当前没有可悔棋的步数";
            renderChineseChess();
            return;
        }
        chessBoard = chessHistory.remove(last);
        chessRedTurn = chessTurnHistory.remove(last);
        chessEnded = false;
        selectedChessRow = -1;
        selectedChessCol = -1;
        chessNotice = "已悔棋，" + (chessRedTurn ? "红方" : "黑方") + "继续";
        renderChineseChess();
    }

    private String[][] copyChineseChessBoard(String[][] board) {
        String[][] copy = new String[10][9];
        for (int row = 0; row < 10; row++) {
            System.arraycopy(board[row], 0, copy[row], 0, 9);
        }
        return copy;
    }

    private boolean legalChineseChessMove(int fromRow, int fromCol, int toRow, int toCol) {
        if (!insideBoard(fromRow, fromCol) || !insideBoard(toRow, toCol)) {
            return false;
        }
        String piece = chessBoard[fromRow][fromCol];
        if (piece == null || fromRow == toRow && fromCol == toCol) {
            return false;
        }
        String target = chessBoard[toRow][toCol];
        boolean red = isRedPiece(piece);
        if (target != null && isRedPiece(target) == red) {
            return false;
        }
        int rowDelta = toRow - fromRow;
        int colDelta = toCol - fromCol;
        int absRow = Math.abs(rowDelta);
        int absCol = Math.abs(colDelta);

        if ("帅".equals(piece) || "将".equals(piece)) {
            if (fromCol == toCol && target != null && isGeneral(target) && clearStraight(fromRow, fromCol, toRow, toCol)) {
                return true;
            }
            return inPalace(toRow, toCol, red) && absRow + absCol == 1;
        }
        if ("仕".equals(piece) || "士".equals(piece)) {
            return inPalace(toRow, toCol, red) && absRow == 1 && absCol == 1;
        }
        if ("相".equals(piece) || "象".equals(piece)) {
            int eyeRow = (fromRow + toRow) / 2;
            int eyeCol = (fromCol + toCol) / 2;
            boolean sameSide = red ? toRow >= 5 : toRow <= 4;
            return sameSide && absRow == 2 && absCol == 2 && chessBoard[eyeRow][eyeCol] == null;
        }
        if ("马".equals(piece) || "馬".equals(piece)) {
            if (!((absRow == 2 && absCol == 1) || (absRow == 1 && absCol == 2))) {
                return false;
            }
            int legRow = fromRow + (absRow == 2 ? rowDelta / 2 : 0);
            int legCol = fromCol + (absCol == 2 ? colDelta / 2 : 0);
            return chessBoard[legRow][legCol] == null;
        }
        if ("车".equals(piece) || "車".equals(piece)) {
            return clearStraight(fromRow, fromCol, toRow, toCol);
        }
        if ("炮".equals(piece) || "砲".equals(piece)) {
            int screens = countBetween(fromRow, fromCol, toRow, toCol);
            return target == null ? screens == 0 : screens == 1;
        }
        if ("兵".equals(piece) || "卒".equals(piece)) {
            int forward = red ? -1 : 1;
            if (rowDelta == forward && colDelta == 0) {
                return true;
            }
            boolean crossedRiver = red ? fromRow <= 4 : fromRow >= 5;
            return crossedRiver && rowDelta == 0 && absCol == 1;
        }
        return false;
    }

    private boolean insideBoard(int row, int col) {
        return row >= 0 && row < 10 && col >= 0 && col < 9;
    }

    private boolean isRedPiece(String piece) {
        return "车马相仕帅炮兵".contains(piece);
    }

    private boolean isGeneral(String piece) {
        return "帅".equals(piece) || "将".equals(piece);
    }

    private boolean inPalace(int row, int col, boolean red) {
        return col >= 3 && col <= 5 && (red ? row >= 7 && row <= 9 : row >= 0 && row <= 2);
    }

    private boolean generalsFaceEachOther() {
        int redRow = -1;
        int redCol = -1;
        int blackRow = -1;
        int blackCol = -1;
        for (int row = 0; row < 10; row++) {
            for (int col = 0; col < 9; col++) {
                if ("帅".equals(chessBoard[row][col])) {
                    redRow = row;
                    redCol = col;
                } else if ("将".equals(chessBoard[row][col])) {
                    blackRow = row;
                    blackCol = col;
                }
            }
        }
        if (redCol < 0 || redCol != blackCol) {
            return false;
        }
        return countBetween(blackRow, blackCol, redRow, redCol) == 0;
    }

    private boolean clearStraight(int fromRow, int fromCol, int toRow, int toCol) {
        return countBetween(fromRow, fromCol, toRow, toCol) == 0;
    }

    private int countBetween(int fromRow, int fromCol, int toRow, int toCol) {
        if (fromRow != toRow && fromCol != toCol) {
            return -1;
        }
        int count = 0;
        int rowStep = Integer.compare(toRow, fromRow);
        int colStep = Integer.compare(toCol, fromCol);
        int row = fromRow + rowStep;
        int col = fromCol + colStep;
        while (row != toRow || col != toCol) {
            if (chessBoard[row][col] != null) {
                count += 1;
            }
            row += rowStep;
            col += colStep;
        }
        return count;
    }

    private View topSeats(JSONObject room) {
        LinearLayout top = row();
        JSONArray players = room.optJSONArray("players");
        JSONObject left = opponentAt(players, 0);
        JSONObject right = opponentAt(players, 1);
        top.addView(playerBadge(left, room), weightParams(1, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout center = new LinearLayout(this);
        center.setGravity(Gravity.CENTER);
        center.setOrientation(LinearLayout.VERTICAL);
        center.addView(title("房间 " + room.optString("code"), 22, CREAM));
        center.addView(caption(statusText(room), GOLD));
        center.addView(caption("倍数 x" + room.optInt("multiplier", 1), Color.rgb(207, 234, 216)));
        top.addView(center, weightParams(1, ViewGroup.LayoutParams.MATCH_PARENT));

        top.addView(playerBadge(right, room), weightParams(1, ViewGroup.LayoutParams.MATCH_PARENT));
        return top;
    }

    private View centerTable(JSONObject room) {
        LinearLayout table = new LinearLayout(this);
        table.setGravity(Gravity.CENTER);
        table.setOrientation(LinearLayout.VERTICAL);

        TextView bottom = smallPill("底牌  " + CardUtil.compactLabel(room.optJSONArray("bottom")), Color.argb(180, 17, 67, 56), CREAM);
        table.addView(bottom, new LinearLayout.LayoutParams(tablePillWidth(), compactHeight(dp(32), dp(36))));

        JSONObject lastPlay = room.optJSONObject("lastPlay");
        LinearLayout last = new LinearLayout(this);
        last.setGravity(Gravity.CENTER);
        last.setOrientation(LinearLayout.VERTICAL);
        boolean ended = "ended".equals(room.optString("status"));
        LinearLayout.LayoutParams lastParams = new LinearLayout.LayoutParams(tablePanelWidth(ended), tablePanelHeight(ended));
        lastParams.setMargins(0, compactHeight(dp(6), dp(10)), 0, compactHeight(dp(6), dp(10)));
        last.setLayoutParams(lastParams);
        last.setBackground(round(Color.argb(120, 9, 43, 34), dp(14), Color.argb(150, 255, 230, 160), dp(1)));
        if (ended) {
            last.addView(title(room.optString("winnerName", "本局") + " 获胜", 25, WHITE));
            last.addView(settlementBoard(room.optJSONArray("lastSettlement")), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, settlementHeight()));
        } else if (lastPlay == null) {
            last.addView(title("等待出牌", 25, Color.argb(220, 255, 255, 255)));
            last.addView(caption("轮到谁，谁先开牌", Color.argb(230, 255, 230, 170)));
        } else {
            last.addView(caption(lastPlay.optString("playerName") + " 出牌", GOLD));
            last.addView(title(CardUtil.compactLabel(lastPlay.optJSONArray("cards")), 25, WHITE));
        }
        table.addView(last);

        return table;
    }

    private View myArea(JSONObject room) {
        LinearLayout area = new LinearLayout(this);
        area.setOrientation(LinearLayout.VERTICAL);
        area.setPadding(dp(6), 0, actionPanelWidth() + actionPanelMargin() + dp(8), 0);

        LinearLayout info = row();
        JSONObject me = findPlayer(room.optJSONArray("players"), playerId);
        String myName = me == null ? "我的手牌" : me.optString("name", "我") + " 的手牌";
        TextView handTitle = title(myName, 18, CREAM);
        info.addView(handTitle, weightParams(1, infoRowHeight()));
        TextView hint = caption(turnHint(room), GOLD);
        hint.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        info.addView(hint, weightParams(2, infoRowHeight()));
        area.addView(info);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout hand = new LinearLayout(this);
        hand.setGravity(Gravity.BOTTOM | Gravity.CENTER_VERTICAL);
        hand.setOrientation(LinearLayout.HORIZONTAL);
        hand.setPadding(dp(6), 0, dp(6), 0);
        JSONArray handArray = snapshot == null ? null : snapshot.optJSONArray("hand");
        List<String> cards = CardUtil.jsonToCards(handArray);
        for (int i = 0; i < cards.size(); i++) {
            final String card = cards.get(i);
            hand.addView(cardView(card, selectedCards.contains(card), cards.size(), i));
        }
        scroll.addView(hand);
        area.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, handStripHeight()));
        return area;
    }

    private View floatingActions(JSONObject room) {
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(actionPanelInnerPadding(), actionPanelInnerPadding(), actionPanelInnerPadding(), actionPanelInnerPadding());
        actions.setBackground(round(Color.argb(170, 13, 44, 39), dp(18), Color.argb(120, 255, 255, 255), dp(1)));

        String status = room.optString("status");
        boolean myTurn = playerId.length() > 0 && playerId.equals(room.optString("turnPlayerId"));

        if ("waiting".equals(status)) {
            if ("gold".equals(room.optString("mode"))) {
                actions.addView(sideButton("等待真人", GOLD, INK, new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                    }
                }, false));
            } else {
                actions.addView(sideButton("补机器人", GOLD, INK, new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        sendType("addBot");
                    }
                }));
            }
        } else if ("bidding".equals(status)) {
            actions.addView(sideButton("叫地主", ORANGE, WHITE, new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    sendBid(true);
                }
            }, myTurn));
            actions.addView(sideButton("不叫", BLUE, WHITE, new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    sendBid(false);
                }
            }, myTurn));
        } else if ("playing".equals(status)) {
            final JSONArray suggestion = snapshot == null ? null : snapshot.optJSONArray("suggestion");
            actions.addView(sideButton("提示", GOLD, INK, new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    selectSuggestion(suggestion);
                }
            }, myTurn && suggestion != null && suggestion.length() > 0));
            actions.addView(sideButton("出牌", ORANGE, WHITE, new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    sendPlay();
                }
            }, myTurn && !selectedCards.isEmpty()));
            JSONObject lastPlay = room.optJSONObject("lastPlay");
            boolean canPass = myTurn && lastPlay != null && !playerId.equals(lastPlay.optString("playerId"));
            actions.addView(sideButton("过牌", BLUE, WHITE, new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    sendType("pass");
                }
            }, canPass));
            actions.addView(sideButton("重选", Color.rgb(73, 92, 105), WHITE, new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    selectedCards.clear();
                    renderGame();
                }
            }, !selectedCards.isEmpty()));
        } else if ("ended".equals(status)) {
            actions.addView(sideButton("再来", ORANGE, WHITE, new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    sendType("restart");
                }
            }));
        }

        if ("bidding".equals(status) || "playing".equals(status)) {
            final boolean autoPlay = isMeAutoPlay(room);
            actions.addView(sideButton(autoPlay ? "取消托管" : "托管", autoPlay ? GOLD : Color.rgb(73, 92, 105), autoPlay ? INK : WHITE, new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    sendToggleAuto(!autoPlay);
                }
            }));
        }

        actions.addView(sideButton("离开", Color.rgb(89, 71, 60), WHITE, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendType("leaveRoom");
                snapshot = null;
                renderLobby();
            }
        }));
        return actions;
    }

    private View cardView(final String card, boolean selected, int cardCount, int index) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setGravity(Gravity.BOTTOM);
        int slotWidth = cardSlotWidth(cardCount, index);
        LinearLayout.LayoutParams wrapParams = new LinearLayout.LayoutParams(slotWidth, handStripHeight());
        wrapParams.setMargins(dp(1), 0, dp(1), 0);
        wrap.setLayoutParams(wrapParams);

        TextView view = new TextView(this);
        view.setText(CardUtil.label(card));
        view.setGravity(Gravity.CENTER);
        view.setTextSize(cardTextSize(card));
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(isRedCard(card) ? CARD_RED : INK);
        view.setBackground(round(selected ? Color.rgb(255, 243, 186) : WHITE, dp(8), selected ? GOLD : Color.rgb(211, 199, 178), dp(2)));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(cardWidth(), cardHeight());
        cardParams.setMargins(0, selected ? 0 : cardDropOffset(), 0, selected ? selectedLiftOffset() : 0);
        wrap.addView(view, cardParams);
        wrap.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (selectedCards.contains(card)) {
                    selectedCards.remove(card);
                } else {
                    selectedCards.add(card);
                }
                renderGame();
            }
        });
        return wrap;
    }

    private int cardSlotWidth(int cardCount, int index) {
        if (cardCount <= 0) {
            return cardWidth();
        }
        if (index == cardCount - 1) {
            return cardWidth() + dp(4);
        }
        int usableWidth = Math.max(dp(280), screenWidthPx() - gameEdgePadding() * 2 - actionPanelWidth() - actionPanelMargin() - dp(28));
        int naturalOverlap = compactHeight(dp(28), dp(36));
        int fittedOverlap = (usableWidth - cardWidth() - dp(8)) / Math.max(1, cardCount - 1);
        int minOverlap = compactHeight(dp(20), dp(28));
        return clamp(Math.min(naturalOverlap, fittedOverlap), minOverlap, cardWidth() + dp(4));
    }

    private View playerBadge(JSONObject player, JSONObject room) {
        LinearLayout badge = new LinearLayout(this);
        badge.setGravity(Gravity.CENTER);
        badge.setOrientation(LinearLayout.HORIZONTAL);
        badge.setPadding(dp(10), dp(8), dp(10), dp(8));

        if (player == null) {
            badge.addView(smallPill("空座", Color.argb(120, 0, 0, 0), CREAM));
            return badge;
        }

        TextView avatar = new TextView(this);
        avatar.setText(player.optBoolean("bot") ? "AI" : "玩");
        avatar.setTextSize(17);
        avatar.setTypeface(Typeface.DEFAULT_BOLD);
        avatar.setGravity(Gravity.CENTER);
        avatar.setTextColor(INK);
        avatar.setBackground(round(player.optString("id").equals(room.optString("landlordId")) ? GOLD : CREAM, dp(22), Color.argb(150, 255, 255, 255), dp(1)));
        badge.addView(avatar, new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(8), 0, 0, 0);
        String name = player.optString("name");
        if (player.optString("id").equals(playerId)) {
            name = name + "  我";
        }
        if (player.optString("id").equals(room.optString("turnPlayerId"))) {
            name = name + "  出牌中";
        }
        if (player.optBoolean("disconnected")) {
            name = name + "  断线";
        } else if (player.optBoolean("autoPlay") && !player.optBoolean("bot")) {
            name = name + "  托管";
        }
        text.addView(title(name, 16, CREAM));
        String role = player.optString("id").equals(room.optString("landlordId")) ? "地主" : "农民";
        text.addView(caption(role + "  " + player.optInt("cardCount") + " 张  " + player.optInt("score") + " 分", GOLD));
        badge.addView(text);
        return badge;
    }

    private View settlementBoard(JSONArray settlement) {
        LinearLayout board = new LinearLayout(this);
        board.setOrientation(LinearLayout.HORIZONTAL);
        board.setGravity(Gravity.CENTER);
        board.setPadding(dp(8), dp(8), dp(8), 0);
        if (settlement == null || settlement.length() == 0) {
            board.addView(caption("等待下一局", GOLD));
            return board;
        }
        for (int i = 0; i < settlement.length(); i++) {
            JSONObject item = settlement.optJSONObject(i);
            if (item == null) {
                continue;
            }
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setBackground(round(Color.argb(95, 255, 255, 255), dp(10), Color.argb(95, 255, 232, 168), dp(1)));
            int change = item.optInt("change");
            String prefix = change >= 0 ? "+" : "";
            TextView name = title(item.optString("name"), 13, CREAM);
            name.setGravity(Gravity.CENTER);
            TextView score = title(prefix + change, 20, change >= 0 ? GOLD : Color.rgb(208, 224, 228));
            score.setGravity(Gravity.CENTER);
            TextView total = caption("总分 " + item.optInt("score"), Color.rgb(207, 234, 216));
            total.setGravity(Gravity.CENTER);
            cell.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));
            cell.addView(score, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));
            cell.addView(total, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(82), 1);
            params.setMargins(dp(4), 0, dp(4), 0);
            board.addView(cell, params);
        }
        return board;
    }

    private JSONObject findPlayer(JSONArray players, String id) {
        for (int i = 0; players != null && i < players.length(); i++) {
            JSONObject player = players.optJSONObject(i);
            if (player != null && id.equals(player.optString("id"))) {
                return player;
            }
        }
        return null;
    }

    private boolean isMeAutoPlay(JSONObject room) {
        JSONObject me = findPlayer(room.optJSONArray("players"), playerId);
        return me != null && me.optBoolean("autoPlay");
    }

    private JSONObject opponentAt(JSONArray players, int index) {
        int found = 0;
        for (int i = 0; players != null && i < players.length(); i++) {
            JSONObject player = players.optJSONObject(i);
            if (player == null || playerId.equals(player.optString("id"))) {
                continue;
            }
            if (found == index) {
                return player;
            }
            found += 1;
        }
        return null;
    }

    private class ChineseChessBoardView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        ChineseChessBoardView() {
            super(MainActivity.this);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            int height = getHeight();
            int cellX = Math.max(1, (int) (width / 9.15f));
            int cellY = Math.max(1, Math.min((int) (cellX * 1.26f), (int) (height / 9.32f)));
            int actualWidth = cellX * 8;
            int actualHeight = cellY * 9;
            int left = (width - actualWidth) / 2;
            int top = Math.max(dp(6), Math.min((height - actualHeight) / 2, dp(28)));
            int right = left + actualWidth;
            int bottom = top + actualHeight;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(231, 174, 90));
            RectF boardRect = new RectF(left - dp(11), top - dp(11), right + dp(11), bottom + dp(11));
            canvas.drawRoundRect(boardRect, dp(16), dp(16), paint);
            paint.setColor(Color.rgb(252, 218, 144));
            RectF innerRect = new RectF(left - dp(5), top - dp(5), right + dp(5), bottom + dp(5));
            canvas.drawRoundRect(innerRect, dp(10), dp(10), paint);

            drawChessGrid(canvas, left, top, cellX, cellY);
            drawSelectedPoint(canvas, left, top, cellX, cellY);
            drawChessPieces(canvas, left, top, cellX, cellY);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() != MotionEvent.ACTION_UP) {
                return true;
            }
            int width = getWidth();
            int height = getHeight();
            int cellX = Math.max(1, (int) (width / 9.15f));
            int cellY = Math.max(1, Math.min((int) (cellX * 1.26f), (int) (height / 9.32f)));
            int actualWidth = cellX * 8;
            int actualHeight = cellY * 9;
            int left = (width - actualWidth) / 2;
            int top = Math.max(dp(6), Math.min((height - actualHeight) / 2, dp(28)));
            int col = Math.round((event.getX() - left) / cellX);
            int row = Math.round((event.getY() - top) / cellY);
            handleChessTap(row, col);
            return true;
        }

        private void drawChessGrid(Canvas canvas, int left, int top, int cellX, int cellY) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(Color.rgb(89, 50, 25));
            for (int row = 0; row < 10; row++) {
                int y = top + row * cellY;
                canvas.drawLine(left, y, left + cellX * 8, y, paint);
            }
            for (int col = 0; col < 9; col++) {
                int x = left + col * cellX;
                canvas.drawLine(x, top, x, top + cellY * 4, paint);
                canvas.drawLine(x, top + cellY * 5, x, top + cellY * 9, paint);
            }
            canvas.drawLine(left + cellX * 3, top, left + cellX * 5, top + cellY * 2, paint);
            canvas.drawLine(left + cellX * 5, top, left + cellX * 3, top + cellY * 2, paint);
            canvas.drawLine(left + cellX * 3, top + cellY * 7, left + cellX * 5, top + cellY * 9, paint);
            canvas.drawLine(left + cellX * 5, top + cellY * 7, left + cellX * 3, top + cellY * 9, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(Math.max(dp(18), cellX * 0.36f));
            paint.setColor(Color.argb(160, 96, 52, 24));
            Paint.FontMetrics metrics = paint.getFontMetrics();
            float base = top + cellY * 4.5f - (metrics.ascent + metrics.descent) / 2;
            canvas.drawText("楚河", left + cellX * 2.2f, base, paint);
            canvas.drawText("汉界", left + cellX * 5.8f, base, paint);
        }

        private void drawSelectedPoint(Canvas canvas, int left, int top, int cellX, int cellY) {
            if (selectedChessRow < 0) {
                return;
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(120, 255, 236, 96));
            canvas.drawCircle(left + selectedChessCol * cellX, top + selectedChessRow * cellY, cellX * 0.48f, paint);
        }

        private void drawChessPieces(Canvas canvas, int left, int top, int cellX, int cellY) {
            for (int row = 0; row < 10; row++) {
                for (int col = 0; col < 9; col++) {
                    String piece = chessBoard == null ? null : chessBoard[row][col];
                    if (piece == null) {
                        continue;
                    }
                    drawPiece(canvas, piece, left + col * cellX, top + row * cellY, cellX);
                }
            }
        }

        private void drawPiece(Canvas canvas, String piece, int cx, int cy, int cell) {
            float radius = cell * 0.42f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(80, 30, 20, 10));
            canvas.drawCircle(cx + dp(2), cy + dp(3), radius, paint);
            paint.setColor(Color.rgb(255, 240, 198));
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(isRedPiece(piece) ? CHESS_RED : CHESS_BLACK);
            canvas.drawCircle(cx, cy, radius - dp(3), paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(Math.max(dp(23), cell * 0.48f));
            paint.setColor(isRedPiece(piece) ? CHESS_RED : CHESS_BLACK);
            Paint.FontMetrics metrics = paint.getFontMetrics();
            canvas.drawText(piece, cx, cy - (metrics.ascent + metrics.descent) / 2, paint);
        }
    }

    private LinearLayout landscapeShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(shellPadding(), shellPadding(), shellPadding(), shellPadding());
        root.setBackground(tableBackground());
        return root;
    }

    private LinearLayout sideBrand(String title, String subtitle, String tag) {
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(dp(18), dp(18), dp(18), dp(18));
        side.setBackground(round(Color.argb(185, 10, 45, 38), dp(18), Color.argb(100, 255, 255, 255), dp(1)));
        side.addView(title(title, 31, WHITE));
        side.addView(caption(subtitle, CREAM));
        TextView chip = smallPill(tag, Color.argb(210, 255, 236, 170), INK);
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
        chipParams.setMargins(0, dp(18), 0, 0);
        side.addView(chip, chipParams);
        side.addView(spacer(), new LinearLayout.LayoutParams(1, 0, 1));
        side.addView(caption("横屏牌桌 · 好友联机 · 机器人测试", Color.rgb(207, 234, 216)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(sideBrandWidth(), ViewGroup.LayoutParams.MATCH_PARENT);
        params.setMargins(0, 0, compactHeight(dp(8), dp(14)), 0);
        side.setLayoutParams(params);
        return side;
    }

    private LinearLayout contentArea() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(2), 0, 0, 0);
        return content;
    }

    private ScrollView scrollContent(LinearLayout content) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private LinearLayout glassPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(compactHeight(dp(12), dp(16)), compactHeight(dp(10), dp(14)), compactHeight(dp(12), dp(16)), compactHeight(dp(10), dp(14)));
        panel.setBackground(round(Color.argb(135, 6, 50, 43), dp(18), Color.argb(120, 255, 255, 255), dp(1)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(12));
        panel.setLayoutParams(params);
        return panel;
    }

    private View lobbyCard(String title, String subtitle, int color, View.OnClickListener listener) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(round(Color.argb(215, Color.red(color), Color.green(color), Color.blue(color)), dp(18), Color.argb(140, 255, 255, 255), dp(1)));
        card.addView(title(title, 26, WHITE));
        card.addView(caption(subtitle, CREAM));
        card.setOnClickListener(listener);
        card.setEnabled(connected);
        return card;
    }

    private View gameTile(String title, String state, boolean enabled) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(10), dp(10), dp(10), dp(10));
        tile.setBackground(round(enabled ? Color.argb(215, 255, 245, 213) : Color.argb(120, 230, 235, 226), dp(16), Color.argb(130, 255, 255, 255), dp(1)));
        TextView tileTitle = title(title, enabled ? ("中国象棋".equals(title) ? 22 : 26) : 19, enabled ? INK : Color.rgb(101, 120, 108));
        tileTitle.setSingleLine(true);
        tileTitle.setGravity(Gravity.CENTER);
        tile.addView(tileTitle);
        tile.addView(caption(state, enabled ? ORANGE : Color.rgb(126, 142, 130)));
        if (enabled) {
            tile.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if ("中国象棋".equals(title)) {
                        renderChineseChess();
                    } else {
                        renderLobby();
                    }
                }
            });
        }
        return tile;
    }

    private EditText edit(String hint, String value) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setText(value);
        edit.setSingleLine(true);
        edit.setTextSize(15);
        edit.setTextColor(INK);
        edit.setHintTextColor(Color.rgb(125, 113, 96));
        edit.setPadding(dp(12), 0, dp(12), 0);
        edit.setBackground(round(CREAM, dp(10), Color.rgb(218, 190, 136), dp(1)));
        edit.setFocusable(true);
        edit.setFocusableInTouchMode(true);
        edit.setCursorVisible(true);
        edit.setSelectAllOnFocus(false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, editHeight());
        params.setMargins(0, compactHeight(dp(5), dp(8)), 0, 0);
        edit.setLayoutParams(params);
        return edit;
    }

    private Button actionButton(String text, int color) {
        return button(text, color, WHITE, true);
    }

    private Button outlineButton(String text) {
        return button(text, Color.argb(60, 255, 255, 255), CREAM, true);
    }

    private Button sideButton(String text, int color, int textColor, View.OnClickListener listener) {
        return sideButton(text, color, textColor, listener, true);
    }

    private Button sideButton(String text, int color, int textColor, View.OnClickListener listener, boolean enabled) {
        Button button = button(text, enabled ? color : Color.rgb(94, 105, 100), textColor, false);
        button.setEnabled(enabled);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, sideButtonHeight());
        params.setMargins(0, dp(3), 0, compactHeight(dp(4), dp(6)));
        button.setLayoutParams(params);
        return button;
    }

    private Button button(String text, int color, int textColor, boolean wide) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(compactPhone() ? 14 : 15);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(textColor);
        button.setGravity(Gravity.CENTER);
        button.setBackground(round(color, dp(12), Color.argb(120, 255, 255, 255), dp(1)));
        if (wide) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, mainButtonHeight());
            params.setMargins(0, compactHeight(dp(6), dp(10)), compactHeight(dp(5), dp(8)), 0);
            button.setLayoutParams(params);
        }
        return button;
    }

    private TextView title(String text, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(adaptiveTextSize(sp));
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setIncludeFontPadding(false);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private TextView caption(String text, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(compactPhone() ? 12 : 13);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(0, dp(5), 0, 0);
        return view;
    }

    private TextView smallPill(String text, int background, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(compactPhone() ? 12 : 13);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setBackground(round(background, dp(17), Color.argb(80, 255, 255, 255), dp(1)));
        view.setPadding(dp(10), 0, dp(10), 0);
        return view;
    }

    private TextView toastStrip(String text) {
        TextView view = smallPill(text, Color.argb(210, 255, 239, 184), INK);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        params.setMargins(0, dp(4), 0, 0);
        view.setLayoutParams(params);
        return view;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private View spacer() {
        return new View(this);
    }

    private LinearLayout.LayoutParams weightParams(int weight, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, height, weight);
        int margin = compactHeight(dp(3), dp(5));
        params.setMargins(margin, margin, margin, margin);
        return params;
    }

    private FrameLayout.LayoutParams floatingRightParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(actionPanelWidth(), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        params.setMargins(0, 0, actionPanelMargin(), 0);
        return params;
    }

    private GradientDrawable round(int color, int radius, int stroke, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        drawable.setStroke(strokeWidth, stroke);
        return drawable;
    }

    private GradientDrawable tableBackground() {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{FELT_DARK, FELT, FELT_LIGHT});
        drawable.setDither(true);
        return drawable;
    }

    private GradientDrawable chessBackground() {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(91, 46, 24), CHESS_WOOD, CHESS_WOOD_LIGHT});
        drawable.setDither(true);
        return drawable;
    }

    private String statusText(JSONObject room) {
        String status = room.optString("status");
        if ("waiting".equals(status)) {
            if ("gold".equals(room.optString("mode"))) {
                int count = room.optJSONArray("players") == null ? 0 : room.optJSONArray("players").length();
                return "金币场等待真人 " + count + "/3";
            }
            return "等待 3 人开局";
        }
        if ("bidding".equals(status)) {
            return "叫地主阶段";
        }
        if ("playing".equals(status)) {
            return "对局中";
        }
        if ("ended".equals(status)) {
            return room.optString("winnerName", "本局结束") + " 获胜";
        }
        return status;
    }

    private String settlementText(JSONArray settlement) {
        if (settlement == null || settlement.length() == 0) {
            return "等待下一局";
        }
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < settlement.length(); i++) {
            JSONObject item = settlement.optJSONObject(i);
            if (item == null) {
                continue;
            }
            int change = item.optInt("change");
            String prefix = change >= 0 ? "+" : "";
            rows.add(item.optString("name") + " " + prefix + change);
        }
        return CardUtil.join(rows, "   ");
    }

    private String turnHint(JSONObject room) {
        if (!selectedCards.isEmpty()) {
            return "已选 " + selectedCards.size() + " 张";
        }
        if ("waiting".equals(room.optString("status"))) {
            if ("gold".equals(room.optString("mode"))) {
                return "等待真人玩家加入";
            }
            return "补机器人可立即测试";
        }
        if (playerId.equals(room.optString("turnPlayerId"))) {
            return "轮到你了";
        }
        return "等待对手";
    }

    private boolean isRedCard(String card) {
        return card.endsWith("H") || card.endsWith("D") || card.startsWith("D");
    }

    private void sendBid(boolean call) {
        JSONObject message = new JSONObject();
        put(message, "type", "bid");
        put(message, "call", call);
        client.send(message);
    }

    private void sendPlay() {
        List<String> cards = new ArrayList<>(selectedCards);
        if (cards.isEmpty()) {
            Toast.makeText(this, "先点选要出的牌", Toast.LENGTH_SHORT).show();
            return;
        }
        CardUtil.sort(cards);
        JSONObject message = new JSONObject();
        put(message, "type", "play");
        put(message, "cards", CardUtil.cardsToJson(cards));
        client.send(message);
    }

    private void selectSuggestion(JSONArray suggestion) {
        selectedCards.clear();
        for (int i = 0; suggestion != null && i < suggestion.length(); i++) {
            String card = suggestion.optString(i);
            if (card.length() > 0) {
                selectedCards.add(card);
            }
        }
        if (selectedCards.isEmpty()) {
            Toast.makeText(this, "当前没有合适的牌", Toast.LENGTH_SHORT).show();
        }
        renderGame();
    }

    private void sendType(String type) {
        JSONObject message = new JSONObject();
        put(message, "type", type);
        client.send(message);
    }

    private void sendToggleAuto(boolean enabled) {
        JSONObject message = new JSONObject();
        put(message, "type", "toggleAuto");
        put(message, "enabled", enabled);
        client.send(message);
    }

    private void put(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (Exception ignored) {
        }
    }

    private void saveConnectionInputs() {
        preferences.edit()
                .putString("name", nameInput.getText().toString().trim())
                .putString("server", serverInput.getText().toString().trim())
                .apply();
    }

    private String getPlayerName() {
        return preferences.getString("name", "玩家");
    }

    private void redrawCurrentScreen() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if ("game".equals(activeScreen) && snapshot != null) {
                    renderGame();
                } else if ("chess".equals(activeScreen)) {
                    renderChineseChess();
                } else if ("lobby".equals(activeScreen)) {
                    renderLobby();
                } else {
                    renderHome();
                }
            }
        });
    }

    private void enterImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private int screenWidthPx() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private int screenHeightPx() {
        return getResources().getDisplayMetrics().heightPixels;
    }

    private boolean compactPhone() {
        return Math.min(screenWidthPx(), screenHeightPx()) <= dp(460) || Math.max(screenWidthPx(), screenHeightPx()) <= dp(860);
    }

    private int compactHeight(int compactPx, int normalPx) {
        return compactPhone() ? compactPx : normalPx;
    }

    private int shellPadding() {
        return compactHeight(dp(8), dp(14));
    }

    private int sideBrandWidth() {
        return clamp(screenWidthPx() / 4, dp(176), dp(250));
    }

    private int mainButtonHeight() {
        return compactHeight(dp(36), dp(48));
    }

    private int editHeight() {
        return compactHeight(dp(38), dp(45));
    }

    private int lobbyCardHeight() {
        return clamp(screenHeightPx() / 4, dp(112), dp(156));
    }

    private int gameTileHeight() {
        return clamp(screenHeightPx() / 6, dp(64), dp(142));
    }

    private int gameEdgePadding() {
        return compactHeight(dp(6), dp(12));
    }

    private int gameTopPadding() {
        return compactHeight(dp(5), dp(8));
    }

    private int topSeatHeight() {
        return clamp(screenHeightPx() / 5, dp(84), dp(96));
    }

    private int myAreaHeight() {
        return clamp(screenHeightPx() / 4, dp(116), dp(154));
    }

    private int actionPanelWidth() {
        return clamp(screenWidthPx() / 8, dp(104), dp(136));
    }

    private int actionPanelMargin() {
        return compactHeight(dp(6), dp(12));
    }

    private int actionPanelInnerPadding() {
        return compactHeight(dp(6), dp(8));
    }

    private int sideButtonHeight() {
        return compactHeight(dp(38), dp(46));
    }

    private int tablePillWidth() {
        int available = screenWidthPx() - actionPanelWidth() - actionPanelMargin() - gameEdgePadding() * 2;
        return clamp((int) (available * 0.45f), dp(260), dp(380));
    }

    private int tablePanelWidth(boolean ended) {
        int available = screenWidthPx() - actionPanelWidth() - actionPanelMargin() - gameEdgePadding() * 2;
        float ratio = ended ? 0.64f : 0.52f;
        return clamp((int) (available * ratio), dp(320), dp(ended ? 560 : 450));
    }

    private int tablePanelHeight(boolean ended) {
        return ended
                ? clamp(screenHeightPx() / 4, dp(132), dp(168))
                : clamp(screenHeightPx() / 6, dp(86), dp(116));
    }

    private int settlementHeight() {
        return compactHeight(dp(78), dp(92));
    }

    private int chessTopHeight() {
        return clamp(screenHeightPx() / 9, dp(86), dp(112));
    }

    private int chessActionHeight() {
        return clamp(screenHeightPx() / 15, dp(46), dp(58));
    }

    private int chineseChessBoardHeight() {
        int shortest = Math.min(screenWidthPx(), screenHeightPx());
        int cellX = Math.max(1, (int) (shortest / 9.15f));
        int wanted = (int) (cellX * 9 * 1.26f) + dp(34);
        int max = Math.max(dp(620), screenHeightPx() - chessTopHeight() - chessActionHeight() - dp(56));
        return clamp(wanted, dp(560), max);
    }

    private int infoRowHeight() {
        return compactHeight(dp(26), dp(30));
    }

    private int handStripHeight() {
        int wanted = cardHeight() + cardDropOffset() + selectedLiftOffset() + dp(8);
        int max = Math.max(dp(76), myAreaHeight() - compactHeight(dp(32), dp(38)));
        return clamp(wanted, dp(78), max);
    }

    private int cardHeight() {
        return clamp(screenHeightPx() / 6, dp(62), dp(76));
    }

    private int cardWidth() {
        return clamp(cardHeight() * 50 / 72, dp(42), dp(52));
    }

    private int cardDropOffset() {
        return compactHeight(dp(8), dp(16));
    }

    private int selectedLiftOffset() {
        return compactHeight(dp(8), dp(12));
    }

    private float cardTextSize(String card) {
        if (card.startsWith("X") || card.startsWith("D")) {
            return compactPhone() ? 13 : 15;
        }
        return compactPhone() ? 15 : 17;
    }

    private int adaptiveTextSize(int sp) {
        if (!compactPhone()) {
            return sp;
        }
        if (sp >= 26) {
            return sp - 4;
        }
        if (sp >= 20) {
            return sp - 2;
        }
        return sp;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
