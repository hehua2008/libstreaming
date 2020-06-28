/*
 * Copyright (C) 2011-2015 GUIGUI Simon, fyhertz@gmail.com
 *
 * This file is part of libstreaming (https://github.com/fyhertz/libstreaming)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.majorkernelpanic.streaming.rtp;

import android.annotation.SuppressLint;
import android.util.Log;

import java.io.IOException;

/**
 * RFC 3984.
 *
 * H.264 streaming over RTP.
 *
 * Must be fed with an InputStream containing H.264 NAL units preceded by their length (4 bytes).
 * The stream must start with mpeg4 or 3gpp header, it will be skipped.
 */
public class H264Packetizer extends AbstractPacketizer implements Runnable {
    public static final String TAG = H264Packetizer.class.getSimpleName();

    /**
     *  NALU header
     *   +---------------+
     *   |0|1|2|3|4|5|6|7|
     *   +-+-+-+-+-+-+-+-+
     *   |F|NRI|   Type  |
     *   +---------------+
     */

    public static final int NAL_SLICE           = 1;
    public static final int NAL_DPA             = 2;
    public static final int NAL_DPB             = 3;
    public static final int NAL_DPC             = 4;
    public static final int NAL_IDR_SLICE       = 5;
    public static final int NAL_SEI             = 6;
    public static final int NAL_SPS             = 7;
    public static final int NAL_PPS             = 8;
    public static final int NAL_AUD             = 9;
    public static final int NAL_END_SEQUENCE    = 10;
    public static final int NAL_END_STREAM      = 11;
    public static final int NAL_FILLER_DATA     = 12;
    public static final int NAL_SPS_EXT         = 13;
    public static final int NAL_AUXILIARY_SLICE = 19;
    public static final int STAP_A              = 24;
    public static final int STAP_B              = 25;
    public static final int MTAP16              = 26;
    public static final int MTAP24              = 27;
    public static final int FU_A                = 28;
    public static final int FU_B                = 29;

    public static final int MASK_00011111 = 0x1F;
    public static final int MASK_01100000 = 0x60;

    public static final int NAL_SLICE_START_FLAG = 0x80; // 10000000
    public static final int NAL_SLICE_END_FLAG = 0x40;   // 01000000

    public static final int STREAM_TYPE_MEDIA_RECORDER = 0;
    public static final int STREAM_TYPE_MEDIA_CODEC = 1;
    public static final int STREAM_TYPE_UNKNOWN = 2;

    private Thread t = null;
    private int naluLength = 0;
    private long delay = 0, oldtime = 0;
    private byte[] sps = null, pps = null, stapa = null;
    private int count = 0;
    private int streamType = STREAM_TYPE_MEDIA_CODEC;

    private final byte[] header = new byte[5];
    private final Statistics stats = new Statistics();

    public H264Packetizer() {
        super();
        socket.setClockFrequency(90000);
    }

    public void start() {
        if (t == null) {
            t = new Thread(this);
            t.start();
        }
    }

    public void stop() {
        if (t != null) {
            try {
                is.close();
            } catch (IOException ignored) {
            }
            t.interrupt();
            try {
                t.join();
            } catch (InterruptedException ignored) {
            }
            t = null;
        }
    }

    public void setStreamParameters(byte[] pps, byte[] sps) {
        this.pps = pps;
        this.sps = sps;

        //   0               1               2               3
        //   0 1 2 3 4 5 6 7 0 1 2 3 4 5 6 7 0 1 2 3 4 5 6 7 0 1 2 3 4 5 6 7
        //  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        //  |                          RTP Header                           |
        //  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        //  |STAP-A NAL HDR |         NALU 1 Size           | NALU 1 HDR    |
        //  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        //  |                         NALU 1 Data                           |
        //  :                                                               :
        //  +               +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        //  |               | NALU 2 Size                   | NALU 2 HDR    |
        //  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        //  |                         NALU 2 Data                           |
        //  :                                                               :
        //  |                               +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        //  |                               :...OPTIONAL RTP padding        |
        //  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        // An e.g of an RTP packet including an STAP-A containing two single-time aggregation units

        // A STAP-A NAL (NAL type 24) containing the sps and pps of the stream
        if (pps != null && sps != null) {
            // STAP-A NAL header + NALU 1 (SPS) size + NALU 2 (PPS) size = 5 bytes
            stapa = new byte[sps.length + pps.length + 5];

            // STAP-A NAL header is 24
            //  +---------------+
            //  |0|1|2|3|4|5|6|7|
            //  +-+-+-+-+-+-+-+-+
            //  |F|NRI| Type(24)|
            //  +---------------+
            stapa[0] = STAP_A;

            // Write NALU 1 size into the array (NALU 1 is the SPS).
            stapa[1] = (byte) (sps.length >> 8);
            stapa[2] = (byte) (sps.length & 0xFF);

            // Write NALU 2 size into the array (NALU 2 is the PPS).
            stapa[sps.length + 3] = (byte) (pps.length >> 8);
            stapa[sps.length + 4] = (byte) (pps.length & 0xFF);

            // Write NALU 1 into the array, then write NALU 2 into the array.
            System.arraycopy(sps, 0, stapa, 3, sps.length);
            System.arraycopy(pps, 0, stapa, 5 + sps.length, pps.length);
        }
    }

