package net.majorkernelpanic.streaming.video;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import net.majorkernelpanic.streaming.RequestPermissionActivity;
import net.majorkernelpanic.streaming.exceptions.ConfNotSupportedException;
import net.majorkernelpanic.streaming.exceptions.StorageUnavailableException;
import net.majorkernelpanic.streaming.mp4.MP4Config;
import net.majorkernelpanic.streaming.rtp.H264Packetizer;
import net.majorkernelpanic.streaming.rtp.MediaCodecInputStream;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class ScreenStream extends BaseVideoStream {
    public static final String TAG = ScreenStream.class.getSimpleName();

    public static final String ACTION_GET_MEDIA_PROJECTION = "ACTION_GET_MEDIA_PROJECTION";
    public static final String MEDIA_PROJECTION_RESUTLT = "MEDIA_PROJECTION_RESUTLT";
    public static final String MEDIA_PROJECTION_DATA = "MEDIA_PROJECTION_DATA";

    public static final int BIT_RATE_MIN = 64_000;
    public static final int BIT_RATE_MAX = 40_000_000;

    public static final int DEFAULT_SCREEN_DENSITY = 160;
    public static final int DEFAULT_SCREEN_WIDTH = 720;
    public static final int DEFAULT_SCREEN_HEIGHT = 1280;
    public static final int DEFAULT_BIT_RATE = 2_000_000;
    public static final int DEFAULT_FRAME_RATE = 25; // 25fps

    public static final int DEFAULT_I_FRAME_INTERVAL = 1; // seconds
    public static final int DEFAULT_REPEAT_FRAME_DELAY = 100_000; // repeat after 100ms

    private static final IntentFilter INTENT_FILTER = new IntentFilter(ACTION_GET_MEDIA_PROJECTION);

    protected VideoQuality mQuality = new VideoQuality(
            DEFAULT_SCREEN_WIDTH, DEFAULT_SCREEN_HEIGHT, DEFAULT_FRAME_RATE, DEFAULT_BIT_RATE);

    protected SharedPreferences mPreferences;

    private DisplayMetrics mDisplayMetrics;
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;
    private MP4Config mConfig;

    private final Semaphore mSemaphore = new Semaphore(0);

    private final BroadcastReceiver mLocalReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean success = intent.getBooleanExtra(MEDIA_PROJECTION_RESUTLT, false);
            Log.d(TAG, "get media projection result:" + success);
            if (success) {
                Intent resultData = intent.getParcelableExtra(MEDIA_PROJECTION_DATA);
                mMediaProjection = context.getSystemService(MediaProjectionManager.class)
                        .getMediaProjection(Activity.RESULT_OK, resultData);
            } else {
                // TODO
                Log.w(TAG, "User cancelled");
            }
            LocalBroadcastManager.getInstance(context).unregisterReceiver(this);
            mSemaphore.release();
        }
    };

    /**
     * Constructs the H.264 stream.
     */
    public ScreenStream(Context context) {
        mPacketizer = new H264Packetizer();
        WindowManager wm = context.getSystemService(WindowManager.class);
        wm.getDefaultDisplay().getMetrics(mDisplayMetrics = new DisplayMetrics());
        adaptiveAspectRatio();
        LocalBroadcastManager.getInstance(context).registerReceiver(mLocalReceiver, INTENT_FILTER);
        Intent intent = new Intent(context, RequestPermissionActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        try {
            mSemaphore.acquire();
        } catch (InterruptedException e) {
            Log.w(TAG, "InterruptedException", e);
        }
    }

    /**
     * Sets the configuration of the stream. You can call this method at any time
     * and changes will take effect next time you call {@link #configure()}.
     *
     * @param videoQuality Quality of the stream
     */
    @Override
    public void setVideoQuality(VideoQuality videoQuality) {
        if (!mQuality.equals(videoQuality)) {
            mQuality = videoQuality.clone();
            adaptiveAspectRatio();
        }
    }

    private void adaptiveAspectRatio() {
        if (mDisplayMetrics != null) {
            mQuality.resX = mDisplayMetrics.widthPixels / 2;
            if ((mQuality.resX & 1) == 1) {
                mQuality.resX++;
            }
            mQuality.resY = mDisplayMetrics.heightPixels / 2;
            if ((mQuality.resY & 1) == 1) {
                mQuality.resY++;
            }
        }
    }

    /**
     * Some data (SPS and PPS params) needs to be stored when {@link #getSessionDescription()} is
     * called
     *
     * @param prefs The SharedPreferences that will be used to save SPS and PPS parameters
     */
    public void setPreferences(SharedPreferences prefs) {
        mPreferences = prefs;
    }

    /**
     * Returns a description of the stream using SDP. It can then be included in an SDP file.
     */
    @Override
    public synchronized String getSessionDescription() throws IllegalStateException {
        if (mConfig == null) {
            throw new IllegalStateException("You need to call configure() first !");
        }
        return "m=video " + getDestinationPorts()[0] + " RTP/AVP 96\r\n"
                + "a=rtpmap:96 H264/90000\r\n"
                + "a=fmtp:96 packetization-mode=1;profile-level-id=" + mConfig.getProfileLevel()
                + ";sprop-parameter-sets=" + mConfig.getB64SPS() + "," + mConfig.getB64PPS()
                + ";\r\n";
    }

    /**
     * Starts the stream.
     */
    @Override
    public synchronized void start() throws IllegalStateException, IOException {
        if (!mStreaming) {
            configure();
            byte[] pps = Base64.decode(mConfig.getB64PPS(), Base64.NO_WRAP);
            byte[] sps = Base64.decode(mConfig.getB64SPS(), Base64.NO_WRAP);
            ((H264Packetizer) mPacketizer).setStreamParameters(pps, sps);
            super.start();
            Log.d(TAG, "Stream configuration: FPS: " + mQuality.framerate + " Width: "
                    + mQuality.resX + " Height: " + mQuality.resY);
        }
    }

    /**
     * Configures the stream. You need to call this before calling {@link #getSessionDescription()}
     * to apply your configuration of the stream.
     */
    @Override
    public synchronized void configure() throws IllegalStateException, IOException {
        super.configure();
        mMode = mRequestedMode;
        mConfig = testH264();
    }

    /** Stops the stream. */
    @Override
    public synchronized void stop() {
        super.stop();
        if (mMediaProjection != null) {
            mMediaProjection.stop();
            mMediaProjection = null;
        }
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
    }

    /**
     * Tests if streaming with the given configuration (bit rate, frame rate, resolution) is
     * possible and determines the pps and sps. Should not be called by the UI thread.
     **/
    private MP4Config testH264() throws IllegalStateException, IOException {
        if (mMode != MODE_MEDIARECORDER_API) {
            return testMediaCodecAPI();
        } else {
            return testMediaRecorderAPI();
        }
    }

    @SuppressLint("NewApi")
    private MP4Config testMediaCodecAPI() throws RuntimeException, IOException {
        try {
            mMediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
                    mQuality.resX, mQuality.resY);
            format.setInteger(MediaFormat.KEY_BIT_RATE, mQuality.bitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, mQuality.framerate);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, DEFAULT_I_FRAME_INTERVAL);
            mMediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            // Some encoders won't give us the SPS and PPS unless they receive something to
            // encode first...
            startVirtualDisplay(mMediaCodec.createInputSurface());
            mMediaCodec.start();

            byte[] csd = new byte[128];
            byte[] sps = null, pps = null;
            int len = 0, p = 4, q = 4;
            long start = System.currentTimeMillis();
            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            while (sps == null || pps == null) {
                // We are looking for the SPS and the PPS here. As always, Android is very
                // inconsistent, I have observed that some encoders will give those parameters
                // through the MediaFormat object (that is the normal behaviour).
                // But some other will not, in that case we try to find a NAL unit of type 7 or 8 in
                // the byte stream outputed by the encoder...
                int outputIndex = mMediaCodec.dequeueOutputBuffer(info,
                        1000000 / mQuality.framerate);

                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // The PPS and PPS shoud be there
                    MediaFormat fmt = mMediaCodec.getOutputFormat();
                    ByteBuffer spsb = fmt.getByteBuffer("csd-0");
                    ByteBuffer ppsb = fmt.getByteBuffer("csd-1");
                    sps = new byte[spsb.capacity() - 4];
                    spsb.position(4);
                    spsb.get(sps, 0, sps.length);
                    pps = new byte[ppsb.capacity() - 4];
                    ppsb.position(4);
                    ppsb.get(pps, 0, pps.length);
                    break;
                } else if (outputIndex >= 0) {
                    len = info.size;
                    if (len < 128) {
                        ByteBuffer ouputBuffer = mMediaCodec.getOutputBuffer(outputIndex);
                        ouputBuffer.get(csd, 0, len);
                        if (len > 0 && csd[0] == 0 && csd[1] == 0 && csd[2] == 0 && csd[3] == 1) {
                            // Parses the SPS and PPS, they could be in two different packets and
                            // in a different order depending on the phone so we don't make any
                            // assumption about that
                            while (p < len) {
                                while (!(csd[p + 0] == 0
                                        && csd[p + 1] == 0
                                        && csd[p + 2] == 0
                                        && csd[p + 3] == 1)
                                        && p + 3 < len) {
                                    p++;
                                }
                                if (p + 3 >= len) {
                                    p = len;
                                }
                                if ((csd[q] & 0x1F) == 7) {
                                    sps = new byte[p - q];
                                    System.arraycopy(csd, q, sps, 0, p - q);
                                } else {
                                    pps = new byte[p - q];
                                    System.arraycopy(csd, q, pps, 0, p - q);
                                }
                                p += 4;
                                q = p;
                            }
                        }
                    }
                    mMediaCodec.releaseOutputBuffer(outputIndex, false);
                }

                if ((System.currentTimeMillis() - start) > 3000) {
                    break;
                }
            }

            if (pps == null || sps == null) {
                Log.e(TAG, "Could not determine the SPS & PPS.");
            }
            String spsB64 = Base64.encodeToString(sps, 0, sps.length, Base64.NO_WRAP);
            String ppsB64 = Base64.encodeToString(pps, 0, pps.length, Base64.NO_WRAP);
            mVirtualDisplay.release();
            mVirtualDisplay = null;
            mMediaCodec.stop();
            mMediaCodec.release();
            mMediaCodec = null;
            MP4Config config = new MP4Config(spsB64, ppsB64);
            return config;
        } catch (Exception e) {
            // Fallback on the old streaming method using the MediaRecorder API
            Log.e(TAG, "Resolution not supported with the MediaCodec API, we fallback on the old "
                    + "streaming method.");
            mMode = MODE_MEDIARECORDER_API;
            return testH264();
        }
    }

    // Should not be called by the UI thread
    private MP4Config testMediaRecorderAPI() throws RuntimeException, IOException {
        String key = PREF_PREFIX + "h264-scr-mr-" + mQuality.framerate + ","
                + mQuality.resX + "," + mQuality.resY;

        if (mPreferences != null && mPreferences.contains(key)) {
            String[] s = mPreferences.getString(key, "").split(",");
            return new MP4Config(s[0], s[1], s[2]);
        }

        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            throw new StorageUnavailableException(
                    "No external storage or external storage not ready !");
        }

        final String testFile =
                Environment.getExternalStorageDirectory().getPath() + "/spydroid-test.mp4";

        Log.i(TAG, "Testing H264 support... Test file saved at: " + testFile);

        try {
            File file = new File(testFile);
            file.createNewFile();
        } catch (IOException e) {
            throw new StorageUnavailableException(e);
        }

        try {
            mMediaRecorder = new MediaRecorder();
            mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mMediaRecorder.setVideoSize(mQuality.resX, mQuality.resY);
            mMediaRecorder.setVideoFrameRate(mQuality.framerate);
            mMediaRecorder.setVideoEncodingBitRate(mQuality.bitrate);
            mMediaRecorder.setMaxDuration(3000);
            mMediaRecorder.setOutputFile(testFile);

            final Semaphore semaphore = new Semaphore(0);
            // We wait a little and stop recording
            mMediaRecorder.setOnInfoListener((mr, what, extra) -> {
                Log.d(TAG, "MediaRecorder callback called !");
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    Log.d(TAG, "MediaRecorder: MAX_DURATION_REACHED");
                } else if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                    Log.d(TAG, "MediaRecorder: MAX_FILESIZE_REACHED");
                } else if (what == MediaRecorder.MEDIA_RECORDER_INFO_UNKNOWN) {
                    Log.d(TAG, "MediaRecorder: INFO_UNKNOWN");
                } else {
                    Log.d(TAG, "WTF ?");
                }
                semaphore.release();
            });

            // Start recording
            mMediaRecorder.prepare();
            startVirtualDisplay(mMediaRecorder.getSurface());
            mMediaRecorder.start();

            if (semaphore.tryAcquire(6, TimeUnit.SECONDS)) {
                Log.d(TAG, "MediaRecorder callback was called :)");
                Thread.sleep(400);
            } else {
                Log.d(TAG, "MediaRecorder callback was not called after 6 seconds... :(");
            }
        } catch (IOException e) {
            throw new ConfNotSupportedException(e);
        } catch (RuntimeException e) {
            throw new ConfNotSupportedException(e);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            try {
                mMediaRecorder.stop();
            } catch (Exception e) {
            }
            mMediaRecorder.release();
            mMediaRecorder = null;
        }

        // Retrieve SPS & PPS & ProfileId with MP4Config
        MP4Config config = new MP4Config(testFile);

        // Delete dummy video
        File file = new File(testFile);
        if (!file.delete()) {
            Log.e(TAG, "Temp file could not be erased");
        }

        Log.i(TAG, "H264 Test succeded...");

        // Save test result
        if (mPreferences != null) {
            SharedPreferences.Editor editor = mPreferences.edit();
            editor.putString(key,
                    config.getProfileLevel() + "," + config.getB64SPS() + "," + config.getB64PPS());
            editor.commit();
        }

        return config;
    }

    private void startVirtualDisplay(Surface surface) {
        mVirtualDisplay = mMediaProjection.createVirtualDisplay(TAG,
                mQuality.resX, mQuality.resY, mDisplayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null);
    }

    /**
     * Video encoding is done by a MediaRecorder.
     */
    @Override
    protected void encodeWithMediaRecorder() throws IOException, ConfNotSupportedException {
        Log.d(TAG, "Video encoded using the MediaRecorder API");

        // We need a local socket to forward data output by the screen to the packetizer
        createSockets();

        Surface surface = null;

        try {
            mMediaRecorder = new MediaRecorder();
            mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mMediaRecorder.setVideoSize(mQuality.resX, mQuality.resY);
            mMediaRecorder.setVideoFrameRate(mQuality.framerate);

            // The bandwidth actually consumed is often above what was requested
            mMediaRecorder.setVideoEncodingBitRate(mQuality.bitrate);

            // We write the output of the screen in a local socket instead of a file !
            // This one little trick makes streaming feasible quiet simply: data from the screen
            // can then be manipulated at the other end of the socket
            FileDescriptor fd = null;
            if (PIPE_API == PIPE_API_PFD) {
                fd = mParcelWrite.getFileDescriptor();
            } else {
                fd = mSender.getFileDescriptor();
            }
            mMediaRecorder.setOutputFile(fd);

            mMediaRecorder.prepare();
            surface = mMediaRecorder.getSurface();
            mMediaRecorder.start();
        } catch (Exception e) {
            throw new ConfNotSupportedException(e);
        }

        InputStream is = null;

        if (PIPE_API == PIPE_API_PFD) {
            is = new ParcelFileDescriptor.AutoCloseInputStream(mParcelRead);
        } else {
            is = mReceiver.getInputStream();
        }

        // This will skip the MPEG4 header if this step fails we can't stream anything :(
        try {
            byte[] buffer = new byte[4];
            // Skip all atoms preceding mdat atom
            while (!Thread.interrupted()) {
                while (is.read() != 'm') ;
                is.read(buffer, 0, 3);
                if (buffer[0] == 'd' && buffer[1] == 'a' && buffer[2] == 't') {
                    break;
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Couldn't skip mp4 header :/");
            stop();
            throw e;
        }

        // The packetizer encapsulates the bit stream in an RTP stream and send it over the network
        mPacketizer.setInputStream(is);
        mPacketizer.start();
        startVirtualDisplay(surface);

        mStreaming = true;
    }

    /**
     * Video encoding is done by a MediaCodec.
     */
    @Override
    protected void encodeWithMediaCodec() throws IOException {
        Log.d(TAG, "Video encoded using the MediaCodec API with a surface");

        mMediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
                mQuality.resX, mQuality.resY);
        format.setInteger(MediaFormat.KEY_BIT_RATE, mQuality.bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, mQuality.framerate);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, DEFAULT_I_FRAME_INTERVAL);
        // display the very first frame, and recover from bad quality when no new frames
        //format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, DEFAULT_REPEAT_FRAME_DELAY);
        mMediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface surface = mMediaCodec.createInputSurface();
        mMediaCodec.start();

        // The packetizer encapsulates the bit stream in an RTP stream and send it over the network
        mPacketizer.setInputStream(new MediaCodecInputStream(mMediaCodec));
        mPacketizer.start();
        startVirtualDisplay(surface);

        mStreaming = true;
    }
}
