adb logcat -c
adb logcat -v time > crash.log &
LOG_PID=$!

./gradlew :androidApp:installDebug
adb shell am start -n com.autobots.camera/com.autobots.MainActivity

# รอ reproduce crash แล้วกด Ctrl+C
kill $LOG_PID

grep -E "FATAL|AndroidRuntime|Exception|com.autobots" crash.log