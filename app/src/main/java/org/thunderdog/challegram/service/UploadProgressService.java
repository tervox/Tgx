/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program, see <https://www.gnu.org/licenses/>.
 *
 * Servico de notificacao de progresso de upload de arquivos/videos.
 * Mantem o upload ativo em background mesmo ao trocar de app.
 */
package org.thunderdog.challegram.service;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.MainActivity;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.U;
import org.thunderdog.challegram.telegram.TdlibFilesManager;
import org.thunderdog.challegram.telegram.TdlibManager;
import org.thunderdog.challegram.tool.Intents;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Servico foreground que exibe uma notificacao persistente com barra de progresso
 * enquanto arquivos/videos estao sendo enviados. Impede que o Android encerre
 * o processo ao trocar de aplicativo durante uploads longos.
 *
 * Uso:
 *   UploadProgressService.startUpload(context, totalBytes);
 *   UploadProgressService.updateProgress(context, uploadedBytes, totalBytes);
 *   UploadProgressService.stopUpload(context);
 */
public class UploadProgressService extends Service {

  // -------------------------------------------------------------------------
  // Constantes de intencao
  // -------------------------------------------------------------------------

  private static final String ACTION_START  = "upload_start";
  private static final String ACTION_UPDATE = "upload_update";
  private static final String ACTION_STOP   = "upload_stop";

  private static final String EXTRA_UPLOADED = "uploaded_bytes";
  private static final String EXTRA_TOTAL    = "total_bytes";
  private static final String EXTRA_COUNT    = "file_count";

  /** ID fixo da notificacao de upload — nao colide com TdlibNotificationManager */
  public static final int NOTIFICATION_ID = Integer.MAX_VALUE - 10;

  // -------------------------------------------------------------------------
  // Estado interno
  // -------------------------------------------------------------------------

  private NotificationManager notificationManager;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  // Valores acumulados de todos os arquivos ativos
  private long uploadedBytes = 0;
  private long totalBytes    = 0;
  private int  fileCount     = 0;

  // Contador de instancias ativas (para saber quando parar o servico)
  private static final AtomicInteger activeUploads = new AtomicInteger(0);

  // -------------------------------------------------------------------------
  // Metodos estaticos publicos — chamados de qualquer lugar do app
  // -------------------------------------------------------------------------

  /**
   * Inicia o servico e mostra a notificacao de upload.
   * @param context  Contexto da aplicacao
   * @param total    Tamanho total do arquivo em bytes
   * @param count    Numero de arquivos sendo enviados
   */
  public static void startUpload (@NonNull Context context, long total, int count) {
    activeUploads.incrementAndGet();
    Intent intent = new Intent(context, UploadProgressService.class);
    intent.setAction(ACTION_START);
    intent.putExtra(EXTRA_TOTAL, total);
    intent.putExtra(EXTRA_COUNT, count);
    try {
      ContextCompat.startForegroundService(context, intent);
    } catch (Throwable ignored) {
      // Alguns dispositivos bloqueiam foreground services em certas condicoes
    }
  }

  /**
   * Atualiza o progresso na notificacao.
   * @param context   Contexto
   * @param uploaded  Bytes ja enviados
   * @param total     Total de bytes
   */
  public static void updateProgress (@NonNull Context context, long uploaded, long total) {
    Intent intent = new Intent(context, UploadProgressService.class);
    intent.setAction(ACTION_UPDATE);
    intent.putExtra(EXTRA_UPLOADED, uploaded);
    intent.putExtra(EXTRA_TOTAL, total);
    try {
      context.startService(intent);
    } catch (Throwable ignored) {}
  }

  /**
   * Para o servico quando o upload terminar ou for cancelado.
   */
  public static void stopUpload (@NonNull Context context) {
    int remaining = activeUploads.decrementAndGet();
    if (remaining < 0) activeUploads.set(0);
    Intent intent = new Intent(context, UploadProgressService.class);
    intent.setAction(ACTION_STOP);
    try {
      context.startService(intent);
    } catch (Throwable ignored) {}
  }

  // -------------------------------------------------------------------------
  // Ciclo de vida do Service
  // -------------------------------------------------------------------------

  @Override
  public void onCreate () {
    super.onCreate();
    notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
  }

