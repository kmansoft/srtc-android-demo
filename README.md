### A demo for the srtc WebRTC library

This is a demo for "srtc" a [simple WebRTC library](https://github.com/kmansoft/srtc).

It is an Android app which captures camera and microphone and publishes them to a WebRTC stream negotiated via WHIP.

Tested with Pion and Amazon IVS (Interactive Video Service) using real devices as well as the Android Emulator.

Video is published using H264 using the highest profile Id that can be negotiated.

Audio is published using Opus.

Should work with other WHIP implementations too.
