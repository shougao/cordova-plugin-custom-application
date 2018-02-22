package com.example.plugin;

import com.google.gson.annotations.SerializedName;

import java.util.Date;

public class Post {
    @SerializedName("id")
    long ID;

    //Gson解析的时候就会将data对应的值赋值到dateCreated属性上, 为了让名字能对应上，又具有可读性
    @SerializedName("date")
    Date dateCreated;

    String title;
    String author;
    String url;
    String body;
}
