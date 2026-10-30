#!/usr/bin/env python3
"""Generate original, permissively-licensed (CC0) audio assets for Weather Doge.

All melodies are composed here from scratch (simple chiptune-style synthesis).
No Nintendo recordings, no copyrighted samples. Output: 16-bit PCM WAV mono 22050Hz.
"""
import math, struct, wave, os, random

SR = 22050
OUT = os.path.join(os.path.dirname(__file__), "out")
os.makedirs(OUT, exist_ok=True)

def midi(n):  # midi note -> Hz
    return 440.0 * (2 ** ((n - 69) / 12.0))

def env(t, dur, a=0.01, r=0.1):
    """attack/release envelope"""
    if t < a:
        return t / a
    if t > dur - r:
        return max(0.0, (dur - t) / r)
    return 1.0

def tone(freq, t, kind="tri", duty=0.5):
    p = (freq * t) % 1.0
    if kind == "sine":
        return math.sin(2 * math.pi * freq * t)
    if kind == "tri":
        return 2.0 * abs(2.0 * p - 1.0) - 1.0
    if kind == "square":
        return 1.0 if p < duty else -1.0
    if kind == "saw":
        return 2.0 * p - 1.0
    return 0.0

def render(notes, gain=0.5, kind="tri", vib=0.0, vibhz=5.0, duty=0.5):
    """notes: list of (start_sec, dur_sec, midi_note, vel)"""
    total = max(s + d for s, d, _, _ in notes)
    buf = []
    n = int(total * SR)
    for i in range(n):
        t = i / SR
        v = 0.0
        for s, d, m, vel in notes:
            if s <= t < s + d:
                f = midi(m)
                if vib > 0:
                    f *= (1 + vib * math.sin(2 * math.pi * vibhz * (t - s)))
                v += tone(f, t - s, kind, duty) * env(t - s, d) * vel
        buf.append(v * gain)
    return buf

def write_wav(name, buf, peak=0.95):
    mx = max(1e-9, max(abs(x) for x in buf))
    sc = peak / mx if mx > peak else 1.0
    with wave.open(os.path.join(OUT, name), "w") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, x * sc)) * 32767)) for x in buf))
    print(name, round(len(buf) / SR, 2), "s")

# ---------------------------------------------------------------------------
# 1) CHAOS MODE music: original cheerful "Wii-inspired" instrumental, 15s loop.
#    Bouncy waltz-ish major-key tune, music-box lead + bouncy bass. Original.
# ---------------------------------------------------------------------------
BPM = 132
B = 60.0 / BPM  # beat

# C major-ish happy progression, lead melody (composed for this project)
# I-V-vi-IV feel in C: C G Am F
def chord(root, kind):
    return {"maj": [0, 4, 7], "min": [0, 3, 7]}[kind]

prog = [("C", "maj"), ("G", "maj"), ("A", "min"), ("F", "maj"),
        ("C", "maj"), ("G", "maj"), ("F", "maj"), ("G", "maj")]
roots = {"C": 48, "G": 55, "A": 57, "F": 53}

lead_notes = [
    # (bar, beat, midi, dur_beats) — a bouncy original melody
    (0, 0.0, 72, 0.5), (0, 0.5, 76, 0.5), (0, 1.0, 79, 1.0), (0, 2.0, 76, 0.5), (0, 2.5, 79, 1.5),
    (1, 0.0, 74, 0.5), (1, 0.5, 79, 0.5), (1, 1.0, 83, 1.0), (1, 2.0, 79, 2.0),
    (2, 0.0, 72, 0.5), (2, 0.5, 76, 0.5), (2, 1.0, 81, 1.0), (2, 2.0, 76, 0.5), (2, 2.5, 72, 1.5),
    (3, 0.0, 69, 0.5), (3, 0.5, 72, 0.5), (3, 1.0, 77, 1.0), (3, 2.0, 72, 2.0),
    (4, 0.0, 79, 0.5), (4, 0.5, 76, 0.5), (4, 1.0, 72, 1.0), (4, 2.0, 76, 1.0), (4, 3.0, 79, 1.0),
    (5, 0.0, 83, 0.5), (5, 0.5, 79, 0.5), (5, 1.0, 74, 1.0), (5, 2.0, 79, 2.0),
    (6, 0.0, 81, 0.5), (6, 0.5, 77, 0.5), (6, 1.0, 72, 1.0), (6, 2.0, 77, 1.0), (6, 3.0, 81, 1.0),
    (7, 0.0, 79, 0.5), (7, 0.5, 74, 0.5), (7, 1.0, 79, 1.0), (7, 2.0, 86, 2.0),  # sparkle finish
]

