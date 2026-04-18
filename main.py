"""
Robot Head — Main Entry Point

Usage:
    export GEMINI_API_KEY="your-key-here"
    python main.py

Optional flags:
    --no-head        Run without servo hardware (simulation / audio only)
    --list-audio     List all audio devices and exit
"""

import argparse
import asyncio
import signal
import sys

from head_controller import HeadController
from gemini_live_agent import GeminiLiveAgent


def list_audio_devices():
    """Print all PyAudio devices and exit."""
    import pyaudio
    pa = pyaudio.PyAudio()
    print(f"\n{'Idx':<5} {'Name':<50} {'In':<5} {'Out':<5} {'Rate'}")
    print("-" * 80)
    for i in range(pa.get_device_count()):
        info = pa.get_device_info_by_index(i)
        print(
            f"{i:<5} {info['name'][:48]:<50} "
            f"{int(info['maxInputChannels']):<5} "
            f"{int(info['maxOutputChannels']):<5} "
            f"{int(info['defaultSampleRate'])}"
        )
    pa.terminate()
    print()


async def run(use_hardware: bool):
    head = None
    agent = None

    try:
        # Initialise head hardware (falls back to simulation if library missing)
        head = HeadController()
        if not use_hardware:
            head._kit = None   # force simulation mode regardless of hardware
            print("[main] Running in simulation mode (no servo hardware).")

        agent = GeminiLiveAgent(head_controller=head)

        # Graceful shutdown on SIGINT / SIGTERM
        loop = asyncio.get_event_loop()
        for sig in (signal.SIGINT, signal.SIGTERM):
            loop.add_signal_handler(sig, lambda: agent.stop())

        await agent.run()

    except KeyboardInterrupt:
        print("\n[main] Interrupted.")
    finally:
        if head is not None:
            head.shutdown()
        print("[main] Shutdown complete.")


def main():
    parser = argparse.ArgumentParser(description="Robot Head — Gemini Live Museum Guide")
    parser.add_argument(
        "--no-head",
        action="store_true",
        help="Run without servo hardware (audio + AI only)",
    )
    parser.add_argument(
        "--list-audio",
        action="store_true",
        help="Print available audio devices and exit",
    )
    args = parser.parse_args()

    if args.list_audio:
        list_audio_devices()
        sys.exit(0)

    asyncio.run(run(use_hardware=not args.no_head))


if __name__ == "__main__":
    main()