    public void run() {
        long duration = 0;
        Log.d(TAG, "H264 packetizer started !");
        stats.reset();
        count = 0;

        if (is instanceof MediaCodecInputStream) {
            streamType = STREAM_TYPE_MEDIA_CODEC;
            socket.setCacheSize(0);
        } else {
            streamType = STREAM_TYPE_MEDIA_RECORDER;
            socket.setCacheSize(400);
        }

        try {
            while (!Thread.interrupted()) {
                oldtime = System.nanoTime();
                // We read a NAL units from the input stream and we send them
                send();
                // We measure how long it took to receive NAL units from the phone
                duration = System.nanoTime() - oldtime;

                stats.push(duration);
                // Computes the average duration of a NAL unit
                delay = stats.average();
                //Log.d(TAG,"duration: "+duration/1000000+" delay: "+delay/1000000);
            }
        } catch (IOException | InterruptedException ignored) {
        }

        Log.d(TAG, "H264 packetizer stopped !");
    }

    /**
     * Reads a NAL unit in the FIFO and sends it.
     * If it is too big, we split it in FU-A units (RFC 3984).
     */
    @SuppressLint("NewApi")
    private void send() throws IOException, InterruptedException {
        int sum = 1, len = 0, type;

        if (streamType == STREAM_TYPE_MEDIA_RECORDER) {
            // NAL units are preceeded by their length, we parse the length
            // e.g.
            // 00 00 00 19 06 [... 25 bytes...] 00 00 24 aa 65 [... 9386 bytes...]
            // SEI                              IDR Slice
            fill(header, 0, 5);
            ts += delay;
            naluLength = (header[0] & 0xFF) << 24
                    | (header[1] & 0xFF) << 16
                    | (header[2] & 0xFF) << 8
                    | header[3] & 0xFF;
            if (naluLength > 100000 || naluLength < 0) {
                resync();
            }
        } else if (streamType == STREAM_TYPE_MEDIA_CODEC) {
            // NAL units are preceeded with 0x00000001
            // e.g.
            // 00 00 00 01 06 ... 00 00 00 01 67 ... 00 00 00 01 68 ... 00 00 00 01 65 ...
            // SEI                SPS                PPS                IDR Slice
            fill(header, 0, 5);
            ts = ((MediaCodecInputStream) is).getLastBufferInfo().presentationTimeUs * 1000L;
            //ts += delay;
            naluLength = is.available() + 1;
            if (!(header[0] == 0 && header[1] == 0 && header[2] == 0)) {
                // Turns out, the NAL units are not preceeded with 0x00000001
                Log.e(TAG, "NAL units are not preceeded with 0x00000001");
                streamType = STREAM_TYPE_UNKNOWN;
                return;
            }
        } else {
            // Nothing preceededs the NAL units
            fill(header, 0, 1);
            header[4] = header[0];
            ts = ((MediaCodecInputStream) is).getLastBufferInfo().presentationTimeUs * 1000L;
            //ts += delay;
            naluLength = is.available() + 1;
        }

        // Parses the NAL unit type
        type = header[4] & MASK_00011111;

        // The stream already contains NAL unit type 7 or 8, we don't need
        // to add them to the stream ourselves
        if (type == NAL_SPS || type == NAL_PPS) {
            Log.v(TAG, "SPS or PPS present in the stream.");
            count++;
            if (count > 4) {
                sps = null;
                pps = null;
            }
        }

        // We send two packets containing NALU type 7 (SPS) and 8 (PPS)
        // Those should allow the H264 stream to be decoded even if no SDP was sent to the decoder.
        if (type == NAL_IDR_SLICE && sps != null && pps != null) {
            buffer = socket.requestBuffer();
            socket.markNextPacket();
            socket.updateTimestamp(ts);
            System.arraycopy(stapa, 0, buffer, RTPHL, stapa.length);
            super.send(RTPHL + stapa.length);
        }

        //Log.d(TAG,"- Nal unit length: " + naluLength + " delay: "+delay/1000000+" type: "+type);

        if (naluLength <= MAXPACKETSIZE - RTPHL - 2) {
            // Small NAL unit => Single NAL unit

            //   0               1               2               3
            //   0 1 2 3 4 5 6 7 0 1 2 3 4 5 6 7 0 1 2 3 4 5 6 7 0 1 2 3 4 5 6 7
            //  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            //  |F|NRI|  type   |                                               |
            //  +-+-+-+-+-+-+-+-+                                               |
            //  |                                                               |
            //  |               Bytes 2..n of a Single NAL unit                 |
            //  |                                                               |
            //  |                               +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            //  |                               :...OPTIONAL RTP padding        |
            //  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            //         RTP payload format for single NAL unit packet

            buffer = socket.requestBuffer();
            buffer[RTPHL] = header[4];
            len = fill(buffer, RTPHL + 1, naluLength - 1);
            socket.updateTimestamp(ts);
            socket.markNextPacket();
            super.send(naluLength + RTPHL);
            //Log.d(TAG,"----- Single NAL unit - len:"+len+" delay: "+delay);
        } else {
            // Large NAL unit => Split nal unit

            //   0               1               2               3
            //   0 1 2 3 4 5 6 7 0 1 2 3 4 5 6 7 0 1 2 3 4 5 6 7 0 1 2 3 4 5 6 7
            //  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            //  | FU indicator  |   FU header  |                                |
            //  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+                               |
            //  |                                                               |
            //  |                         FU payload                            |
            //  |                                                               |
            //  |                               +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            //  |                               :...OPTIONAL RTP padding        |
            //  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            //                  RTP payload format for FU-A

            // Set FU-A header
            //  +---------------+
            //  |0|1|2|3|4|5|6|7|
            //  +-+-+-+-+-+-+-+-+
            //  |S|E|R|Type(1-23)|
            //  +---------------+
            header[1] = (byte) (header[4] & MASK_00011111);  // FU header type
            header[1] += NAL_SLICE_START_FLAG; // Start bit

            // Set FU-A indicator
            //  +---------------+
            //  |0|1|2|3|4|5|6|7|
            //  +-+-+-+-+-+-+-+-+
            //  |F|NRI|Type(24-29)|
            //  +---------------+
            header[0] = (byte) ((header[4] & MASK_01100000) & 0xFF); // FU indicator NRI
            header[0] += FU_A;

            while (sum < naluLength) {
                buffer = socket.requestBuffer();
                buffer[RTPHL] = header[0]; // FU indicator
                buffer[RTPHL + 1] = header[1]; // FU header
                socket.updateTimestamp(ts);
                if ((len = fill(buffer, RTPHL + 2,
                        Math.min(naluLength - sum, MAXPACKETSIZE - RTPHL - 2))) < 0) {
                    return;
                }
                sum += len;
                // Last packet before next NAL
                if (sum >= naluLength) {
                    // End bit on
                    buffer[RTPHL + 1] += NAL_SLICE_END_FLAG; // FU header
                    socket.markNextPacket();
                }
                super.send(len + RTPHL + 2);
                // Switch start bit
                header[1] = (byte) (header[1] & ~NAL_SLICE_START_FLAG);
                //Log.d(TAG,"----- FU-A unit, sum:"+sum);
            }
        }
    }

