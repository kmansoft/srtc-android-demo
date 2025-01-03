#/bin/bash

SERVER="http://10.0.2.2:8080/whip"
TOKEN="none"

adb -e shell am start -a "android.intent.action.VIEW" -d "srtc://pion/?server=$SERVER\&token=$TOKEN" org.kman.srtctest/.MainActivity
