#!/bin/bash
# ============================================================
#  Bluetooth Audio Setup — Raspberry Pi 5
#  Pairs, connects, and configures BT mic + speaker for
#  Gemini Live Chat
#
#  Usage: chmod +x bt_audio_setup.sh && ./bt_audio_setup.sh
# ============================================================

echo "=========================================="
echo "  Bluetooth Audio Setup for Pi 5"
echo "=========================================="

# ── Ensure PulseAudio/PipeWire is running ────────────────
echo ""
echo "[1/4] Checking audio service..."
if systemctl --user is-active pulseaudio >/dev/null 2>&1; then
    echo "  PulseAudio is running."
elif systemctl --user is-active pipewire pipewire-pulse >/dev/null 2>&1; then
    echo "  PipeWire is running."
else
    echo "  Starting PulseAudio..."
    pulseaudio --start || echo "  PulseAudio may already be running."
fi

# ── Ensure bluetooth service is running ──────────────────
echo ""
echo "[2/4] Checking Bluetooth service..."
sudo systemctl enable bluetooth
sudo systemctl start bluetooth
echo "  Bluetooth service is active."

# ── Pair and connect ─────────────────────────────────────
echo ""
echo "[3/4] Bluetooth pairing..."
echo ""
echo "  Put your Bluetooth device in PAIRING MODE now."
echo "  Then follow the prompts below."
echo ""
echo "  Common commands inside bluetoothctl:"
echo "    scan on              — Search for devices"
echo "    devices              — List found devices"
echo "    pair XX:XX:...       — Pair a device"
echo "    connect XX:XX:...    — Connect to it"
echo "    trust XX:XX:...      — Auto-connect on boot"
echo "    quit                 — Exit"
echo ""

bluetoothctl

# ── Set Bluetooth as default audio ───────────────────────
echo ""
echo "[4/4] Configuring audio defaults..."
echo ""

echo "  Available output devices (speakers):"
echo "  ------------------------------------"
pactl list sinks short 2>/dev/null
echo ""

echo "  Available input devices (microphones):"
echo "  --------------------------------------"
pactl list sources short 2>/dev/null
echo ""

# Auto-detect BT sink and source
BT_SINK=$(pactl list sinks short 2>/dev/null | grep bluez | head -1 | awk '{print $2}')
BT_SOURCE=$(pactl list sources short 2>/dev/null | grep bluez | head -1 | awk '{print $2}')

if [ -n "$BT_SINK" ]; then
    echo "  Setting BT speaker as default: $BT_SINK"
    pactl set-default-sink "$BT_SINK"
else
    echo "  ⚠ No Bluetooth speaker found. Using current default."
fi

if [ -n "$BT_SOURCE" ]; then
    echo "  Setting BT mic as default: $BT_SOURCE"
    pactl set-default-source "$BT_SOURCE"
else
    echo "  ⚠ No Bluetooth mic found."
    echo "    Your BT device may need HSP/HFP profile for mic input."
    echo "    Run: pactl list cards"
    echo "    Then: pactl set-card-profile <card_name> headset_head_unit"
fi

# ── Quick test ────────────────────────────────────────────
echo ""
echo "=========================================="
echo "  Quick Audio Test"
echo "=========================================="
echo ""
read -p "  Test speaker? (y/n) " -n 1 -r
echo ""
if [[ $REPLY =~ ^[Yy]$ ]]; then
    speaker-test -t wav -c 2 -l 1 2>/dev/null && echo "  ✅ Speaker test done"
fi

echo ""
read -p "  Test microphone (5 sec recording)? (y/n) " -n 1 -r
echo ""
if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo "  Recording for 5 seconds... Speak now!"
    parecord -d 5 ~/bt_mic_test.wav 2>/dev/null
    echo "  Playing back..."
    paplay ~/bt_mic_test.wav 2>/dev/null && echo "  ✅ Mic test done"
    rm -f ~/bt_mic_test.wav
fi

echo ""
echo "=========================================="
echo "  Setup Complete!"
echo "=========================================="
echo ""
echo "  If mic is not working over Bluetooth:"
echo "    1. Check: pactl list cards"
echo "    2. Switch to HSP/HFP: pactl set-card-profile <card> headset_head_unit"
echo "    3. Note: HSP/HFP has lower audio quality but enables mic"
echo ""
echo "  To run Gemini Live:"
echo "    cd ~/gemini-live"
echo "    source venv/bin/activate"
echo "    python gemini_live_audio.py"
echo ""