YAMNet audio classifier model
=============================

The on-device siren/horn detector (YamnetClassifier) loads `yamnet.tflite` from this assets folder.

This binary (~4 MB) is intentionally NOT committed. Drop the TF-Hub YAMNet model *with metadata*
here as `yamnet.tflite`:

  https://www.kaggle.com/models/google/yamnet/tfLite  (the "classification" / with-metadata variant)
  (or `tensorflow-lite-task-audio` compatible YAMNet)

Without this file the app still records audio to `audio.wav` and logs `/audio/microphone` metadata;
only live siren/horn DrivingEvent detection is disabled (YamnetClassifier.available == false).
