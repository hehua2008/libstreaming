package net.majorkernelpanic.streaming.video;

import net.majorkernelpanic.streaming.MediaStream;

public abstract class BaseVideoStream extends MediaStream {
    /**
     * Sets the configuration of the stream. You can call this method at any time
     * and changes will take effect next time you call {@link #configure()}.
     *
     * @param videoQuality Quality of the stream
     */
    public abstract void setVideoQuality(VideoQuality videoQuality);
}
