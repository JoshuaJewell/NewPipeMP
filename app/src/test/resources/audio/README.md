# Test fixtures

These are 1-second silent audio files in four common containers, used by
`util/rating/Rating*Test.kt`.

To regenerate:

    cd app/src/test/resources/audio
    ffmpeg -f lavfi -i anullsrc=r=44100:cl=stereo -t 1 -b:a 128k silence-1s.mp3
    ffmpeg -f lavfi -i anullsrc=r=44100:cl=stereo -t 1 silence-1s.flac
    ffmpeg -f lavfi -i anullsrc=r=48000:cl=stereo -t 1 -c:a libopus -b:a 96k silence-1s.opus
    ffmpeg -f lavfi -i anullsrc=r=44100:cl=stereo -t 1 -c:a aac -b:a 128k silence-1s.m4a

All files committed as-is; do not edit.
