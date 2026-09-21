package com.codex.toutiaothanks;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

public class UpdateFileProvider extends ContentProvider {
    static final String AUTHORITY = "com.codex.toutiaothanks.update";
    static final String FILE_NAME = "update.apk";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File base = getContext() == null ? null : getContext().getExternalFilesDir(null);
        if (base == null) {
            throw new FileNotFoundException("external files directory unavailable");
        }
        File apk = new File(base, FILE_NAME);
        if (!apk.isFile()) {
            throw new FileNotFoundException(apk.getAbsolutePath());
        }
        return ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
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