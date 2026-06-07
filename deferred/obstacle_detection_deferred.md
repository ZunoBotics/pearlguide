# Obstacle Detection — Deferred Items

## Status
Detection infrastructure is implemented. Motor enforcement actions and full YOLO pipeline
are deferred pending hardware integration.

---

## 1. YOLOv8-nano Object Classifier (Quest 3 Hexagon NPU)

**Deferred:** Requires model conversion and NPU delegate setup.

**Steps when ready:**
1. Export YOLOv8-nano to ONNX: `yolo export model=yolov8n.pt format=onnx`
2. Convert ONNX → TFLite with int8 quantization:
   ```
   tflite_convert --output_file=yolov8n.tflite \
     --graph_def_file=yolov8n.onnx --inference_type=QUANTIZED_UINT8
   ```
3. Place `yolov8n.tflite` in `QuestHeadApp/app/src/main/assets/`
4. Load in `ObstacleDetector.kt` using TFLite Java API
5. Enable Hexagon NPU delegate: `TfLiteHexagonDelegateCreate(null)` — requires Qualcomm Hexagon SDK
6. Replace the placeholder heuristic in `ObstacleDetector.detect()` with real bounding boxes

**Target classes:** person, wall, furniture, door, staircase, floor_object

---

## 2. Quest 3 Scene API Depth Sensor (Hardware IR Depth)

**Deferred:** Requires Quest Platform SDK (com.oculus.sdk) integration.

Current implementation uses CPU-based stereo block-matching which is inaccurate.
The Quest 3 has a hardware depth sensor (Time of Flight) accessible via:

```kotlin
// Quest Platform SDK
import com.oculus.vrapi.VrApi
import com.oculus.sdk.scene.ScenePlane
```

**Steps when ready:**
1. Add `com.oculus.sdk:scene:*` dependency to build.gradle
2. Initialize Scene API in MainActivity
3. Feed depth frames to `ObstacleDetector` instead of using `StereoDepthEstimator`
4. Replace `StereoDepthEstimator.compute()` call in `ObstacleDetector.kt`

---

## 3. Staircase Detection (Critical Safety)

**Deferred:** Needs accurate depth data (item 2 above).

**Algorithm (implement after depth sensor is wired):**
- Sample depth values along a horizontal scanline at 60% of frame height (floor plane)
- If any 3 consecutive pixels show a depth drop > 15 cm → classify as staircase
- Immediately call `DetectionReporter.onFramesAvailable()` with staircase result
- Do NOT debounce staircase alerts — report every detection

---

## 4. Motor Stop / Slow Actions (Pi 5)

**Deferred:** Requires Raspberry Pi 5 motor controller integration.

When Pi 5 is integrated:
- Danger zone (< 60 cm): Pi receives alert via MQTT `robot/obstacles/danger` → immediate motor stop
- Caution zone (60–100 cm): Pi reduces speed to 30% of current velocity
- Staircase: Pi receives `robot/obstacles/staircase_detected` → full motor lockout until operator releases
- Clear zone (> 100 cm): normal motor operation resumes

**MQTT topics to implement on Pi side:**
```
robot/obstacles/danger        { type, direction, distanceCm, confidence }
robot/obstacles/caution       { type, direction, distanceCm, confidence }
robot/obstacles/staircase_detected { direction, distanceCm, confidence }
robot/obstacles/clear
```

---

## 5. Dynamic Path Reporting

**Deferred:** Compute forward/left/right clearance map and recommend direction.
Spec: `nexus_remaining_features.md` Section 2.5
