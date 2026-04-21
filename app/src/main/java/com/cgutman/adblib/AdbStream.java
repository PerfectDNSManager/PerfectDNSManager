package com.cgutman.adblib;

import java.io.Closeable;
import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/* loaded from: classes.dex */
public class AdbStream implements Closeable {
    private AdbConnection adbConn;
    private int localId;
    private int remoteId;
    private Queue<byte[]> readQueue = new ConcurrentLinkedQueue<byte[]>();
    private AtomicBoolean writeReady = new AtomicBoolean(false);
    private boolean isClosed = false;

    public AdbStream(AdbConnection adbConnection, int i) {
        this.adbConn = adbConnection;
        this.localId = i;
    }

    void addPayload(byte[] bArr) {
        synchronized (this.readQueue) {
            this.readQueue.add(bArr);
            this.readQueue.notifyAll();
        }
    }

    void sendReady() throws IOException {
        this.adbConn.outputStream.write(AdbProtocol.generateReady(this.localId, this.remoteId));
        this.adbConn.outputStream.flush();
    }

    void updateRemoteId(int i) {
        this.remoteId = i;
    }

    void readyForWrite() {
        this.writeReady.set(true);
    }

    void notifyClose() {
        this.isClosed = true;
        synchronized (this) {
            notifyAll();
        }
        synchronized (this.readQueue) {
            this.readQueue.notifyAll();
        }
    }

    public byte[] read() throws InterruptedException, IOException {
        byte[] bArrPoll;
        synchronized (this.readQueue) {
            bArrPoll = null;
            while (!this.isClosed && (bArrPoll = this.readQueue.poll()) == null) {
                this.readQueue.wait();
            }
            if (this.isClosed) {
                throw new IOException("Stream closed");
            }
        }
        return bArrPoll;
    }

    public void write(String str) throws InterruptedException, IOException {
        write(str.getBytes("UTF-8"), false);
        write(new byte[1], true);
    }

    public void write(byte[] bArr) throws InterruptedException, IOException {
        write(bArr, true);
    }

    public void write(byte[] bArr, boolean z) throws InterruptedException, IOException {
        synchronized (this) {
            while (!this.isClosed && !this.writeReady.compareAndSet(true, false)) {
                wait();
            }
            if (this.isClosed) {
                throw new IOException("Stream closed");
            }
        }
        this.adbConn.outputStream.write(AdbProtocol.generateWrite(this.localId, this.remoteId, bArr));
        if (z) {
            this.adbConn.outputStream.flush();
        }
    }

    @Override // java.io.Closeable, java.lang.AutoCloseable
    public void close() throws IOException {
        synchronized (this) {
            if (this.isClosed) {
                return;
            }
            notifyClose();
            this.adbConn.outputStream.write(AdbProtocol.generateClose(this.localId, this.remoteId));
            this.adbConn.outputStream.flush();
        }
    }

    public boolean isClosed() {
        return this.isClosed;
    }
}
