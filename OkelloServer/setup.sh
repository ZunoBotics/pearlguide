#!/bin/bash
# Run on the Pi: bash setup.sh
set -e
cd /home/okello/OkelloServer

echo "=== Creating Python venv ==="
python3 -m venv venv
./venv/bin/pip install --upgrade pip
./venv/bin/pip install -r requirements.txt

echo "=== Exporting YOLOv8n to TFLite (for Quest assets) ==="
./venv/bin/pip install ultralytics
./venv/bin/python3 -c "
from ultralytics import YOLO
import shutil, os
m = YOLO('yolov8n.pt')
m.export(format='tflite', imgsz=320)
# Find and copy the float32 tflite
import glob
files = glob.glob('yolov8n_saved_model/*.tflite')
if files:
    shutil.copy(files[0], 'yolov8n_float32.tflite')
    print('Exported:', files[0], '→ yolov8n_float32.tflite')
    print('Size:', os.path.getsize('yolov8n_float32.tflite'), 'bytes')
"

echo "=== Installing systemd service ==="
sudo cp okello-server.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable okello-server
sudo systemctl start okello-server

echo "=== Adding okello to dialout group (for serial port access) ==="
sudo usermod -aG dialout okello

echo ""
echo "=== Done! ==="
echo "Server: http://$(hostname -I | awk '{print $1}'):5000/ping"
echo "Logs:   journalctl -u okello-server -f"
echo ""
echo "Copy yolov8n_float32.tflite to Quest app assets:"
echo "  scp okello@$(hostname -I | awk '{print $1}'):/home/okello/OkelloServer/yolov8n_float32.tflite ."
