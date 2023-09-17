package com.example.telegram_bot_chat_ai.constant;

import java.util.*;

public enum ButtonType {

    CALLBACK("callback"), URL("url");

    public String type;

    ButtonType(String type) {
        this.type = type;
    }

    private static Map<String, ButtonType> BUTTON_TYPE = new HashMap<>();

    static {
        for (ButtonType bt : values()) {
            BUTTON_TYPE.put(bt.type, bt);
        }
    }

    public static ButtonType getButtonTypeByString(String type) {
        return BUTTON_TYPE.get(type);
    }


}