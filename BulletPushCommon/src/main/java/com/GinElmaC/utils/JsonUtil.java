package com.GinElmaC.utils;

import com.google.gson.Gson;

public class JsonUtil {
    private static final Gson gson = new Gson();

    public static <T> T fromJson(String json,Class<T> tClass){
        return gson.fromJson(json,tClass);
    }

    public static String toJson(Object o){
        return gson.toJson(o);
    }
}
