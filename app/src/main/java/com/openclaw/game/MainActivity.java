package com.openclaw.game;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
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

    private final GameClient client = new GameClient();
    private final Set<String> selectedCards = new HashSet<>();
    private final Handler handler = new Handler();

    private SharedPreferences preferences;
    private JSONObject snapshot;
    private boolean connected;
    private String activeScreen = "home";
    private String playerId = "";
    private String lastNotice = "";

    private EditText nameInput;
    private EditText serverInput;
    private EditText roomCodeInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        preferences = getSharedPreferences("openclaw", MODE_PRIVATE);
        renderHome();
    }

    @Override
    protected void onDestroy() {
        client.close();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
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
        LinearLayout root = landscapeShell();
        root.addView(sideBrand("OpenClaw", "横屏联机游戏平台", "斗地主已开放"));

        LinearLayout content = contentArea();
        LinearLayout connection = glassPanel();
        connection.addView(title("连接牌桌", 22, WHITE));
        connection.addView(caption("先连服务器，再进入斗地主。模拟器默认地址已经填好。", CREAM));
        nameInput = edit("玩家昵称", preferences.getString("name", "玩家" + (System.currentTimeMillis() % 1000)));
        serverInput = edit("服务器地址", preferences.getString("server", DEFAULT_SERVER));
        connection.addView(nameInput);
        connection.addView(serverInput);

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
        row.addView(connect, weightParams(1, dp(48)));
        Button offline = outlineButton("只看界面");
        offline.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                renderLobby();
            }
        });
        row.addView(offline, weightParams(1, dp(48)));
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
        games.addView(gameTile("斗地主", "横屏牌桌", true), weightParams(2, dp(142)));
        games.addView(gameTile("中国象棋", "预留", false), weightParams(1, dp(142)));
        games.addView(gameTile("广东麻将", "预留", false), weightParams(1, dp(142)));
        games.addView(gameTile("画我猜", "预留", false), weightParams(1, dp(142)));
        games.addView(gameTile("谁是卧底", "预留", false), weightParams(1, dp(142)));
        content.addView(games);

        if (lastNotice.length() > 0) {
            content.addView(toastStrip(lastNotice));
        }
        root.addView(content, weightParams(1, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private void renderLobby() {
        activeScreen = "lobby";
        LinearLayout root = landscapeShell();
        root.addView(sideBrand("斗地主", connected ? "已连接服务器" : "未连接服务器", connected ? "好友房 / 机器人测试" : "返回首页连接"));

        LinearLayout content = contentArea();
        LinearLayout top = row();
        top.addView(lobbyCard("创建房间", "生成 4 位房间号，邀请朋友加入", ORANGE, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendType("createRoom");
            }
        }), weightParams(1, dp(156)));
        top.addView(lobbyCard("快速加入", "自动进入未满 3 人的牌桌", BLUE, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendType("quickJoin");
            }
        }), weightParams(1, dp(156)));
        top.addView(lobbyCard("金币场", "自动配桌，立即开局", GOLD, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendType("goldMatch");
            }
        }), weightParams(1, dp(156)));
        content.addView(top);

        LinearLayout joinPanel = glassPanel();
        joinPanel.addView(title("加入指定房间", 20, WHITE));
        roomCodeInput = edit("输入 4 位房间号", "");
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
        buttons.addView(join, weightParams(1, dp(48)));
        Button back = outlineButton("返回平台");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                renderHome();
            }
        });
        buttons.addView(back, weightParams(1, dp(48)));
        joinPanel.addView(buttons);
        content.addView(joinPanel);

        if (!connected) {
            content.addView(toastStrip("还没连接服务器。回首页点“连接服务器”，地址填 ws://10.0.2.2:8080。"));
        }
        root.addView(content, weightParams(1, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private void renderGame() {
        activeScreen = "game";
        JSONObject room = snapshot == null ? null : snapshot.optJSONObject("room");
        if (room == null) {
            renderLobby();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackground(tableBackground());

        LinearLayout board = new LinearLayout(this);
        board.setOrientation(LinearLayout.VERTICAL);
        board.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.addView(board, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        board.addView(topSeats(room), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(88)));
        board.addView(centerTable(room), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        board.addView(myArea(room), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(142)));

        root.addView(floatingActions(room), floatingRightParams());

        setContentView(root);
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
        table.addView(bottom, new LinearLayout.LayoutParams(dp(360), dp(36)));

        JSONObject lastPlay = room.optJSONObject("lastPlay");
        LinearLayout last = new LinearLayout(this);
        last.setGravity(Gravity.CENTER);
        last.setOrientation(LinearLayout.VERTICAL);
        boolean ended = "ended".equals(room.optString("status"));
        LinearLayout.LayoutParams lastParams = new LinearLayout.LayoutParams(dp(ended ? 540 : 430), dp(ended ? 168 : 116));
        lastParams.setMargins(0, dp(10), 0, dp(10));
        last.setLayoutParams(lastParams);
        last.setBackground(round(Color.argb(120, 9, 43, 34), dp(14), Color.argb(150, 255, 230, 160), dp(1)));
        if (ended) {
            last.addView(title(room.optString("winnerName", "本局") + " 获胜", 25, WHITE));
            last.addView(settlementBoard(room.optJSONArray("lastSettlement")), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(92)));
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
        area.setPadding(dp(8), 0, dp(148), 0);

        LinearLayout info = row();
        JSONObject me = findPlayer(room.optJSONArray("players"), playerId);
        String myName = me == null ? "我的手牌" : me.optString("name", "我") + " 的手牌";
        TextView handTitle = title(myName, 18, CREAM);
        info.addView(handTitle, weightParams(1, dp(30)));
        TextView hint = caption(turnHint(room), GOLD);
        hint.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        info.addView(hint, weightParams(2, dp(30)));
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
        area.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(102)));
        return area;
    }

    private View floatingActions(JSONObject room) {
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(dp(8), dp(8), dp(8), dp(8));
        actions.setBackground(round(Color.argb(170, 13, 44, 39), dp(18), Color.argb(120, 255, 255, 255), dp(1)));

        String status = room.optString("status");
        boolean myTurn = playerId.length() > 0 && playerId.equals(room.optString("turnPlayerId"));

        if ("waiting".equals(status)) {
            actions.addView(sideButton("补机器人", GOLD, INK, new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    sendType("addBot");
                }
            }));
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
        LinearLayout.LayoutParams wrapParams = new LinearLayout.LayoutParams(dp(slotWidth), dp(100));
        wrapParams.setMargins(dp(1), 0, dp(1), 0);
        wrap.setLayoutParams(wrapParams);

        TextView view = new TextView(this);
        view.setText(CardUtil.label(card));
        view.setGravity(Gravity.CENTER);
        view.setTextSize(card.startsWith("X") || card.startsWith("D") ? 15 : 17);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(isRedCard(card) ? CARD_RED : INK);
        view.setBackground(round(selected ? Color.rgb(255, 243, 186) : WHITE, dp(8), selected ? GOLD : Color.rgb(211, 199, 178), dp(2)));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(dp(50), dp(72));
        cardParams.setMargins(0, selected ? 0 : dp(16), 0, selected ? dp(12) : 0);
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
        if (index == cardCount - 1) {
            return 54;
        }
        if (cardCount >= 20) {
            return 36;
        }
        if (cardCount >= 17) {
            return 40;
        }
        if (cardCount >= 14) {
            return 46;
        }
        return 54;
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

    private LinearLayout landscapeShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(14));
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
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(250), ViewGroup.LayoutParams.MATCH_PARENT);
        params.setMargins(0, 0, dp(14), 0);
        side.setLayoutParams(params);
        return side;
    }

    private LinearLayout contentArea() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(2), 0, 0, 0);
        return content;
    }

    private LinearLayout glassPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(14));
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
        tile.addView(title(title, enabled ? 26 : 19, enabled ? INK : Color.rgb(101, 120, 108)));
        tile.addView(caption(state, enabled ? ORANGE : Color.rgb(126, 142, 130)));
        if (enabled) {
            tile.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    renderLobby();
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
        edit.setFocusable(false);
        edit.setFocusableInTouchMode(false);
        edit.setCursorVisible(false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(45));
        params.setMargins(0, dp(8), 0, 0);
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
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        params.setMargins(0, dp(4), 0, dp(6));
        button.setLayoutParams(params);
        return button;
    }

    private Button button(String text, int color, int textColor, boolean wide) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(textColor);
        button.setGravity(Gravity.CENTER);
        button.setBackground(round(color, dp(12), Color.argb(120, 255, 255, 255), dp(1)));
        if (wide) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
            params.setMargins(0, dp(10), dp(8), 0);
            button.setLayoutParams(params);
        }
        return button;
    }

    private TextView title(String text, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setIncludeFontPadding(false);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private TextView caption(String text, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13);
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
        view.setTextSize(13);
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
        params.setMargins(dp(5), dp(5), dp(5), dp(5));
        return params;
    }

    private FrameLayout.LayoutParams floatingRightParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(132), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        params.setMargins(0, 0, dp(12), 0);
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

    private String statusText(JSONObject room) {
        String status = room.optString("status");
        if ("waiting".equals(status)) {
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
                } else if ("lobby".equals(activeScreen)) {
                    renderLobby();
                } else {
                    renderHome();
                }
            }
        });
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
