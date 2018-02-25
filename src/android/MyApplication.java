package com.example.plugin;

import android.app.Application;
import android.app.DownloadManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Process;
import android.util.Log;

import com.android.volley.AuthFailureError;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


public class MyApplication extends Application implements Handler.Callback {
    public static final String TAG = "MyApplication";

    // business logic.
    private static final int HANDLE_MESSAGE_CHECK_VERSION = 100;
    private static final int HANDLE_MESSAGE_CHECK_VERSION_COMPLETE = 101;
    private static final int HANDLE_MESSAGE_DOWNLOAD = 102;
    private static final int HANDLE_MESSAGE_DOWNLOAD_COMPLETE = 103;

    // provider query event
    private static final int HANDLE_MESSAGE_DOWNLOAD_PREGRESS = 200;

    // ui event.
    private static final int HANDLE_UI_ONPROGRESS = 300;
    private static final int HANDLE_UI_ONCOMPLETE = 301;

    //
//    private static final int RECHECK_INTERVAL_MS = 1000 * 60 * 10; // 如果没网络，10分钟检查一次，直到有网络为止
    public static final int RECHECK_DELAY_MS = 1000 * 10;// delay cordova issues, todo zqc

    private Handler mWorkerHandler;
    private HandlerThread mWorkerHandlerThread;

    private DownloadChangeObserver mDownloadObserver;
    private ScheduledExecutorService scheduledExecutorService;

    // ====================network part.
    private static final String ENDPOINT = "https://kylewbanks.com/rest/posts.json";
    private RequestQueue mRequestQueue;
    private Gson mGson;

    private DownloadManager mDownloadManager;
    private DownLoadBroadcast mDownLoadBroadcast;
    private long mDownloadId;