  @Override
  public int onStartCommand (Intent intent, int flags, int startId) {
    if (intent == null) {
      stopSelfSafely();
      return START_NOT_STICKY;
    }

    String action = intent.getAction();
    if (action == null) {
      stopSelfSafely();
      return START_NOT_STICKY;
    }

    switch (action) {
      case ACTION_START: {
        totalBytes    = intent.getLongExtra(EXTRA_TOTAL, 0);
        fileCount     = intent.getIntExtra(EXTRA_COUNT, 1);
        uploadedBytes = 0;
        showNotification(0, 100);
        break;
      }
      case ACTION_UPDATE: {
        uploadedBytes = intent.getLongExtra(EXTRA_UPLOADED, 0);
        totalBytes    = intent.getLongExtra(EXTRA_TOTAL, totalBytes);
        int progress  = totalBytes > 0 ? (int) ((uploadedBytes * 100L) / totalBytes) : 0;
        updateNotification(progress);
        break;
      }
      case ACTION_STOP: {
        stopSelfSafely();
        return START_NOT_STICKY;
      }
    }

    return START_NOT_STICKY;
  }

  @Nullable
  @Override
  public IBinder onBind (Intent intent) {
    return null;
  }

  // -------------------------------------------------------------------------
  // Notificacoes
  // -------------------------------------------------------------------------

  /** Cria e exibe a notificacao inicial com progresso 0%. */
  private void showNotification (int progress, int max) {
    NotificationCompat.Builder builder = buildNotification(progress, max);
    try {
      startForeground(NOTIFICATION_ID, builder.build());
    } catch (Throwable t) {
      // Falha ao iniciar foreground — pode ocorrer em Android 12+ com restricoes
      stopSelfSafely();
    }
  }

  /** Atualiza a barra de progresso sem reiniciar o servico. */
  private void updateNotification (int progressPercent) {
    if (notificationManager == null) return;
    NotificationCompat.Builder builder = buildNotification(progressPercent, 100);
    try {
      notificationManager.notify(NOTIFICATION_ID, builder.build());
    } catch (Throwable ignored) {}
  }

  /** Constroi o objeto de notificacao com barra de progresso. */
  private NotificationCompat.Builder buildNotification (int progress, int max) {
    PendingIntent openAppIntent = PendingIntent.getActivity(
      this, 0,
      new Intent(this, MainActivity.class),
      Intents.mutabilityFlags(false)
    );

    String channelId = U.getOtherNotificationChannel();

    String title = fileCount > 1
      ? "Enviando " + fileCount + " arquivos…"
      : "Enviando arquivo…";

    String text = progress > 0
      ? progress + "% concluído"
      : "Preparando upload…";

    return new NotificationCompat.Builder(this, channelId)
      .setSmallIcon(R.drawable.baseline_sync_white_24)
      .setContentTitle(title)
      .setContentText(text)
      .setContentIntent(openAppIntent)
      .setOngoing(true)          // nao pode ser deslizada pelo usuario
      .setOnlyAlertOnce(true)    // sem som a cada atualizacao
      .setSilent(true)
      .setProgress(max, progress, progress == 0)  // indeterminado enquanto 0%
      .setPriority(NotificationCompat.PRIORITY_LOW);
  }

  private void stopSelfSafely () {
    try {
      stopForeground(true);
    } catch (Throwable ignored) {}
    stopSelf();
  }

  // -------------------------------------------------------------------------
  // Listener global de arquivos — conecta TDLib ao servico
  // -------------------------------------------------------------------------

  /**
   * Listener estatico que observa todos os updates de arquivo do TDLib
   * e repassa o progresso para o UploadProgressService.
   * Deve ser registrado em TdlibFilesManager via addGlobalListener().
   */
  public static class UploadFileListener implements TdlibFilesManager.FileListener {

    private final Context context;
    private final AtomicLong totalSize     = new AtomicLong(0);
    private final AtomicLong uploadedSize  = new AtomicLong(0);
    private boolean started = false;

    public UploadFileListener (@NonNull Context context, long totalBytes, int fileCount) {
      this.context = context.getApplicationContext();
      this.totalSize.set(totalBytes);
      startService(fileCount, totalBytes);
    }

    private void startService (int fileCount, long total) {
      started = true;
      UploadProgressService.startUpload(context, total, fileCount);
    }

    @Override
    public void onFileLoadProgress (TdApi.File file) {
      if (!started) return;
      if (file.remote.isUploadingActive) {
        long uploaded = file.remote.uploadedSize;
        long total    = file.expectedSize > 0 ? file.expectedSize : totalSize.get();
        uploadedSize.set(uploaded);
        totalSize.set(total);
        UploadProgressService.updateProgress(context, uploaded, total);
      }
    }

    @Override
    public void onFileLoadStateChanged (
      org.thunderdog.challegram.telegram.Tdlib tdlib,
      int fileId,
      int state,
      @Nullable TdApi.File downloadedFile
    ) {
      if (!started) return;
      // Quando o upload terminar (state != em progresso), para o servico
      if (state != TdlibFilesManager.STATE_IN_PROGRESS) {
        started = false;
        UploadProgressService.stopUpload(context);
      }
    }
  }
}
