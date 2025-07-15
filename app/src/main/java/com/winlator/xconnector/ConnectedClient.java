package com.winlator.xconnector;

/* loaded from: classes.dex */
public class ConnectedClient {
    public final int fd;
    private NewXInputStream inputStream;
    protected final long nativePtr;
    private NewXOutputStream outputStream;
    private Object tag;

    public ConnectedClient(long nativePtr, int fd) {
        this.nativePtr = nativePtr;
        this.fd = fd;
    }

    public void createInputStream(int initialInputBufferCapacity) {
        if (this.inputStream == null && initialInputBufferCapacity > 0) {
            this.inputStream = new NewXInputStream(this.fd, initialInputBufferCapacity);
        }
    }

    public void createOutputStream(int initialOutputBufferCapacity) {
        if (this.outputStream == null && initialOutputBufferCapacity > 0) {
            this.outputStream = new NewXOutputStream(this.fd, initialOutputBufferCapacity);
        }
    }

    public NewXInputStream getInputStream() {
        return this.inputStream;
    }

    public NewXOutputStream getOutputStream() {
        return this.outputStream;
    }

    public Object getTag() {
        return this.tag;
    }

    public void setTag(Object tag) {
        this.tag = tag;
    }

    public void destroy() {
        NewXInputStream xInputStream = this.inputStream;
        if (xInputStream != null) {
            xInputStream.destroy();
            this.inputStream = null;
        }
        NewXOutputStream xOutputStream = this.outputStream;
        if (xOutputStream != null) {
            xOutputStream.destroy();
            this.outputStream = null;
        }
    }
}
