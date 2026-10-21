"""Writes the two navigation clicks (res/raw/tick.wav, open.wav): short, soft sine blips.

Run from the repo root: python tools/make_sounds.py
"""
import math
import struct
import wave
from pathlib import Path

RAW = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res" / "raw"
RATE = 22050


def blip(name, freqs, ms, amp):
    n = int(RATE * ms / 1000)
    frames = bytearray()
    for i in range(n):
        t = i / RATE
        attack = min(1.0, i / (RATE * 0.004))
        decay = math.exp(-t * 38)
        v = sum(math.sin(2 * math.pi * f * t) for f in freqs) / len(freqs)
        frames += struct.pack("<h", int(32767 * amp * attack * decay * v))
    RAW.mkdir(parents=True, exist_ok=True)
    with wave.open(str(RAW / name), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(bytes(frames))


if __name__ == "__main__":
    blip("tick.wav", [1760], 45, 0.5)
    blip("open.wav", [880, 1320], 140, 0.5)
    print("tick.wav y open.wav listos")