    // update download progress.
    private Handler mUIHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            if (HANDLE_UI_ONPROGRESS == msg.what) {
                float onprogress = (float) msg.obj;
                // TODO: 2/25/18 progress js display.
            } else if (HANDLE_UI_ONCOMPLETE == msg.what) {
                // TODO: 2/25/18  complete is display
            }
        }
    };

    private BroadcastReceiver mWifiConnetedReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
            if (networkInfo != null) {
                if (networkInfo.isConnected()) {
                    mWorkerHandler.sendEmptyMessageDelayed(HANDLE_MESSAGE_CHECK_VERSION, RECHECK_DELAY_MS);
                }
            }
        }
    };

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate(), ThreadId= " + Thread.currentThread().getId());
        super.onCreate();
        initInternal();
    }

    private void initInternal() {

        SpUtil.init(getApplicationContext());
        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.setDateFormat("M/d/yy hh:mm a");
        mGson = gsonBuilder.create();

        mDownloadManager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);

        mWorkerHandlerThread = new HandlerThread(Constant.THREAD_NAME, Process.THREAD_PRIORITY_BACKGROUND);
        mWorkerHandlerThread.start();
        mWorkerHandler = new Handler(mWorkerHandlerThread.getLooper(), this);
        if (!mWorkerHandler.hasMessages(HANDLE_MESSAGE_CHECK_VERSION)) {
            mWorkerHandler.sendEmptyMessageDelayed(HANDLE_MESSAGE_CHECK_VERSION, RECHECK_DELAY_MS);
        }
    }

    @Override
    public boolean handleMessage(Message message) {
        switch (message.what) {
            case HANDLE_MESSAGE_CHECK_VERSION:
                if (isWifiConnected()) {
                    getRemoteNewVersion();
                } else {
                    startWifiListen();
                    return true;
                }
                break;

            case HANDLE_MESSAGE_CHECK_VERSION_COMPLETE:
                int newVersion = (int) message.obj;
                if (newVersion > localVersion()) {
                    Log.d(TAG, "start checkupdate, thread id=" + Thread.currentThread().getId());
                    mWorkerHandler.sendEmptyMessage(HANDLE_MESSAGE_DOWNLOAD);
                } else {
                    exitHandlerThread();
                }
                break;

            case HANDLE_MESSAGE_DOWNLOAD:
                LogUtil.d(TAG, "start download.");
                downloadFromServer("url");
                break;

            case HANDLE_MESSAGE_DOWNLOAD_PREGRESS:
                if ((boolean) message.obj) {
                    mUIHandler.sendMessage(mUIHandler.obtainMessage(HANDLE_UI_ONCOMPLETE));//提示可以安装。
                } else {
                    if (message.arg1 >= 0 && message.arg2 > 0) {//更新进度
                        mUIHandler.sendMessage(mUIHandler.obtainMessage(HANDLE_UI_ONPROGRESS, message.arg1 / (float) message.arg2));
                    }
                }
                break;

            case HANDLE_MESSAGE_DOWNLOAD_COMPLETE:
                boolean success = (Boolean) message.obj;
                if (success) {
                    // TODO: 2/25/18 1. 出发安装从js。 2. 如果没有安装，需要保存md5和安装文件， 用于下起提示用户，省去下载流程
                    exitHandlerThread();
                } else {
                    // TODO: 2/22/18 zqc
                    exitHandlerThread();
                }
                break;

            default:
                return true;
        }
        return true;
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        synchronized (this) {
            if (mWorkerHandler != null) {
                mWorkerHandler.removeCallbacksAndMessages(null);
                mWorkerHandler = null;
                Log.d(TAG, "stop handler, start checkupdate");
            }
            exitHandlerThread();
        }
        Log.d(TAG, "stop all. start checkupdate");
    }


    // TODO: 2/22/18 zqc, get from so.

    private int localVersion() {
        return SpUtil.getLocalVersion(getApplicationContext());
    }


    private boolean isWifiConnected() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Service.CONNECTIVITY_SERVICE);
        return connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI).isConnected();
    }

    private void startWifiListen() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(mWifiConnetedReceiver, filter);
    }

    /**
     * JSON 返回结果
     * <p>
     * 下载需要的全部信息，给download函数使用
     * <p>
     * <p>
     * 版本号：3.0
     * 下载链接
     * md5
     * 文件名
     * tar压缩包
     *
     * @return
     */
    private void getRemoteNewVersion() {
        mRequestQueue = Volley.newRequestQueue(getApplicationContext());
        // 不添加参数
        StringRequest request = new StringRequest(Request.Method.GET, ENDPOINT, onPostLoaded, onPostError);
        mRequestQueue.add(request);


        // 添加参数
        StringRequest customRequest = new StringRequest(Request.Method.GET, ENDPOINT, onPostLoaded, onPostError) {
            @Override
            protected Map<String, String> getParams() {
                Map<String, String> params = new HashMap<String, String>();
                params.put("user", "x"/*userAccount.getUsername()*/);
                params.put("pass", "x"/*userAccount.getPassword()*/);
                params.put("comment", "x"/*Uri.encode(comment)*/);
                params.put("comment_post_ID", String.valueOf(0/*postId*/));
                params.put("blogId", String.valueOf(0/*blogId*/));
                return params;
            }

            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                Map<String, String> params = new HashMap<String, String>();
                params.put("Content-Type", "application/x-www-form-urlencoded");
                return params;
            }
        };
        mRequestQueue.add(customRequest);


        // 添加json格式参数
        final String URL = "http://m.weather.com.cn/data/101010100.html";
        // Post params to be sent to the server
        HashMap<String, String> params = new HashMap<String, String>();
        params.put("token", "AbCdEfGh123456");

        JsonObjectRequest jsonObjectRequest = new JsonObjectRequest(URL, new JSONObject(params),
                new Response.Listener<JSONObject>() {
                    @Override
                    public void onResponse(JSONObject response) {

                        LogUtil.d(TAG, "zqc" + response.toString());
                    }
                }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {

                LogUtil.d(TAG, "zqc" + error.toString());
            }
        })/*{
            @Override
            protected Map<String,String> getParams() {
                // something to do here ??
                return params;
            }

            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                // something to do here ??
                return params;
            }
        }*/;
        mRequestQueue.add(jsonObjectRequest);
    }

    private Response.Listener<String> onPostLoaded = new Response.Listener<String>() {
        @Override
        public void onResponse(String response) {
            List<Post> posts = Arrays.asList(mGson.fromJson(response, Post[].class));

            Iterator<Post> iterator = posts.iterator();
            int itemNumbers = 0;
            while (iterator.hasNext()) {
                Post post = iterator.next();
                Log.d(TAG, "ID:" + post.ID + ", TITLE:" + post.title);
                itemNumbers++;
            }

            int fackedVersionNuber = 10;// TODO: 2/23/18 zqc
            Message msg = mWorkerHandler.obtainMessage(HANDLE_MESSAGE_CHECK_VERSION_COMPLETE, fackedVersionNuber);
            mWorkerHandler.sendMessage(msg);
        }
    };

    private Response.ErrorListener onPostError = new Response.ErrorListener() {
        @Override
        public void onErrorResponse(VolleyError error) {
            Log.e(TAG, error.toString());
        }
    };

    private void downloadFromServer(String url) {
        url = "http://www.zhaoshangdai.com/file/android.apk";

        mDownloadObserver = new DownloadChangeObserver();
        registerContentObserver();

        Uri uri = Uri.parse(url);
        DownloadManager.Request request = new DownloadManager.Request(uri);
        request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_MOBILE | DownloadManager.Request.NETWORK_WIFI);
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN);
        request.setDestinationInExternalFilesDir(getApplicationContext(), Environment.DIRECTORY_DOWNLOADS, "soupdate");
        request.setTitle("AndroidSoTitle");
        request.setDescription("click to open");

        mDownloadId = mDownloadManager.enqueue(request);
        registerDownloadCompleteBroadcast();
    }

    private void registerDownloadCompleteBroadcast() {
        /**注册service 广播 1.任务完成时 2.进行中的任务被点击*/
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
//        intentFilter.addAction(DownloadManager.ACTION_NOTIFICATION_CLICKED);
//        unregisterBroadcast();
        LogUtil.d(TAG, "register zqc");
        mDownLoadBroadcast = new DownLoadBroadcast();
        registerReceiver(mDownLoadBroadcast, intentFilter);
    }

    private void unregisterWifiListener() {
        if (mWifiConnetedReceiver != null) {
            unregisterReceiver(mWifiConnetedReceiver);
        }
    }

    /**
     * 注销广播
     */
    private void unregisterBroadcast() {
        if (mDownLoadBroadcast != null) {
            unregisterReceiver(mDownLoadBroadcast);
            mDownLoadBroadcast = null;
        }
    }

    /**
     * 注册ContentObserver
     */
    private void registerContentObserver() {
        /** observer download change **/
        if (mDownloadObserver != null) {
            getContentResolver().registerContentObserver(Uri.parse("content://downloads/my_downloads"), false, mDownloadObserver);
        }
    }

    /**
     * 注销ContentObserver
     */
    private void unregisterContentObserver() {
        if (mDownloadObserver != null) {
            getContentResolver().unregisterContentObserver(mDownloadObserver);
        }
    }


    private void exitHandlerThread() {

        unregisterWifiListener();
        unregisterBroadcast();
        unregisterContentObserver();

        if (mWorkerHandlerThread != null) {
            mWorkerHandlerThread.quit();
            mWorkerHandlerThread = null;

            Log.d(TAG, "stop thread, start checkupdate");
        }
    }

    /**
     * 接受下载完成广播
     */
    private class DownLoadBroadcast extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            long downId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            switch (intent.getAction()) {
                case DownloadManager.ACTION_DOWNLOAD_COMPLETE:
                    if (mDownloadId == downId && downId != -1 && mDownloadManager != null) {
                        Uri downIdUri = mDownloadManager.getUriForDownloadedFile(mDownloadId);
                        if (downIdUri != null) {
                            Log.i(TAG, "广播监听下载完成，APK存储路径为 ：" + downIdUri.getPath());
                            SpUtil.put(Constant.SP_DOWNLOAD_PATH, downIdUri.getPath());
//                            APPUtil.installApk(context, downIdUri);
                            mWorkerHandler.sendMessage(mWorkerHandler.obtainMessage(HANDLE_MESSAGE_DOWNLOAD_COMPLETE, "dummy_apk_path"));
                        }
                    }
                    break;
            }
        }
    }


    private class DownloadChangeObserver extends ContentObserver {

        public DownloadChangeObserver() {
            super(mWorkerHandler);
            scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        }

        /**
         * 当所监听的Uri发生改变时，就会回调此方法
         *
         * @param selfChange 此值意义不大, 一般情况下该回调值false
         */
        @Override
        public void onChange(boolean selfChange) {
            scheduledExecutorService.scheduleAtFixedRate(progressRunnable, 0, 1, TimeUnit.SECONDS);
        }
    }

    private Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            Log.d(TAG, "ui thread id =" + Thread.currentThread().getId());
            int[] bytesAndStatus = getBytesAndStatus(mDownloadId);
            mWorkerHandler.sendMessage(mUIHandler.obtainMessage(HANDLE_MESSAGE_DOWNLOAD_PREGRESS, bytesAndStatus[0], bytesAndStatus[1], bytesAndStatus[2] == DownloadManager.STATUS_SUCCESSFUL));
        }
    };


    /**
     * 通过query查询下载状态，包括已下载数据大小，总大小，下载状态
     *
     * @param downloadId
     * @return
     */
    private int[] getBytesAndStatus(long downloadId) {
        int[] bytesAndStatus = new int[]{
                -1, -1, 0
        };
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
        Cursor cursor = null;
        try {
            cursor = mDownloadManager.query(query);
            if (cursor != null && cursor.moveToFirst()) {
                //已经下载文件大小
                bytesAndStatus[0] = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                //下载文件的总大小
                bytesAndStatus[1] = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                //下载状态
                bytesAndStatus[2] = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return bytesAndStatus;
    }

}
