package com.tencent.iot.explorer.device.rtc.data_template.utils;

import android.text.TextUtils;

public class Utils {
    public static String getAvatarUrl(String userId) {
        if (TextUtils.isEmpty(userId)) {
            return null;
        }
        byte[] bytes = userId.getBytes();
        int    index = bytes[bytes.length - 1] % 10;
        String avatarName = "avatar" + index + "_100";
        return "https://imgcache.qq.com/qcloud/public/static/" + avatarName + ".20191230.png";
    }
}
