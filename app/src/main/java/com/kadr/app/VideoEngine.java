package com.kadr.app;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VideoEngine {
    public interface Progress { void update(int percent, String message); }
    public static final class Metadata {
        public long durationMs; public int width, height;
    }
    private VideoEngine() {}
    public static Metadata metadata(Context context, Uri uri) throws Exception {
        try (MediaMetadataRetriever r = new MediaMetadataRetriever()) {
            r.setDataSource(context, uri);
            Metadata m = new Metadata();
            m.durationMs = Long.parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            m.width = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            m.height = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            if (m.durationMs <= 0) throw new IllegalArgumentException("Не удалось прочитать длительность видео");
            return m;
        }
    }
    public static List<MomentSelector.Window> analyze(Context context, Uri uri, long duration,
            long length, int count, boolean calm, AtomicBoolean cancelled, Progress progress) throws Exception {
        List<MomentSelector.Sample> samples = new ArrayList<>();
        // At most 900 decoded samples: bounded memory even for a full feature film.
        int steps = (int) Math.min(900, Math.max(12, duration / 1800));
        int[] previous = null;
        int decoded = 0;
        try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
            retriever.setDataSource(context, uri);
            for (int i = 0; i < steps; i++) {
                check(cancelled);
                long t = (long) ((duration - 1) * (double) i / (steps - 1));
                Bitmap frame = retriever.getScaledFrameAtTime(t * 1000,
                        MediaMetadataRetriever.OPTION_CLOSEST, 96, 54);
                if (frame != null) {
                    Bitmap small = Bitmap.createScaledBitmap(frame, 48, 27, false);
                    int[] pixels = new int[48 * 27];
                    small.getPixels(pixels, 0, 48, 0, 0, 48, 27);
                    double delta = 0, light = 0;
                    for (int j = 0; j < pixels.length; j++) {
                        int c = pixels[j], r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                        light += (r + g + b) / 3.0;
                        if (previous != null) {
                            int old = previous[j];
                            delta += Math.abs(r - ((old >> 16) & 255)) + Math.abs(g - ((old >> 8) & 255)) + Math.abs(b - (old & 255));
                        }
                    }
                    // Exclude mostly black frames and the first frame, which has no predecessor.
                    if (previous != null && light / pixels.length > 14) {
                        samples.add(new MomentSelector.Sample(t, delta / (pixels.length * 3.0)));
                    }
                    previous = pixels;
                    decoded++;
                    if (small != frame) small.recycle();
                    frame.recycle();
                }
                progress.update((i + 1) * 100 / steps, "Сравниваем кадры: " + (i + 1) + " / " + steps);
            }
        }
        if (decoded < 2) throw new IllegalArgumentException("Не удалось декодировать видео. Попробуй MP4 с кодеком H.264.");
        return MomentSelector.select(samples, duration, length, count, calm);
    }
    public static String thumbnail(Context context, Uri uri, long timeMs, String key) {
        File file = new File(context.getCacheDir(), key + ".jpg");
        if (file.exists()) return file.getAbsolutePath();
        try (MediaMetadataRetriever r = new MediaMetadataRetriever()) {
            r.setDataSource(context, uri);
            Bitmap bitmap = r.getScaledFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 400, 240);
            if (bitmap == null) return "";
            try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.JPEG, 75, out); }
            bitmap.recycle();
            return file.getAbsolutePath();
        } catch (Exception e) { return ""; }
    }
    /** Lossless stream copy. The start is aligned to the previous video keyframe. */
    public static Uri export(Context context, Uri source, long startMs, long endMs,
            String name, AtomicBoolean cancelled, Progress progress) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        ParcelFileDescriptor output = null;
        Uri uri = null;
        boolean started = false, complete = false;
        try {
            extractor.setDataSource(context, source, null);
            int videoTrack = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) { videoTrack = i; break; }
            }
            if (videoTrack < 0) throw new IllegalArgumentException("В файле нет видеодорожки");
            extractor.selectTrack(videoTrack);
            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            long baseUs = extractor.getSampleTime();
            if (baseUs < 0 || baseUs >= endMs * 1000) throw new IllegalArgumentException("Не удалось найти начало фрагмента");
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, name.replaceAll("[\\\\/:*?\"<>|]", "_") + ".mp4");
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/KADR");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            uri = context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("Не удалось создать файл в галерее");
            output = context.getContentResolver().openFileDescriptor(uri, "rw");
            if (output == null) throw new IllegalStateException("Не удалось открыть файл для записи");
            muxer = new MediaMuxer(output.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            try (MediaMetadataRetriever r = new MediaMetadataRetriever()) {
                r.setDataSource(context, source);
                String rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                if (rotation != null) muxer.setOrientationHint(Integer.parseInt(rotation));
            }
            Map<Integer, Integer> tracks = new HashMap<>();
            int bufferSize = 4 * 1024 * 1024;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && (mime.startsWith("video/") || mime.startsWith("audio/"))) {
                    tracks.put(i, muxer.addTrack(format));
                    extractor.selectTrack(i);
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) bufferSize = Math.max(bufferSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                }
            }
            extractor.seekTo(baseUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            muxer.start(); started = true;
            ByteBuffer buffer = ByteBuffer.allocateDirect(bufferSize);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int writtenVideo = 0;
            while (true) {
                check(cancelled);
                int track = extractor.getSampleTrackIndex();
                long pts = extractor.getSampleTime();
                if (track < 0 || pts >= endMs * 1000) break;
                if (pts < baseUs) { extractor.advance(); continue; }
                if (extractor.getSampleSize() > buffer.capacity()) throw new IllegalArgumentException("Слишком большой кадр для экспорта на этом устройстве");
                if ((extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) throw new IllegalArgumentException("Защищённое видео не поддерживается");
                buffer.clear();
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) break;
                info.set(0, size, pts - baseUs, (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                muxer.writeSampleData(tracks.get(track), buffer, info);
                if (track == videoTrack) writtenVideo++;
                progress.update((int) Math.min(100, (pts - baseUs) * 100 / Math.max(1, endMs * 1000 - baseUs)), "Сохраняем видео…");
                extractor.advance();
            }
            if (writtenVideo == 0) throw new IllegalStateException("Не удалось записать видеокадры");
            muxer.stop(); started = false;
            check(cancelled);
            ContentValues ready = new ContentValues(); ready.put(MediaStore.Video.Media.IS_PENDING, 0);
            context.getContentResolver().update(uri, ready, null, null);
            complete = true;
            return uri;
        } finally {
            if (started && muxer != null) { try { muxer.stop(); } catch (Exception ignored) {} }
            if (muxer != null) { try { muxer.release(); } catch (Exception ignored) {} }
            extractor.release();
            if (output != null) { try { output.close(); } catch (Exception ignored) {} }
            if (!complete && uri != null) context.getContentResolver().delete(uri, null, null);
        }
    }
    private static void check(AtomicBoolean cancelled) {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancellationException("Операция отменена");
    }
}