lead = [(bar * 4 * B + beat * B, dur * B, m, 0.9) for bar, beat, m, dur in lead_notes]
# harmony: soft chord pad every bar
harm = []
for bar, (name, kind) in enumerate(prog):
    for iv in chord(roots[name], kind):
        harm.append((bar * 4 * B, 4 * B * 0.95, roots[name] + iv + 12, 0.22))
# bouncy bass: root on 1 & 3, fifth on 2 & 4
bass = []
for bar, (name, kind) in enumerate(prog):
    r = roots[name] - 12
    bass += [(bar * 4 * B + 0 * B, B * 0.9, r, 0.7),
             (bar * 4 * B + 1 * B, B * 0.9, r + 7, 0.55),
             (bar * 4 * B + 2 * B, B * 0.9, r, 0.7),
             (bar * 4 * B + 3 * B, B * 0.9, r + 7, 0.55)]
# light hi-hat-ish ticks
tick = []
tt = 0.0
while tt < 8 * 4 * B:
    tick.append((tt, 0.03, 96 if int(tt / B) % 2 else 100, 0.25 if int(tt / B) % 2 else 0.4))
    tt += B / 2

tracks = [render(lead, gain=0.42, kind="tri", vib=0.004, vibhz=5.5)]
for extra, g, k in ((harm, 0.30, "sine"), (bass, 0.5, "tri"), (tick, 0.5, "square")):
    tracks.append(render(extra, gain=g, kind=k))
total = max(len(t) for t in tracks)
buf = [0.0] * total
for t in tracks:
    for i in range(len(t)):
        buf[i] += t[i]
# Pad to exactly 15s so the CHAOS MODE soundtrack covers the full sequence
buf += [0.0] * (15 * SR - len(buf))
write_wav("chaos_wii_style_loop.wav", buf)

# ---------------------------------------------------------------------------
# 2) Air horn (original synth: three detuned saw blasts w/ pitch rise)
# ---------------------------------------------------------------------------
horn = []
dur = 0.9
n = int(dur * SR)
for i in range(n):
    t = i / SR
    f = 1.0 + 0.35 * min(1.0, t / 0.25)  # slight rise
    v = (tone(233.0 * f, t, "saw") + tone(277.2 * f, t, "saw") + tone(311.1 * f, t, "saw")) / 3.0
    v += tone(116.5 * f, t, "square") * 0.3
    horn.append(v * env(t, dur, a=0.005, r=0.15) * 0.8)
write_wav("mlg_air_horn.wav", horn)

# ---------------------------------------------------------------------------
# 3) Keyboard Cat riff (original short silly honky-tonk riff, ~2s)
# ---------------------------------------------------------------------------
kc = [(0.0, 0.14, 60, .9), (0.14, 0.14, 64, .9), (0.28, 0.14, 67, .9), (0.42, 0.28, 72, 1.0),
      (0.70, 0.14, 67, .8), (0.84, 0.14, 72, .9), (0.98, 0.42, 76, 1.0),
      (1.40, 0.12, 74, .8), (1.52, 0.12, 72, .8), (1.64, 0.12, 71, .8), (1.76, 0.34, 79, 1.0)]
write_wav("keyboard_cat_riff.wav", render(kc, gain=0.5, kind="square", duty=0.42))

# ---------------------------------------------------------------------------
# 4) Wii-menu-style soft "blip" UI sounds (original)
# ---------------------------------------------------------------------------
blip = [(0.0, 0.09, 84, 0.9), (0.09, 0.13, 91, 0.8)]
write_wav("wii_blip.wav", render(blip, gain=0.4, kind="sine"))
done = [(0.0, 0.08, 79, 0.8), (0.08, 0.08, 84, 0.8), (0.16, 0.2, 91, 0.9)]
write_wav("wii_done.wav", render(done, gain=0.4, kind="sine"))

# ---------------------------------------------------------------------------
# 5) "WOW" chime for MAX DOGE / dial reactions (original)
# ---------------------------------------------------------------------------
wow = [(0.0, 0.5, 88, 0.9), (0.05, 0.5, 93, 0.6)]
write_wav("wow_chime.wav", render(wow, gain=0.35, kind="sine"))

