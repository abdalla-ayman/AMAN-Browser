Models go here. The app loads them from assets/models/ at runtime.

Required .tflite files
──────────────────────
1. nsfw_mobilenetv3.tflite
   Input : [1, 224, 224, 3] float32 normalised to [-1, 1]
   Output: [1, 5]  → [drawings, hentai, neutral, porn, sexy]
   NSFW score is computed as hentai + porn + sexy.

2. face_detection_short.tflite
   Input : [1, 128, 128, 3] float32 normalised to [-1, 1]
   Output: BlazeFace tensors → bounding boxes + face scores
   Source: MediaPipe Face Detection (short-range) TFLite model.
           Available at: https://ai.google.dev/edge/mediapipe/solutions/vision/face_detector

3. skin_classifier.tflite
   Input : [1, 224, 224, 3] float32 normalised to [-1, 1]
   Output: [1, 2]  → [low_skin, high_skin]
   Source: Fine-tuned MobileNetV3-Small on skin exposure datasets.

4. gender_mobilenetv3.tflite (preferred) or model_gender_q.tflite (fallback)
   Preferred input : [1, 224, 224, 3] float32 RGB normalised to [-1, 1]
   Preferred output: [1, 2]  → [female_probability, male_probability]
   Fallback input  : [1, 128, 128, 3] float32 RGB normalised to [0, 1]
   Fallback output : [1, 2]  → [male_probability, female_probability]
   The app validates the loaded model shape and falls back automatically.
   Source for fallback: https://github.com/shubham0204/Age-Gender_Estimation_TF-Android
      Quantized gender classifier trained on UTKFace face crops.

All models must be:
  - TFLite flat-buffer format (.tflite)
  - No dynamic shapes

See scripts/download_models.sh for automated download of open-source alternatives.
