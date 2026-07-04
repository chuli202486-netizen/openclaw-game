package com.openclaw.game;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

final class GameClient {
    interface Listener {
        void onConnected();

        void onDisconnected(String reason);

        void onMessage(JSONObject message);

        void onError(String error);
    }

    private final OkHttpClient httpClient = new OkHttpClient();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Listener listener;
    private WebSocket socket;

    void connect(String url, Listener listener) {
        this.listener = listener;
        close();
        Request request = new Request.Builder().url(url).build();
        socket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                postConnected();
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    JSONObject message = new JSONObject(text);
                    postMessage(message);
                } catch (Exception error) {
                    postError("消息解析失败: " + error.getMessage());
                }
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(code, reason);
                postDisconnected(reason);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                postError("连接失败: " + error.getMessage());
                postDisconnected("连接断开");
            }
        });
    }

    void send(JSONObject message) {
        if (socket == null) {
            postError("还没有连接服务器");
            return;
        }
        socket.send(message.toString());
    }

    void close() {
        if (socket != null) {
            socket.close(1000, "client close");
            socket = null;
        }
    }

    private void postConnected() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onConnected();
                }
            }
        });
    }

    private void postDisconnected(final String reason) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onDisconnected(reason);
                }
            }
        });
    }

    private void postMessage(final JSONObject message) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onMessage(message);
                }
            }
        });
    }

    private void postError(final String error) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onError(error);
                }
            }
        });
    }
}
