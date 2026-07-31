package com.termux.app;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import java.io.File;

public class UriToPathUtils {
    public static String getPathFromTreeUri(Uri treeUri) {
        if (treeUri == null) return null;
        String path = treeUri.getPath();
        if (path != null && path.startsWith("/tree/primary:")) {
            String relativePath = path.substring("/tree/primary:".length());
            return "/storage/emulated/0/" + relativePath;
        } else if (path != null && path.startsWith("/tree/raw:")) {
            return path.substring("/tree/raw:".length());
        }
        return null;
    }
}
