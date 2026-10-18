package org.webrtc;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.view.Surface;
import androidx.annotation.Nullable;
import java.io.IOException;
import java.nio.ByteBuffer;

import static org.webrtc.MediaCodecUtils.EXYNOS_PREFIX;
import static org.webrtc.MediaCodecUtils.QCOM_PREFIX;

/**
 * WebRTC'nin donanım H.264 kodlayıcı fabrikasının yerine geçer. Aynı kodlayıcıyı kurar,
 * tek fark: MediaCodec'e yapılandırma verilirken düşük gecikme ipuçları eklenir
 * ({@link EncoderTuning#lowLatency} açıksa). İpuçları kabul edilmezse hiçbir şey kaybedilmeden
 * ayarsız yapılandırmayla yeniden denenir; yani en kötü ihtimalle eski davranışa döner.
 *
 * Bu sınıf org.webrtc paketinde durur çünkü WebRTC'nin gerekli sınıfları paket-içi (package-private).
 */
public class PulseEncoderFactory implements VideoEncoderFactory {
    private static final String TAG = "PulseEncoderFactory";
    private static final int PERIODIC_KEY_FRAME_INTERVAL_S = 3600;

    @Nullable private final EglBase14.Context sharedContext;

    /**
     * Uygulamanın kullanacağı tam fabrika: önce bu sınıf, olmazsa WebRTC'nin kendi donanım fabrikası,
     * o da olmazsa yazılım kodlayıcılar. Yani bu katman bir şeyi bozarsa görüntü yine de gider.
     */
    public static VideoEncoderFactory createDefault(EglBase.Context eglContext) {
        VideoEncoderFactory hardware = new FallbackFactory(new PulseEncoderFactory(eglContext),
                new HardwareVideoEncoderFactory(eglContext, /* enableIntelVp8Encoder= */ true,
                        /* enableH264HighProfile= */ true));
        return new DefaultVideoEncoderFactory(hardware);
    }

    private static final class FallbackFactory implements VideoEncoderFactory {
        private final VideoEncoderFactory primary;
        private final VideoEncoderFactory secondary;

        FallbackFactory(VideoEncoderFactory primary, VideoEncoderFactory secondary) {
            this.primary = primary;
            this.secondary = secondary;
        }

        @Nullable
        @Override
        public VideoEncoder createEncoder(VideoCodecInfo info) {
            VideoEncoder encoder = null;
            try {
                encoder = primary.createEncoder(info);
            } catch (Throwable t) {
                Logging.w(TAG, "Primary encoder failed, using default: " + t);
            }
            return encoder != null ? encoder : secondary.createEncoder(info);
        }

        @Override
        public VideoCodecInfo[] getSupportedCodecs() {
            java.util.LinkedHashSet<VideoCodecInfo> all = new java.util.LinkedHashSet<>();
            all.addAll(java.util.Arrays.asList(primary.getSupportedCodecs()));
            all.addAll(java.util.Arrays.asList(secondary.getSupportedCodecs()));
            return all.toArray(new VideoCodecInfo[0]);
        }
    }

    public PulseEncoderFactory(EglBase.Context sharedContext) {
        if (sharedContext instanceof EglBase14.Context) {
            this.sharedContext = (EglBase14.Context) sharedContext;
        } else {
            Logging.w(TAG, "No shared EglBase.Context. Encoders will not use texture mode.");
            this.sharedContext = null;
        }
    }

