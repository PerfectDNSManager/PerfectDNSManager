package com.cgutman.adblib;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.HashMap;

public class AdbConnection implements Closeable {

    private Socket socket;
    private InputStream inputStream;
    OutputStream outputStream;
    private AdbCrypto crypto;

    private boolean connected;
    private boolean connectAttempted;
    private boolean sentSignature;
    private int maxData;

    private HashMap<Integer, AdbStream> openStreams = new HashMap<>();
    private int lastLocalId = 0;

    private Thread connectionThread;

    private AdbConnection() {
        this.connectionThread = createConnectionThread();
    }

    public static AdbConnection create(Socket socket, AdbCrypto crypto) throws IOException {
        AdbConnection conn = new AdbConnection();
        conn.crypto = crypto;
        conn.socket = socket;
        conn.inputStream = socket.getInputStream();
        conn.outputStream = socket.getOutputStream();
        socket.setTcpNoDelay(true);
        return conn;
    }

    private Thread createConnectionThread() {
        return new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (!Thread.interrupted() && socket != null && !socket.isClosed()) {
                        AdbProtocol.AdbMessage msg = AdbProtocol.AdbMessage.parseAdbMessage(inputStream);
                        if (msg == null) break;

                        switch (msg.command) {
                            case AdbProtocol.CMD_AUTH: {
                                byte[] packet;
                                if (msg.arg0 == AdbProtocol.AUTH_TYPE_TOKEN) {
                                    if (!sentSignature) {
                                        packet = AdbProtocol.generateAuth(AdbProtocol.AUTH_TYPE_SIGNATURE,
                                                crypto.signAdbTokenPayload(msg.payload));
                                        sentSignature = true;
                                    } else {
                                        packet = AdbProtocol.generateAuth(AdbProtocol.AUTH_TYPE_RSA_PUBLIC,
                                                crypto.getAdbPublicKeyPayload());
                                    }
                                    outputStream.write(packet);
                                    outputStream.flush();
                                }
                                break;
                            }

                            case AdbProtocol.CMD_CNXN: {
                                maxData = msg.arg1;
                                synchronized (AdbConnection.this) {
                                    connected = true;
                                    AdbConnection.this.notifyAll();
                                }
                                break;
                            }

                            case AdbProtocol.CMD_OKAY: {
                                AdbStream stream = openStreams.get(msg.arg1);
                                if (stream != null) {
                                    stream.updateRemoteId(msg.arg0);
                                    stream.readyForWrite();
                                    synchronized (stream) {
                                        stream.notifyAll();
                                    }
                                }
                                break;
                            }

                            case AdbProtocol.CMD_WRTE: {
                                AdbStream stream = openStreams.get(msg.arg1);
                                if (stream != null) {
                                    // Send OKAY acknowledgement
                                    outputStream.write(AdbProtocol.generateReady(msg.arg1, msg.arg0));
                                    outputStream.flush();
                                    stream.addPayload(msg.payload);
                                }
                                break;
                            }

                            case AdbProtocol.CMD_CLSE: {
                                AdbStream stream = openStreams.get(msg.arg1);
                                if (stream != null) {
                                    stream.notifyClose();
                                    openStreams.remove(msg.arg1);
                                }
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    // Connection lost
                } finally {
                    synchronized (AdbConnection.this) {
                        connected = false;
                        AdbConnection.this.notifyAll();
                    }
                    cleanupStreams();
                }
            }
        });
    }

    public void connect() throws InterruptedException, IOException {
        if (connected) {
            throw new IllegalStateException("Already connected");
        }
        outputStream.write(AdbProtocol.generateConnect());
        outputStream.flush();
        connectAttempted = true;
        connectionThread.start();
        synchronized (this) {
            if (!connected) {
                wait(10000); // 10s timeout
            }
            if (!connected) {
                throw new IOException("Connection failed");
            }
        }
    }

    public AdbStream open(String destination) throws InterruptedException, IOException {
        int localId = ++lastLocalId;
        if (!connectAttempted) {
            throw new IllegalStateException("connect() must be called first");
        }
        synchronized (this) {
            if (!connected) {
                wait(5000);
            }
            if (!connected) {
                throw new IOException("Connection failed");
            }
        }
        AdbStream stream = new AdbStream(this, localId);
        openStreams.put(localId, stream);
        outputStream.write(AdbProtocol.generateOpen(localId, destination));
        outputStream.flush();
        synchronized (stream) {
            stream.wait(5000);
        }
        if (stream.isClosed()) {
            throw new IOException("Stream open rejected by remote peer");
        }
        return stream;
    }

    public int getMaxData() throws InterruptedException, IOException {
        if (!connectAttempted) {
            throw new IllegalStateException("connect() must be called first");
        }
        synchronized (this) {
            if (!connected) {
                wait(5000);
            }
            if (!connected) {
                throw new IOException("Connection failed");
            }
        }
        return maxData;
    }

    private void cleanupStreams() {
        for (AdbStream stream : openStreams.values()) {
            try {
                stream.close();
            } catch (IOException e) {
                // ignore
            }
        }
        openStreams.clear();
    }

    @Override
    public void close() throws IOException {
        if (connectionThread == null) return;
        try {
            socket.close();
        } catch (IOException e) {
            // ignore
        }
        connectionThread.interrupt();
        try {
            connectionThread.join(2000);
        } catch (InterruptedException e) {
            // ignore
        }
    }
}
