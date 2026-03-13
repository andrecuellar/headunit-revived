package com.andrerinas.headunitrevived.aap

import android.content.Context
import com.andrerinas.headunitrevived.aap.protocol.Channel
import com.andrerinas.headunitrevived.decoder.MicRecorder
import com.andrerinas.headunitrevived.main.BackgroundNotification
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings

internal class AapMessageHandlerType(
        private val transport: AapTransport,
        recorder: MicRecorder,
        private val aapAudio: AapAudio,
        private val aapVideo: AapVideo,
        settings: Settings,
        backgroundNotification: BackgroundNotification,
        context: Context) : AapMessageHandler {

    private val aapControl: AapControl = AapControlGateway(transport, recorder, aapAudio, settings, context)
    private val mediaPlayback = AapMediaPlayback(backgroundNotification)
    private val aapNavigation = AapNavigation(context, settings)
    private var videoPacketCount = 0

    @Throws(AapMessageHandler.HandleException::class)
    override fun handle(message: AapMessage) {

        val msgType = message.type
        val flags = message.flags

        // 1. Try processing as Video stream first (ID_VID)
        if (message.channel == Channel.ID_VID) {
             // Send ACK IMMEDIATELY before processing to keep the stream flowing
             // This prevents blocking the message thread if the decoder is slow.
             if (message.type == 0 || message.type == 1) {
                 transport.sendMediaAck(message.channel)
             }
             
             if (aapVideo.process(message)) {
                 videoPacketCount++
                 // ACK was already sent above if it was a media packet
                 return
             }
        }

        // 2. Try processing as Audio stream (Speech, System, Media)
        if (message.isAudio) {
            // Send ACK IMMEDIATELY before processing
            if (message.type == 0 || message.type == 1) {
                transport.sendMediaAck(message.channel)
            }

            if (aapAudio.process(message)) {
                return
            }
        }

        // 3. Media Playback Status (separate channel)
        if (message.channel == Channel.ID_MPB && msgType > 31) {
            mediaPlayback.process(message)
            return
        }

        // 4. Navigation (turn-by-turn from any AA nav app)
        // Process only payload messages on NAV channel (>31).
        // Control/handshake messages on NAV channel must pass through to AapControl.
        if (message.channel == Channel.ID_NAV && msgType > 31) {
            if (aapNavigation.process(message)) {
                return
            }
        }

        // 5. Control Message Fallback
        if (msgType in 0..31 || msgType in 32768..32799 || msgType in 65504..65535) {
            try {
                aapControl.execute(message)
            } catch (e: Exception) {
                AppLog.e(e)
                throw AapMessageHandler.HandleException(e)
            }
        } else {
            AppLog.e("Unknown msg_type: %d, flags: %d, channel: %d", msgType, flags, message.channel)
        }
    }
}
