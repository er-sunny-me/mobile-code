package com.termux.app.database;

import android.content.Context;
import android.util.Log;

import io.objectbox.BoxStore;

public class ObjectBoxStore {
    private static final String TAG = "ObjectBoxStore";
    private static volatile BoxStore boxStore;

    public static synchronized void init(Context context) {
        if (boxStore != null) return;

        boxStore = MyObjectBox.builder()
                .androidContext(context.getApplicationContext())
                .name("zerocode-agents-db")
                .build();

        Log.i(TAG, "ObjectBox initialized. DB size: " + boxStore.sizeOnDisk() + " bytes");
    }

    public static BoxStore get() {
        if (boxStore == null) {
            throw new IllegalStateException("ObjectBox not initialized. Call ObjectBoxStore.init(context) first.");
        }
        return boxStore;
    }

    public static synchronized void close() {
        if (boxStore != null) {
            boxStore.close();
            boxStore = null;
        }
    }
}
