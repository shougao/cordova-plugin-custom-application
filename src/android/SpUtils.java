package com.example.plugin;

import android.content.Context;
import android.content.SharedPreferences;


public class SpUtils {

    private static final String TAG = "SpUtils";
    private static final String SPKEY = "shared_preference_key";
    private static final String VERSION_KEY = "version_key";
    public static final int DEFAULT_VERSION = 0;


    public static int getLocalVersion(Context applicationContext) {
        SharedPreferences sp = applicationContext.getSharedPreferences(SPKEY, Context.MODE_PRIVATE);
        return sp.getInt(VERSION_KEY, DEFAULT_VERSION);
    }

    public static void setLocalVersion(Context applicationContext, int versionNumber) {
        SharedPreferences sp = applicationContext.getSharedPreferences(SPKEY, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        editor.putInt(VERSION_KEY, versionNumber);
        editor.commit();
    }
}