"""Generates the launcher's UI sounds (stdlib only): python make_sounds.py

nav.wav   - moving focus: a tiny, soft, high "tick"
click.wav - opening something: a short, rounded, slightly lower "tock"

Both are deliberately quiet and short with smooth attacks, so they never click harshly.
"""
import math
import struct
import wave

RATE = 44100


def tone(path, length, partials, attack, decay, gain):
    """partials: (frequency Hz, relative amplitude); exponential decay after a short attack."""
    n = int(RATE * length)
    samples = []
    for i in range(n):
        t = i / RATE
        env = (t / attack) if t < attack else math.exp(-(t - attack) / decay)
        v = sum(a * math.sin(2 * math.pi * f * t) for f, a in partials)
        samples.append(v * env * gain)
    peak = max(abs(s) for s in samples) or 1
    with wave.open(path, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b''.join(struct.pack('<h', int(32767 * gain * s / peak)) for s in samples))


# Navigation: 30 ms, around 2.6 kHz with a softer octave below, very quiet
tone('nav.wav', 0.030, [(2600, 1.0), (1300, 0.35)], attack=0.0015, decay=0.007, gain=0.16)
# Click: 70 ms, rounded mid tone with a touch of body, a little more present than nav
tone('click.wav', 0.070, [(880, 1.0), (1320, 0.4), (440, 0.25)], attack=0.002, decay=0.018, gain=0.24)
