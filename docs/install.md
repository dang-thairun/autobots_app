adb connect 192.168.1.147:5555

./gradlew :androidApp:assembleDebug
adb -s 192.168.1.147:5555 install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk