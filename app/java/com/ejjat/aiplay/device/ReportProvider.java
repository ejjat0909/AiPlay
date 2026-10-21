package com.ejjat.aiplay.device;

import android.content.ContentProvider;
import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Minimal read-only provider so a saved report can be shared without AndroidX's FileProvider.
 * Only files inside the report directory are reachable, and names are reduced to
 * their basename first.
 */
public final class ReportProvider extends ContentProvider {

    public static final String AUTHORITY = "com.ejjat.aiplay.reports";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (getContext() == null || uri.getLastPathSegment() == null) {
            throw new FileNotFoundException("no context or path");
        }
        String name = new File(uri.getLastPathSegment()).getName();
        File file = new File(ReportStore.directory(getContext()), name);
        if (!file.exists()) {
            throw new FileNotFoundException(name);
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "text/plain";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
