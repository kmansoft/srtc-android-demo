### A demo for the srtc WebRTC library

This is a demo for "srtc" a [simple WebRTC library](https://github.com/kmansoft/srtc).

It is an Android app which captures camera and microphone and publishes them to a WebRTC stream negotiated via WHIP.

Tested with Pion and Amazon IVS (Interactive Video Service) using real devices as well as the Android Emulator.

Video is published using H264 using the highest profile Id that can be negotiated.

Audio is published using Opus.

Should work with other WHIP implementations too.

#### Checking out and building

Please clone this repository with `--recurse-submodules` to bring in the srtc library and the Opus audio encoder.

Then build the project in Android Studio. For running I suggest starting with the emulator first.

#### Testing with Pion

Run pion by changing the directory into `./src/main/cpp/srtc/pion-webrtc-examples-whip-whep` and runnig `run.sh`.

Open your browser to `http://localhost:8080` and click "Subscribe", you should see Peer Connection State = "connected"
and a black video view with a spinning progress wheel.

Now run `new_pion_token.sh` at the root of this repository to configure the Android app to use the locally
running Pion. Click "Connect" and you should see Android's synthetic video feed in the web browser.

#### Testing with Amazon IVS

You will need an AWS account. Note that IVS Realtime is not included in the free trial of AWS.

Install AWS CLI and configure it for your account.

Use the AWS Console or CLI to create an IVS Realtime Stage.

Edit `new_ivs_token` to use your Stage's ARN, and run the file to generate a new token and configure the Android app
to use that token. Click "Connect" and you should be able to open the Stage in the AWS Console and Subscribe to its video feed.

When publishing to IVS, you can enable Simulcast.
