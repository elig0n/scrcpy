package com.genymobile.scrcpy;

import com.genymobile.scrcpy.wrappers.SurfaceControl;

import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.IBinder;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScreenEncoder implements Device.RotationListener {

    private static final int DEFAULT_FRAME_RATE = 60; // fps
    private static final int REDUCED_FRAME_RATE = 30; // fps

    private static final int DEFAULT_I_FRAME_INTERVAL   =  5; // seconds
    private static final int INCREASED_I_FRAME_INTERVAL = 10; // seconds

    private static final int REPEAT_FRAME_DELAY    = 6; // repeat after 6 frames

    private static final int MICROSECONDS_IN_ONE_SECOND = 1_000_000;
    private static final int NO_PTS = -1;

    private final AtomicBoolean rotationChanged = new AtomicBoolean();
    private final ByteBuffer headerBuffer = ByteBuffer.allocate(12);

    private int bitRate;
    private int frameRate;
    private int iFrameInterval;
    private int repeatFrameDelay;
    private boolean sendFrameMeta;
    private long ptsOrigin;

    private boolean abort = false;

    private ScreenEncoder(boolean sendFrameMeta, int bitRate, int frameRate, int iFrameInterval, int repeatFrameDelay) {
        this.sendFrameMeta    = sendFrameMeta;
        this.bitRate          = bitRate;
        this.frameRate        = frameRate;
        this.iFrameInterval   = iFrameInterval;
        this.repeatFrameDelay = repeatFrameDelay;
        Ln.i("bitRate: "+bitRate+" frameRate: "+frameRate+" iFrameInterval: "+iFrameInterval+" repeatFrameDelay: "+repeatFrameDelay);
    }

    public ScreenEncoder(boolean sendFrameMeta, int bitRate, boolean isTunnelForward) {
        this(sendFrameMeta, bitRate
                , isTunnelForward ? REDUCED_FRAME_RATE : DEFAULT_FRAME_RATE
                , isTunnelForward ? INCREASED_I_FRAME_INTERVAL : DEFAULT_I_FRAME_INTERVAL
                , REPEAT_FRAME_DELAY);
    }

    @Override
    public void onRotationChanged(int rotation) {
        rotationChanged.set(true);
    }

    public boolean consumeRotationChange() {
        return rotationChanged.getAndSet(false);
    }

    public void streamScreen(Device device, WritableByteChannel outputChannel) throws IOException {
        MediaFormat format = createFormat(bitRate, frameRate, iFrameInterval, repeatFrameDelay);
        device.setRotationListener(this);
        try {
            boolean alive;
            do {
                MediaCodec codec = createCodec();
                IBinder display = createDisplay();
                Rect contentRect = device.getScreenInfo().getContentRect();
                Rect videoRect = device.getScreenInfo().getVideoSize().toRect();
                setSize(format, videoRect.width(), videoRect.height());
                configure(codec, format);
                Surface surface = codec.createInputSurface();
                setDisplaySurface(display, surface, contentRect, videoRect);
                codec.start();
                try {
                    alive = encode(codec, outputChannel);
                    // do not call stop() on exception, it would trigger an IllegalStateException
                    codec.stop();
                } finally {
                    destroyDisplay(display);
                    codec.release();
                    surface.release();
                }
            } while (alive && !abort);
        } finally {
            device.setRotationListener(null);
        }
    }

    public void Abort() { abort = true; }

    private boolean encode(MediaCodec codec, final WritableByteChannel out) throws IOException {
        boolean eof = false;
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        final long timeoutUs = 1*1000*1000; // 1 second

        while (!consumeRotationChange() && !eof && !abort) {
            int outputBufferId = codec.dequeueOutputBuffer(bufferInfo, timeoutUs);
            eof = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            if (abort) break;
            try {
                if (consumeRotationChange()) {
                    // must restart encoding with new size
                    break;
                }
                if (outputBufferId >= 0) {
                    ByteBuffer codecBuffer = codec.getOutputBuffer(outputBufferId);

                    if (sendFrameMeta) {
                        writeFrameMeta(out, bufferInfo, codecBuffer.remaining());
                    }
                    if (out.write(codecBuffer) <= 0) {
                        Ln.w("Can't send frame");
                        abort = true;
                        break;
                    }
                }
            } finally {
                if (outputBufferId >= 0) {
                    codec.releaseOutputBuffer(outputBufferId, false);
                }
            }
        }

        return !eof;
    }

    private void writeFrameMeta(WritableByteChannel out, MediaCodec.BufferInfo bufferInfo, int packetSize) throws IOException {
        headerBuffer.clear();

        long pts;
        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            pts = NO_PTS; // non-media data packet
        } else {
            if (ptsOrigin == 0) {
                ptsOrigin = bufferInfo.presentationTimeUs;
            }
            pts = bufferInfo.presentationTimeUs - ptsOrigin;
        }

        headerBuffer.putLong(pts);
        headerBuffer.putInt(packetSize);
        headerBuffer.flip();
        out.write(headerBuffer);
    }

    private static MediaCodec createCodec() throws IOException {
        return MediaCodec.createEncoderByType("video/avc");
    }

    private static MediaFormat createFormat(int bitRate, int frameRate, int iFrameInterval, int repeatFrameDelay) throws IOException {
        MediaFormat format = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, "video/avc");
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval);
        // display the very first frame, and recover from bad quality when no new frames
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, MICROSECONDS_IN_ONE_SECOND * repeatFrameDelay / frameRate); // µs
        return format;
    }

    private static IBinder createDisplay() {
        return SurfaceControl.createDisplay("scrcpy", true);
    }

    private static void configure(MediaCodec codec, MediaFormat format) {
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    }

    private static void setSize(MediaFormat format, int width, int height) {
        format.setInteger(MediaFormat.KEY_WIDTH, width);
        format.setInteger(MediaFormat.KEY_HEIGHT, height);
    }

    private static void setDisplaySurface(IBinder display, Surface surface, Rect deviceRect, Rect displayRect) {
        SurfaceControl.openTransaction();
        try {
            SurfaceControl.setDisplaySurface(display, surface);
            SurfaceControl.setDisplayProjection(display, 0, deviceRect, displayRect);
            SurfaceControl.setDisplayLayerStack(display, 0);
        } finally {
            SurfaceControl.closeTransaction();
        }
    }

    private static void destroyDisplay(IBinder display) {
        SurfaceControl.destroyDisplay(display);
    }
}
