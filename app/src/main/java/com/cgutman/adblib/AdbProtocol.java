package com.cgutman.adblib;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/* loaded from: classes.dex */
public class AdbProtocol {
    public static final int ADB_HEADER_LENGTH = 24;
    public static final int AUTH_TYPE_RSA_PUBLIC = 3;
    public static final int AUTH_TYPE_SIGNATURE = 2;
    public static final int AUTH_TYPE_TOKEN = 1;
    public static final int CMD_AUTH = 1213486401;
    public static final int CMD_CLSE = 1163086915;
    public static final int CMD_CNXN = 1314410051;
    public static final int CMD_OKAY = 1497451343;
    public static final int CMD_OPEN = 1313165391;
    public static final int CMD_SYNC = 1129208147;
    public static final int CMD_WRTE = 1163154007;
    public static final int CONNECT_MAXDATA = 4096;
    public static byte[] CONNECT_PAYLOAD = null;
    public static final int CONNECT_VERSION = 16777216;

    static {
        try {
            CONNECT_PAYLOAD = "host::\u0000".getBytes("UTF-8");
        } catch (UnsupportedEncodingException unused) {
        }
    }

    private static int getPayloadChecksum(byte[] bArr) {
        int i = 0;
        for (int i2 : bArr) {
            if (i2 < 0) {
                i2 += 256;
            }
            i += i2;
        }
        return i;
    }

    public static boolean validateMessage(AdbMessage adbMessage) {
        if (adbMessage.command != (~adbMessage.magic)) {
            return false;
        }
        return adbMessage.payloadLength == 0 || getPayloadChecksum(adbMessage.payload) == adbMessage.checksum;
    }

    public static byte[] generateMessage(int i, int i2, int i3, byte[] bArr) {
        ByteBuffer byteBufferOrder;
        if (bArr != null) {
            byteBufferOrder = ByteBuffer.allocate(bArr.length + 24).order(ByteOrder.LITTLE_ENDIAN);
        } else {
            byteBufferOrder = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        }
        byteBufferOrder.putInt(i);
        byteBufferOrder.putInt(i2);
        byteBufferOrder.putInt(i3);
        if (bArr != null) {
            byteBufferOrder.putInt(bArr.length);
            byteBufferOrder.putInt(getPayloadChecksum(bArr));
        } else {
            byteBufferOrder.putInt(0);
            byteBufferOrder.putInt(0);
        }
        byteBufferOrder.putInt(~i);
        if (bArr != null) {
            byteBufferOrder.put(bArr);
        }
        return byteBufferOrder.array();
    }

    public static byte[] generateConnect() {
        return generateMessage(CMD_CNXN, 16777216, 4096, CONNECT_PAYLOAD);
    }

    public static byte[] generateAuth(int i, byte[] bArr) {
        return generateMessage(CMD_AUTH, i, 0, bArr);
    }

    public static byte[] generateOpen(int i, String str) throws UnsupportedEncodingException {
        ByteBuffer byteBufferAllocate = ByteBuffer.allocate(str.length() + 1);
        byteBufferAllocate.put(str.getBytes("UTF-8"));
        byteBufferAllocate.put((byte) 0);
        return generateMessage(CMD_OPEN, i, 0, byteBufferAllocate.array());
    }

    public static byte[] generateWrite(int i, int i2, byte[] bArr) {
        return generateMessage(CMD_WRTE, i, i2, bArr);
    }

    public static byte[] generateClose(int i, int i2) {
        return generateMessage(CMD_CLSE, i, i2, null);
    }

    public static byte[] generateReady(int i, int i2) {
        return generateMessage(CMD_OKAY, i, i2, null);
    }

    static final class AdbMessage {
        public int arg0;
        public int arg1;
        public int checksum;
        public int command;
        public int magic;
        public byte[] payload;
        public int payloadLength;

        AdbMessage() {
        }

        public static AdbMessage parseAdbMessage(InputStream inputStream) throws IOException {
            AdbMessage adbMessage = new AdbMessage();
            ByteBuffer byteBufferOrder = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
            int i = 0;
            int i2 = 0;
            do {
                int i3 = inputStream.read(byteBufferOrder.array(), i2, 24 - i2);
                if (i3 < 0) {
                    throw new IOException("Stream closed");
                }
                i2 += i3;
            } while (i2 < 24);
            adbMessage.command = byteBufferOrder.getInt();
            adbMessage.arg0 = byteBufferOrder.getInt();
            adbMessage.arg1 = byteBufferOrder.getInt();
            adbMessage.payloadLength = byteBufferOrder.getInt();
            adbMessage.checksum = byteBufferOrder.getInt();
            adbMessage.magic = byteBufferOrder.getInt();
            int i4 = adbMessage.payloadLength;
            if (i4 != 0) {
                adbMessage.payload = new byte[i4];
                do {
                    int i5 = inputStream.read(adbMessage.payload, i, adbMessage.payloadLength - i);
                    if (i5 < 0) {
                        throw new IOException("Stream closed");
                    }
                    i += i5;
                } while (i < adbMessage.payloadLength);
            }
            return adbMessage;
        }
    }
}