    private int fill(byte[] buffer, int offset, int length) throws IOException {
        int sum = 0, len;
        while (sum < length) {
            len = is.read(buffer, offset + sum, length - sum);
            if (len < 0) {
                throw new IOException("End of stream");
            } else {
                sum += len;
            }
        }
        return sum;
    }

    private void resync() throws IOException {
        int type;

        Log.e(TAG, "Packetizer out of sync! Let's try to fix that(NAL length=" + naluLength + ")");

        while (true) {
            header[0] = header[1];
            header[1] = header[2];
            header[2] = header[3];
            header[3] = header[4];
            header[4] = (byte) is.read();

            type = header[4] & MASK_00011111;

            if (type == NAL_IDR_SLICE || type == NAL_SLICE) {
                naluLength = (header[0] & 0xFF) << 24
                        | (header[1] & 0xFF) << 16
                        | (header[2] & 0xFF) << 8
                        | header[3] & 0xFF;
                if (naluLength > 0 && naluLength < 100000) {
                    oldtime = System.nanoTime();
                    Log.e(TAG, "A NAL unit may have been found in the bit stream !");
                    break;
                } else if (naluLength == 0) {
                    Log.e(TAG, "NAL unit with NULL size found...");
                } else if (header[0] == 0xFF
                        && header[1] == 0xFF
                        && header[2] == 0xFF
                        && header[3] == 0xFF) {
                    Log.e(TAG, "NAL unit with 0xFFFFFFFF size found...");
                }
            }
        }
    }
}
