package net.appstorefr.perfectdnsmanager.adblib;

import android.util.Base64;
import com.cgutman.adblib.AdbBase64;

/* loaded from: classes.dex */
public class AndroidBase64 implements AdbBase64 {
    @Override // com.cgutman.adblib.AdbBase64
    public String encodeToString(byte[] bArr) {
        return Base64.encodeToString(bArr, 2);
    }
}