# ---------------------------------------------------------------------------
# 5b) BONK! - original cartoon bonk (woodblock hit + pitch drop), used for
#     BOTH ui taps and doge touch
# ---------------------------------------------------------------------------
import random
random.seed(7)
bonk = []
dur = 0.22
n = int(dur * SR)
for i in range(n):
    t = i / SR
    # pitch sweep down 700->180Hz (the "doink")
    f = 700 * math.exp(-9 * t) + 180
    ph = 0.0
    # integrate freq for phase
    ph = 2 * math.pi * (700 * (1 - math.exp(-9 * t)) / 9 + 180 * t)
    v = math.sin(ph) * 0.8 + math.sin(2.3 * ph) * 0.2
    # transient click at start
    if t < 0.008:
        v += random.uniform(-1, 1) * 0.7
    bonk.append(v * env(t, dur, a=0.001, r=0.12) * 0.9)
write_wav("bonk.wav", bonk)

# ---------------------------------------------------------------------------
# 5c) MEOW - original synthesized cat meow (chaos mode), glottal-ish buzz
# ---------------------------------------------------------------------------
meow = []
dur = 0.5
n = int(dur * SR)
for i in range(n):
    t = i / SR
    x = t / dur
    # meow pitch curve: rise then fall, 500->750->400 Hz
    f = 500 + 350 * math.sin(math.pi * min(1.0, x * 1.15)) - 100 * x
    vib = 1 + 0.02 * math.sin(2 * math.pi * 6 * t)
    f *= vib
    ph = 2 * math.pi * f * t
    # buzzy source filtered feel: saw + 2 harmonics
    v = math.sin(ph) + 0.5 * math.sin(2 * ph) + 0.3 * math.sin(3 * ph) + 0.15 * math.sin(4 * ph)
    # formant-ish emphasis mid-meow
    v *= 0.8 + 0.2 * math.sin(math.pi * x)
    meow.append(v * env(t, dur, a=0.03, r=0.15) * 0.55)
write_wav("meow.wav", meow)

# ---------------------------------------------------------------------------
# 5d) Chill background music loop - original, mellow lofi-ish 4-bar loop.
#     Slow tempo, soft sine keys + gentle bass, designed to loop seamlessly.
# ---------------------------------------------------------------------------
BPM2 = 76
B2 = 60.0 / BPM2
bars = 4
# Am - F - C - G (mellow, inoffensive, original)
prog2 = [("A", "min"), ("F", "maj"), ("C", "maj"), ("G", "maj")]
roots2 = {"A": 45, "F": 41, "C": 48, "G": 43}
pad = []
bass2 = []
arp = []
total2 = bars * 4 * B2
for bar, (name, kind) in enumerate(prog2):
    r = roots2[name]
    for iv in chord(r + 12, kind):  # pad chord, soft
        pad.append((bar * 4 * B2, 4 * B2, iv, 0.30))
    bass2.append((bar * 4 * B2, 2 * B2, r - 12, 0.55))
    bass2.append((bar * 4 * B2 + 2 * B2, 2 * B2, r - 12 + 7, 0.40))
    # gentle arpeggio (8ths)
    ivs = chord(r + 24, kind)
    for k in range(8):
        arp.append((bar * 4 * B2 + k * B2 / 2, B2 * 0.45, ivs[k % len(ivs)], 0.22))
tracks2 = [render(pad, gain=0.28, kind="sine"),
           render(bass2, gain=0.40, kind="tri"),
           render(arp, gain=0.26, kind="sine", vib=0.003, vibhz=4.5)]
total = max(len(t) for t in tracks2)
bg = [0.0] * total
for t in tracks2:
    for i in range(len(t)):
        bg[i] += t[i]
# equal-power crossfade of last 0.35s into first 0.35s for a seamless loop
xf = int(0.35 * SR)
for i in range(xf):
    a = i / xf
    # fold tail into head
    if i < total:
        bg[i] = bg[i] * a + bg[total - xf + i] * (1 - a)
bg = bg[:total - xf]
write_wav("bg_loop.wav", bg)
print("ALL OK ->", OUT)

# ---------------------------------------------------------------------------
# 6) Nyan-ish sparkle (original, not the Nyan Cat tune) for rainbow trail
# ---------------------------------------------------------------------------
sp = [(0.0, 0.1, 88, .9), (0.1, 0.1, 93, .9), (0.2, 0.1, 96, .9), (0.3, 0.1, 100, .9)]
write_wav("sparkle.wav", render(sp, gain=0.3, kind="sine"))
print("ALL OK ->", OUT)
