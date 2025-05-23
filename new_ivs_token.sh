#/bin/bash

set -e

TOKEN=$(aws ivs-realtime create-participant-token --duration 4320 --stage-arn arn:aws:ivs:us-west-2:422437114350:stage/R0uaOh27PasU --output json | jq -r ".participantToken.token")

if [ -z "$TOKEN" ]
then
	echo "Failed to create a token"
	exit 1
fi

TOKEN_HEADER=$(echo "$TOKEN" | cut -d '.' -f 1)
TOKEN_CLAIMS=$(echo "$TOKEN" | cut -d '.' -f 2)
TOKEN_SIGNTR=$(echo "$TOKEN" | cut -d '.' -f 3)

adb -e shell am start -a "android.intent.action.VIEW" -d "srtc://ivs/?header=$TOKEN_HEADER\&claims=$TOKEN_CLAIMS\&signature=$TOKEN_SIGNTR" org.kman.srtctest/.MainActivity
