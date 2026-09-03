package jp.rimtty.codematch.history;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.UUID;

/**
 * File-backed provider used only by the app's instrumentation test APK.
 *
 * This class is Java on purpose: the provider can be called through Binder
 * before the instrumentation test class loader has Kotlin stdlib available.
 */
public final class RecordingDocumentProvider extends ContentProvider {
    private File currentFile;

    @Override
    public boolean onCreate() {
        final android.content.Context providerContext = getContext();
        if (providerContext == null) {
            return false;
        }
        currentFile = new File(
                providerContext.getCacheDir(),
                "document-provider-" + UUID.randomUUID() + ".pdf"
        );
        currentFile.delete();
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "application/pdf";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"/history.pdf".equals(uri.getPath())) {
            throw new FileNotFoundException("Unknown test document");
        }
        final File file = currentFile;
        if (file == null) {
            throw new FileNotFoundException("Provider is not initialized");
        }
        final boolean write = mode != null && mode.contains("w");
        final int flags = write
                ? ParcelFileDescriptor.MODE_CREATE
                        | ParcelFileDescriptor.MODE_READ_WRITE
                        | ParcelFileDescriptor.MODE_TRUNCATE
                : ParcelFileDescriptor.MODE_READ_ONLY;
        return ParcelFileDescriptor.open(file, flags);
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder
    ) {
        final String[] columns = projection == null
                ? new String[]{"_display_name"}
                : projection;
        final MatrixCursor cursor = new MatrixCursor(columns);
        final Object[] row = new Object[columns.length];
        if (row.length > 0) {
            row[0] = "history.pdf";
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        if (!"/history.pdf".equals(uri.getPath()) || currentFile == null) {
            return 0;
        }
        return currentFile.delete() ? 1 : 0;
    }

    @Override
    public int update(
            Uri uri,
            ContentValues values,
            String selection,
            String[] selectionArgs
    ) {
        return 0;
    }
}
