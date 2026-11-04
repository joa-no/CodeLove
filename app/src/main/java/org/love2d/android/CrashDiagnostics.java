package org.love2d.android;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Registra eventos locais e recupera informacoes de encerramentos anormais anteriores. */
final class CrashDiagnostics {
    private static final String TAG = "CodeLoveDiag";
    private static final String PREFS = "codelove_diagnostics";
    private static final String KEY_LAST_REPORTED_EXIT = "last_reported_exit";
    private static final String LOG_FILE = "codelove-diagnostics.log";
    private static final long MAX_LOG_BYTES = 256 * 1024;

    private CrashDiagnostics() {}

    static synchronized void log(Context context, String message) {
        log(context, message, null);
    }

    static synchronized void log(Context context, String message, Throwable error) {
        if (context == null) return;
        try {
            File file = new File(context.getFilesDir(), LOG_FILE);
            if (file.exists() && file.length() > MAX_LOG_BYTES) {
                File backup = new File(context.getFilesDir(), LOG_FILE + ".1");
                if (backup.exists()) backup.delete();
                if (!file.renameTo(backup)) file.delete();
            }
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date());
            StringBuilder entry = new StringBuilder();
            entry.append(time).append(" [").append(Thread.currentThread().getName())
                .append("] ").append(message == null ? "" : message).append('\n');
            if (error != null) {
                StringWriter trace = new StringWriter();
                error.printStackTrace(new PrintWriter(trace));
                entry.append(trace).append('\n');
            }
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(entry.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
            }
        } catch (Throwable loggingFailure) {
            Log.e(TAG, "Could not persist diagnostic event", loggingFailure);
        }
    }

    static String consumePreviousExitReport(Context context) {
        if (Build.VERSION.SDK_INT < 30) return null;
        try {
            ActivityManager manager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return null;
            List<ApplicationExitInfo> exits =
                manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 8);
            if (exits == null || exits.isEmpty()) return null;

            long lastReported = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_REPORTED_EXIT, 0L);
            ApplicationExitInfo newest = null;
            for (ApplicationExitInfo info : exits) {
                if (info == null || info.getTimestamp() <= lastReported) continue;
                int reason = info.getReason();
                if (reason == ApplicationExitInfo.REASON_CRASH
                    || reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                    || reason == ApplicationExitInfo.REASON_ANR
                    || reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
                    || reason == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE
                    || reason == ApplicationExitInfo.REASON_DEPENDENCY_DIED) {
                    if (newest == null || info.getTimestamp() > newest.getTimestamp()) {
                        newest = info;
                    }
                }
            }
            if (newest == null) return null;

            StringBuilder report = new StringBuilder();
            report.append("O Android registrou um encerramento anormal na execucao anterior.\n\n");
            report.append("Motivo: ").append(reasonName(newest.getReason()))
                .append(" (codigo ").append(newest.getReason()).append(")\n");
            report.append("Horario: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                Locale.getDefault()).format(new Date(newest.getTimestamp()))).append('\n');
            report.append("Processo: ").append(newest.getProcessName()).append('\n');
            if (newest.getDescription() != null && !newest.getDescription().isEmpty()) {
                report.append("Descricao: ").append(newest.getDescription()).append('\n');
            }
            report.append("Importancia do processo: ").append(newest.getImportance()).append('\n');

            if (Build.VERSION.SDK_INT >= 31) {
                InputStream trace = null;
                try {
                    trace = newest.getTraceInputStream();
                    if (trace != null) {
                        report.append("\n--- Rastreamento fornecido pelo Android ---\n");
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        byte[] buffer = new byte[4096];
                        int read;
                        while ((read = trace.read(buffer)) != -1 && bytes.size() < 48 * 1024) {
                            int allowed = Math.min(read, 48 * 1024 - bytes.size());
                            bytes.write(buffer, 0, allowed);
                        }
                        report.append(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                    }
                } catch (Exception traceError) {
                    report.append("\nNao foi possivel ler o rastreamento: ")
                        .append(traceError.getClass().getSimpleName());
                } finally {
                    if (trace != null) try { trace.close(); } catch (Exception ignored) {}
                }
            }

            report.append("\n\n--- Eventos registrados pelo CodeLove ---\n");
            appendLogTail(context, report);
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_REPORTED_EXIT, newest.getTimestamp()).apply();
            log(context, "Previous abnormal process exit surfaced in workspace");
            return report.toString();
        } catch (Throwable error) {
            log(context, "Could not inspect previous process exit information", error);
            return null;
        }
    }

    private static void appendLogTail(Context context, StringBuilder report) {
        File file = new File(context.getFilesDir(), LOG_FILE);
        if (!file.isFile()) {
            report.append("Nenhum evento local foi registrado.\n");
            return;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(file.length(), 48 * 1024)];
            int count = in.read(data);
            if (count > 0) report.append(new String(data, 0, count, StandardCharsets.UTF_8));
        } catch (Exception e) {
            report.append("Falha ao ler o registro local: ").append(e.getMessage());
        }
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_CRASH: return "Crash Java";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "Crash nativo";
            case ApplicationExitInfo.REASON_ANR: return "Aplicativo sem resposta (ANR)";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "Falha de inicializacao";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "Uso excessivo de recursos";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "Processo dependente encerrou";
            default: return "Outro";
        }
    }
}
