package com.pridroid.xserver;

public interface XLock extends AutoCloseable {
    @Override
    void close();
}
