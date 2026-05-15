#!/bin/bash
# ============================================================
#  Gemini Live Chat — Raspberry Pi 5 Setup Script
#  Run this on a fresh Pi OS installation
#  Usage: chmod +x setup.sh && ./setup.sh
# ============================================================

set -e

echo "=========================================="
echo "  Gemini Live Chat — Pi 5 Setup"
echo "=========================================="

# ── 1. System Update ──────────────────────────────────────
echo ""
echo "[1/6] Updating system packages..."
sudo apt update && sudo apt full-upgrade -y

# ── 2. Install Dependencies ──────────────────────────────
echo ""
echo "[2/6] Installing system dependencies..."
sudo apt install -y \
  git curl wget vim \
  python3-pip python3-venv \
  portaudio19-dev ffmpeg \
  libespeak1 espeak espeak-ng \
  v4l-utils alsa-utils pulseaudio-utils \
  libopencv-dev python3-opencv

# ── 3. Configure Audio ───────────────────────────────────
echo ""
echo "[3/6] Checking audio devices..."
echo ""
echo "  Playback devices:"
aplay -l 2>/dev/null || echo "    No playback devices found"
echo ""
echo "  Capture devices:"
arecord -l 2>/dev/null || echo "    No capture devices found"
echo ""
echo "  If your USB audio device isn't the default, run:"
echo "    raspi-config → System Options → Audio"

# ── 4. Configure Camera ─────────────────────────────────
echo ""
echo "[4/6] Checking camera..."
if ls /dev/video* >/dev/null 2>&1; then
    echo "  Camera detected:"
    v4l2-ctl --list-devices 2>/dev/null || ls -la /dev/video*
else
    echo "  No camera detected at /dev/video*"
    echo "  If using Pi Camera (CSI), enable it:"
    echo "    sudo raspi-config → Interface Options → Legacy Camera → Enable"
    echo "  Then reboot and run this script again."
fi

# ── 5. Create Virtual Environment ────────────────────────
echo ""
echo "[5/6] Setting up Python virtual environment..."
mkdir -p ~/gemini-live
cd ~/gemini-live

if [ ! -d "venv" ]; then
    python3 -m venv venv
    echo "  Virtual environment created."
else
    echo "  Virtual environment already exists."
fi

source venv/bin/activate
pip install --upgrade pip

# ── 6. Install Python Packages ──────────────────────────
echo ""
echo "[6/6] Installing Python packages..."
pip install \
  google-generativeai \
  opencv-python-headless \
  numpy \
  Pillow \
  sounddevice \
  pygame

# ── Copy the main script ────────────────────────────────
echo ""
echo "Copying gemini_live.py to ~/gemini-live/..."
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/gemini_live.py" ]; then
    cp "$SCRIPT_DIR/gemini_live.py" ~/gemini-live/
else
    echo "  Note: gemini_live.py not found next to setup.sh."
    echo "  Place it manually in ~/gemini-live/"
fi

# ── Done ─────────────────────────────────────────────────
echo ""
echo "=========================================="
echo "  Setup Complete!"
echo "=========================================="
echo ""
echo "Next steps:"
echo ""
echo "  1. Set your Gemini API key:"
echo "     export GEMINI_API_KEY=\"your-key-here\""
echo "     # Or persist it:"
echo "     echo 'export GEMINI_API_KEY=\"your-key-here\"' >> ~/.bashrc"
echo "     source ~/.bashrc"
echo ""
echo "  2. Get your key from:"
echo "     https://aistudio.google.com/apikey"
echo ""
echo "  3. Run Gemini Live Chat:"
echo "     cd ~/gemini-live"
echo "     source venv/bin/activate"
echo "     python gemini_live.py"
echo ""
echo "  4. Press Ctrl+C to stop the chat."
echo ""
