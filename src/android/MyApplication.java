package com.example.plugin;

import android.app.Application;
import android.app.DownloadManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Process;
import android.util.Log;


import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;


public class MyApplication extends Application implements Handler.Callback {
    public static final String TAG = "MyApplication";

    public static final int HANDLE_MESSAGE_CHECK_VERSION = 100;
    public static final int HANDLE_MESSAGE_CHECK_VERSION_COMPLETE = 101;

    public static final int HANDLE_MESSAGE_DOWNLOAD_SO = 102;
    public static final int HANDLE_MESSAGE_DOWNLOAD_COMPLETE = 103;

//
//    private static final int RECHECK_INTERVAL_MS = 1000 * 60 * 10; // 如果没网络，10分钟检查一次，直到有网络为止
    public static final int RECHECK_DELAY_MS = 1000 * 10;// delay cordova issues, todo zqc


    private Handler mWorkerHandler;
    private HandlerThread mWorkerHandlerThread;

    // ====================network part.
    private static final String ENDPOINT = "https://kylewbanks.com/rest/posts.json";
    private RequestQueue mRequestQueue;
    private Gson mGson;

    private DownloadManager mDownloadManager;
    private DownLoadBroadcast mDownLoadBroadcast;
    private long mDownloadId;


    private BroadcastReceiver mWifiReceiver = new BroadcastReceiver() {

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
        // DO SOME STUFF
        super.onCreate();

        initInternal();
    }

    private void initInternal() {

        SpUtil.init(getApplicationContext());
        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.setDateFormat("M/d/yy hh:mm a");
        mGson = gsonBuilder.create();

        mDownloadManager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);

        mWorkerHandlerThread = new HandlerThread("soupdate", Process.THREAD_PRIORITY_BACKGROUND);
        mWorkerHandlerThread.start();
        mWorkerHandler = new Handler(mWorkerHandlerThread.getLooper(), this);
        if(!mWorkerHandler.hasMessages(HANDLE_MESSAGE_CHECK_VERSION)) {
            mWorkerHandler.sendEmptyMessageDelayed(HANDLE_MESSAGE_CHECK_VERSION, RECHECK_DELAY_MS);
        }
    }


    @Override
    public boolean handleMessage(Message message) {
        switch (message.what) {
            case HANDLE_MESSAGE_CHECK_VERSION:
                if (true || isWifiConnected()) { // todo true is for debug.
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
                    (new Exception(TAG)).printStackTrace();
                    mWorkerHandler.sendEmptyMessage(HANDLE_MESSAGE_DOWNLOAD_SO);
                } else {
                    exitHandlerThread();
                }
                break;

            case HANDLE_MESSAGE_DOWNLOAD_SO:
                LogUtil.d(TAG, "start download.");
                downloadFromServer();
                break;

            case HANDLE_MESSAGE_DOWNLOAD_COMPLETE:
                boolean success = (Boolean) message.obj;
                if (success) {
                    if (mWifiReceiver != null) {
                        unregisterReceiver(mWifiReceiver);
                    }
                } else {
                    // TODO: 2/22/18 zqc
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

    private void exitHandlerThread() {

        if (mWorkerHandlerThread != null) {
            mWorkerHandlerThread.quit();
            mWorkerHandlerThread = null;

            Log.d(TAG, "stop thread, start checkupdate");
        }
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
        filter.addAction("android.net.conn.CONNECTIVITY_CHANGE");
        registerReceiver(mWifiReceiver, filter);
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
        StringRequest request = new StringRequest(Request.Method.GET, ENDPOINT, onPostLoaded, onPostError);
        mRequestQueue.add(request);
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

    private void downloadFromServer() {
        String url = "https://cdn2.jianshu.io/assets/web/nav-logo-4c7bbafe27adc892f3046e6978459bac.png";
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
                            // TODO: 2/23/18 change so. zqc
                        }
                    }
                    break;
            }
        }
    }

}
