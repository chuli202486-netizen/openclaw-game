package com.openclaw.game;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class CardUtil {
    private static final String ORDER = "3456789TJQKA2XD";

    private CardUtil() {
    }

    static List<String> jsonToCards(JSONArray array) {
        List<String> cards = new ArrayList<>();
        if (array == null) {
            return cards;
        }
        for (int i = 0; i < array.length(); i++) {
            cards.add(array.optString(i));
        }
        sort(cards);
        return cards;
    }

    static JSONArray cardsToJson(List<String> cards) {
        JSONArray array = new JSONArray();
        for (String card : cards) {
            array.put(card);
        }
        return array;
    }

    static void sort(List<String> cards) {
        Collections.sort(cards, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                int value = valueOf(a) - valueOf(b);
                if (value != 0) {
                    return value;
                }
                return suitOf(a).compareTo(suitOf(b));
            }
        });
    }

    static int valueOf(String card) {
        if (card == null || card.length() == 0) {
            return -1;
        }
        char rank = card.charAt(0);
        return ORDER.indexOf(rank);
    }

    static String label(String card) {
        if ("XR".equals(card)) {
            return "小王";
        }
        if ("DB".equals(card)) {
            return "大王";
        }
        if (card == null || card.length() < 2) {
            return card == null ? "" : card;
        }
        String rank = card.substring(0, 1);
        if ("T".equals(rank)) {
            rank = "10";
        }
        return rank + suitSymbol(card.substring(1));
    }

    static String compactLabel(JSONArray cards) {
        if (cards == null || cards.length() == 0) {
            return "无";
        }
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < cards.length(); i++) {
            labels.add(label(cards.optString(i)));
        }
        return join(labels, " ");
    }

    static String join(List<String> values, String separator) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(separator);
            }
            builder.append(values.get(i));
        }
        return builder.toString();
    }

    private static String suitOf(String card) {
        if (card == null || card.length() < 2) {
            return "";
        }
        return card.substring(1);
    }

    private static String suitSymbol(String suit) {
        if ("S".equals(suit)) {
            return "♠";
        }
        if ("H".equals(suit)) {
            return "♥";
        }
        if ("C".equals(suit)) {
            return "♣";
        }
        if ("D".equals(suit)) {
            return "♦";
        }
        return "";
    }
}
