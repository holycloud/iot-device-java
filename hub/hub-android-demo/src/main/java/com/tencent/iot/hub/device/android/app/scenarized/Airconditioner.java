package com.tencent.iot.hub.device.android.app.scenarized;

import android.content.Context;
import android.text.TextUtils;

import com.tencent.iot.hub.device.android.app.BuildConfig;
import com.tencent.iot.hub.device.android.core.mqtt.TXMqttConnection;
import com.tencent.iot.hub.device.android.core.util.AsymcSslUtils;
import com.tencent.iot.hub.device.android.core.util.TXLog;
import com.tencent.iot.hub.device.java.core.mqtt.TXMqttActionCallBack;
import com.tencent.iot.hub.device.java.core.mqtt.TXMqttConstants;

import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;


public class Airconditioner {

    public static final String TAG = "iot.scenarized.Airconditioner";

    /**
     * 产品ID
     */
    private static final String PRODUCT_ID = BuildConfig.AIRCONDITIONER_PRODUCT_ID;

    /**
     * 设备名称
     */
    protected static final String DEVICE_NAME = BuildConfig.AIRCONDITIONER_DEVICE_NAME;

  
    /**
     * 密钥
     */
    private static final String SECRET_KEY = BuildConfig.AIRCONDITIONER_DEVICE_PSK;
    /**
     * 设备证书名
     */
    private static final String DEVICE_CERT_NAME = "YOUR_DEVICE_NAME_cert.crt";

    /**
     * 设备私钥文件名
     */
    private static final String DEVICE_KEY_NAME = "YOUR_DEVICE_NAME_private.key";

    private Context mContext;

    private TXMqttConnection mqttConnection;

    private MqttConnectOptions options;

    public Airconditioner(Context context, TXMqttActionCallBack callBack) {
        this.mContext = context;

        mqttConnection = new TXMqttConnection(mContext, PRODUCT_ID, DEVICE_NAME, SECRET_KEY, callBack);

        options = new MqttConnectOptions();
        options.setConnectionTimeout(8);
        options.setKeepAliveInterval(240);
        options.setAutomaticReconnect(true);
        if (TextUtils.isEmpty(SECRET_KEY)) {
            options.setSocketFactory(AsymcSslUtils.getSocketFactoryByAssetsFile(mContext, DEVICE_CERT_NAME, DEVICE_KEY_NAME));
        } else {
//            options.setSocketFactory(AsymcSslUtils.getSocketFactory());   如果您使用的是3.3.0及以下版本的 hub-device-android sdk，由于密钥认证默认配置的ssl://的url，请添加此句setSocketFactory配置
        }

        mqttConnection.connect(options, null);
        DisconnectedBufferOptions bufferOptions = new DisconnectedBufferOptions();
        bufferOptions.setBufferEnabled(true);
        bufferOptions.setBufferSize(1024);
        bufferOptions.setDeleteOldestMessages(true);
        mqttConnection.setBufferOpts(bufferOptions);
    }

    public void subScribeTopic() {
        TXLog.d(TAG, "subScribeTopic");
        String topic = String.format("%s/%s/%s", PRODUCT_ID, DEVICE_NAME, "control");
        mqttConnection.subscribe(topic, TXMqttConstants.QOS1, null);
    }

    public void closeConnection() {
        if (null != mqttConnection) {
            mqttConnection.disConnect(null);
        }
    }
}
