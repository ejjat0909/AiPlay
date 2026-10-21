package com.ejjat.aiplay.device;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Writes diagnostic reports into app-specific external storage (no permission required). */
public final class ReportStore {

    private static final String FOLDER = "reports";

    private ReportStore() {
    }

    public static File directory(Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) {
            base = context.getFilesDir();
        }
        File dir = new File(base, FOLDER);
        if (!dir.exists() && !dir.mkdirs()) {
            Diag.log("report", "could not create " + dir.getAbsolutePath());
        }
        return dir;
    }

    public static File save(Context context, String report) throws IOException {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File file = new File(directory(context), "aiplay-report-" + stamp + ".txt");
        Writer writer = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
        try {
            writer.write(report);
        } finally {
            writer.close();
        }
        Diag.log("report", "saved " + file.getName() + " (" + file.length() + " bytes)");
        return file;
    }

    public static Uri uriFor(String name) {
        return Uri.parse("content://" + ReportProvider.AUTHORITY + "/" + name);
    }
}
