package com.termux.app;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import java.io.File;

public class UriToPathUtils {

    /**
     * Resolve an {@code ACTION_OPEN_DOCUMENT_TREE} result to a filesystem path.
     *
     * <p>Uses {@link DocumentsContract#getTreeDocumentId} rather than string-matching the URI
     * path, so removable volumes (SD cards) resolve to {@code /storage/<VOLUME_ID>/...} instead
     * of returning null.</p>
     */
    public static String getPathFromTreeUri(Uri treeUri) {
        if (treeUri == null) return null;

        try {
            String documentId = DocumentsContract.getTreeDocumentId(treeUri);
            if (documentId != null && documentId.contains(":")) {
                String[] split = documentId.split(":", 2);
                String volume = split[0];
                String relativePath = split[1];
                if ("primary".equalsIgnoreCase(volume)) {
                    return android.os.Environment.getExternalStorageDirectory()
                            + (relativePath.isEmpty() ? "" : "/" + relativePath);
                }
                return "/storage/" + volume + (relativePath.isEmpty() ? "" : "/" + relativePath);
            }
        } catch (Exception ignored) {
            // Fall through to the legacy parsing below.
        }

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
