package com.example.plugin;

import android.app.Application;
import android.util.Log;

public class MyApplication extends Application
{
    public static final String TAG = "MyApplication";

    @Override
    public void onCreate()
    {
        Log.d(TAG, "onCreate()");
        // DO SOME STUFF
        super.onCreate();
    }
}