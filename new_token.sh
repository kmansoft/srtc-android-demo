#/bin/bash

TOKEN=$(aws ivs-realtime create-participant-token --duration 1440 --stage-arn arn:aws:ivs:us-west-2:422437114350:stage/1nM0M3foFyNa | jq -r ".participantToken.token")

if [ -z "$TOKEN" ]
then
	echo "Failed to create a token"
	exit 1
fi

TOKEN_HEADER=$(echo "$TOKEN" | cut -d '.' -f 1)
TOKEN_CLAIMS=$(echo "$TOKEN" | cut -d '.' -f 2)
TOKEN_SIGNTR=$(echo "$TOKEN" | cut -d '.' -f 3)

echo "$TOKEN_CLAIMS" | base64 -d | jq

adb -e shell am start -a "android.intent.action.VIEW" -d "srtc://ignored/?header=$TOKEN_HEADER\&claims=$TOKEN_CLAIMS\&signature=$TOKEN_SIGNTR" org.kman.srtctest/.MainActivity