    @Nullable
    @Override
    public VideoEncoder createEncoder(VideoCodecInfo input) {
        VideoCodecMimeType type;
        try {
            type = VideoCodecMimeType.valueOf(input.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (type != VideoCodecMimeType.H264) {
            return null; // yalnızca H.264 kullanılıyor
        }
        MediaCodecInfo info = findCodecForType(type);
        if (info == null) {
            return null;
        }

        String codecName = info.getName();
        String mime = type.mimeType();
        Integer surfaceColorFormat = MediaCodecUtils.selectColorFormat(
                MediaCodecUtils.TEXTURE_COLOR_FORMATS, info.getCapabilitiesForType(mime));
        Integer yuvColorFormat = MediaCodecUtils.selectColorFormat(
                MediaCodecUtils.ENCODER_COLOR_FORMATS, info.getCapabilitiesForType(mime));

        boolean isBaselineProfile = H264Utils.isSameH264Profile(
                input.params, MediaCodecUtils.getCodecProperties(type, /* highProfile= */ false));
        if (!isBaselineProfile) {
            return null; // yüksek profil (Exynos OMX) bu uygulamada kullanılmıyor
        }

        final MediaCodecWrapperFactory base = new MediaCodecWrapperFactoryImpl();
        final MediaCodecWrapperFactory tuned = new MediaCodecWrapperFactory() {
            @Override
            public MediaCodecWrapper createByCodecName(String name) throws IOException {
                return new TuningWrapper(name, base);
            }
        };

        return new HardwareVideoEncoder(tuned, codecName, type, surfaceColorFormat, yuvColorFormat,
                input.params, PERIODIC_KEY_FRAME_INTERVAL_S, /* forceKeyFrameIntervalMs= */ 0,
                createBitrateAdjuster(codecName), sharedContext);
    }

    @Override
    public VideoCodecInfo[] getSupportedCodecs() {
        MediaCodecInfo codec = findCodecForType(VideoCodecMimeType.H264);
        if (codec == null) {
            return new VideoCodecInfo[0];
        }
        return new VideoCodecInfo[] {new VideoCodecInfo(VideoCodecMimeType.H264.name(),
                MediaCodecUtils.getCodecProperties(VideoCodecMimeType.H264, /* highProfile= */ false))};
    }

    @Nullable
    private MediaCodecInfo findCodecForType(VideoCodecMimeType type) {
        for (int i = 0; i < MediaCodecList.getCodecCount(); ++i) {
            MediaCodecInfo info = null;
            try {
                info = MediaCodecList.getCodecInfoAt(i);
            } catch (IllegalArgumentException e) {
                Logging.e(TAG, "Cannot retrieve encoder codec info", e);
            }
            if (info == null || !info.isEncoder()) {
                continue;
            }
            if (isSupportedCodec(info, type)) {
                return info;
            }
        }
        return null;
    }

    private boolean isSupportedCodec(MediaCodecInfo info, VideoCodecMimeType type) {
        if (!MediaCodecUtils.codecSupportsType(info, type)) {
            return false;
        }
        if (MediaCodecUtils.selectColorFormat(
                    MediaCodecUtils.ENCODER_COLOR_FORMATS, info.getCapabilitiesForType(type.mimeType()))
                == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isHardwareAccelerated();
        }
        String name = info.getName();
        return name.startsWith(QCOM_PREFIX) || name.startsWith(EXYNOS_PREFIX);
    }

    private BitrateAdjuster createBitrateAdjuster(String codecName) {
        if (codecName.startsWith(EXYNOS_PREFIX)) {
            return new FramerateBitrateAdjuster();
        }
        return new BaseBitrateAdjuster();
    }

    /** MediaCodec'i sarar; configure() sırasında düşük gecikme ipuçlarını ekler. */
    private static final class TuningWrapper implements MediaCodecWrapper {
        private final String name;
        private final MediaCodecWrapperFactory base;
        private MediaCodecWrapper delegate;

        TuningWrapper(String name, MediaCodecWrapperFactory base) throws IOException {
            this.name = name;
            this.base = base;
            this.delegate = base.createByCodecName(name);
        }

        @Override
        public void configure(MediaFormat format, Surface surface, MediaCrypto crypto, int flags) {
            if (EncoderTuning.lowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    MediaFormat tunedFormat = new MediaFormat(format); // özgün biçim bozulmasın
                    tunedFormat.setInteger(MediaFormat.KEY_PRIORITY, 0);        // gerçek zamanlı öncelik
                    tunedFormat.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);    // ileri tahminli (B) kare yok
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        tunedFormat.setInteger(MediaFormat.KEY_LATENCY, 1);     // en düşük gecikme ipucu
                    }
                    delegate.configure(tunedFormat, surface, crypto, flags);
                    return;
                } catch (Throwable t) {
                    Logging.w(TAG, "Low-latency hints rejected, retrying plain: " + t);
                    try {
                        delegate.release();
                    } catch (Throwable ignored) {
                    }
                    try {
                        delegate = base.createByCodecName(name);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
            delegate.configure(format, surface, crypto, flags);
        }

        @Override public void start() { delegate.start(); }
        @Override public void flush() { delegate.flush(); }
        @Override public void stop() { delegate.stop(); }
        @Override public void release() { delegate.release(); }
        @Override public int dequeueInputBuffer(long timeoutUs) { return delegate.dequeueInputBuffer(timeoutUs); }
        @Override public void queueInputBuffer(int index, int offset, int size, long presentationTimeUs, int flags) {
            delegate.queueInputBuffer(index, offset, size, presentationTimeUs, flags);
        }
        @Override public int dequeueOutputBuffer(MediaCodec.BufferInfo info, long timeoutUs) {
            return delegate.dequeueOutputBuffer(info, timeoutUs);
        }
        @Override public void releaseOutputBuffer(int index, boolean render) { delegate.releaseOutputBuffer(index, render); }
        @Override public MediaFormat getInputFormat() { return delegate.getInputFormat(); }
        @Override public MediaFormat getOutputFormat() { return delegate.getOutputFormat(); }
        @Override public MediaFormat getOutputFormat(int index) { return delegate.getOutputFormat(index); }
        @Override public ByteBuffer getInputBuffer(int index) { return delegate.getInputBuffer(index); }
        @Override public ByteBuffer getOutputBuffer(int index) { return delegate.getOutputBuffer(index); }
        @Override public Surface createInputSurface() { return delegate.createInputSurface(); }
        @Override public void setParameters(Bundle params) { delegate.setParameters(params); }
        @Override public MediaCodecInfo getCodecInfo() { return delegate.getCodecInfo(); }
    }
}
