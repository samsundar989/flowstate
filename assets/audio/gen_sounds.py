"""Generate the app's sound set: 11 short cues as 48 kHz 16-bit mono WAVs.

Run:  python -I assets/audio/gen_sounds.py
Stdlib only. Deterministic: fixed seed, fixed maths, so repeated runs give
byte-identical files. Every cue is loudness-matched to TARGET_RMS_DBFS (RMS over
the whole file), with 5 ms fades at both ends. Writes the WAVs next to this file
and prints a table (file, duration, peak dBFS, RMS dBFS) that README.md mirrors.
"""
import math
import os
import random
import struct
import wave

SR = 48000
TARGET_RMS_DBFS = -18.0
FADE_S = 0.005
SEED = 56
OUT = os.path.dirname(os.path.abspath(__file__))


def midi(n):
    return 440.0 * 2 ** ((n - 69) / 12)


def silence(dur):
    return [0.0] * int(round(dur * SR))


def mix(dst, src, at=0.0, gain=1.0):
    """Add src into dst starting at time `at` (s), growing dst if needed."""
    o = int(round(at * SR))
    if len(dst) < o + len(src):
        dst.extend([0.0] * (o + len(src) - len(dst)))
    for i, v in enumerate(src):
        dst[o + i] += gain * v
    return dst


def tone(freq, dur, partials=((1, 1.0),), attack=0.004, decay=8.0, glide_to=None):
    """Additive tone: partials are (ratio, amp, [decay multiplier]).

    Envelope is a linear attack then exponential decay exp(-decay * t * dm).
    glide_to: end frequency for an exponential pitch glide over `dur`.
    """
    n = int(round(dur * SR))
    out = [0.0] * n
    phases = [0.0] * len(partials)
    for i in range(n):
        t = i / SR
        f = freq if glide_to is None else freq * (glide_to / freq) ** (t / dur)
        a = min(1.0, t / attack) if attack > 0 else 1.0
        s = 0.0
        for k, p in enumerate(partials):
            ratio, amp = p[0], p[1]
            dm = p[2] if len(p) > 2 else 1.0
            phases[k] += 2 * math.pi * f * ratio / SR
            s += amp * math.exp(-decay * t * dm) * math.sin(phases[k])
        out[i] = a * s
    return out


def noise_burst(dur, rng, decay=200.0, lp=0.15):
    """Short filtered noise transient (one-pole low-pass, coefficient lp)."""
    n = int(round(dur * SR))
    out, y = [0.0] * n, 0.0
    for i in range(n):
        y += lp * (rng.uniform(-1, 1) - y)
        out[i] = y * math.exp(-decay * i / SR)
    return out


def lowpass(x, coef):
    y, out = 0.0, [0.0] * len(x)
    for i, v in enumerate(x):
        y += coef * (v - y)
        out[i] = y
    return out


BELL = ((1, 1.0), (2.0, 0.35, 1.6), (3.01, 0.12, 2.4))   # soft chime
MARIMBA = ((1, 1.0), (3.93, 0.25, 3.0), (9.4, 0.05, 6.0))  # woody bar
WARM = ((1, 1.0), (2, 0.25, 1.5), (3, 0.08, 2.0))


def build(rng):
    c = {}
    # hover: faint, high, very short blip. The quietest-feeling cue, matched anyway.
    c["hover"] = tone(midi(96), 0.060, ((1, 1.0), (2, 0.15, 2.0)), attack=0.004, decay=55)
    # press: low-mid woody knock with a tiny noise transient (the 7 mm poke bottoming out).
    c["press"] = mix(tone(midi(69), 0.080, MARIMBA, attack=0.002, decay=45),
                     noise_burst(0.020, rng, decay=300, lp=0.3), gain=0.25)
    # tick: crisp selection confirm, one bright marimba-like note.
    c["tick"] = tone(midi(88), 0.070, MARIMBA, attack=0.002, decay=50)
    # assign: upward glide, "picked up and handed over".
    c["assign"] = tone(midi(72), 0.180, WARM, attack=0.010, decay=10, glide_to=midi(79))
    # snap-back: downward glide, soft, "went back where it came from".
    c["snap-back"] = tone(midi(76), 0.220, WARM, attack=0.010, decay=9, glide_to=midi(67))
    # step-done: two quick stacked partials, a bright pleasant tick (pitched per agent at runtime).
    sd = tone(midi(84), 0.150, MARIMBA, attack=0.003, decay=22)
    c["step-done"] = mix(sd, tone(midi(91), 0.110, MARIMBA, attack=0.003, decay=30), at=0.035, gain=0.6)
    # ask: two-note rising chime (perfect 4th, E5 -> A5): reads as a question.
    ask = tone(midi(76), 0.380, BELL, attack=0.008, decay=9)
    c["ask"] = mix(ask, tone(midi(81), 0.240, BELL, attack=0.008, decay=10), at=0.140)
    # approve: quick major arpeggio up (C5 E5 G5), warm, affirming.
    ap = tone(midi(72), 0.320, BELL, attack=0.006, decay=12)
    mix(ap, tone(midi(76), 0.260, BELL, attack=0.006, decay=13), at=0.060)
    c["approve"] = mix(ap, tone(midi(79), 0.200, BELL, attack=0.006, decay=14), at=0.120)
    # stop: two muted descending notes (A4 -> E4), short and neutral.
    st = tone(midi(69), 0.250, WARM, attack=0.006, decay=22)
    c["stop"] = mix(st, tone(midi(64), 0.170, WARM, attack=0.006, decay=20), at=0.080)
    # fail: low, soft, rounded thud with a gentle downward bend. Not an alarm.
    f = tone(midi(52), 0.350, ((1, 1.0), (2, 0.2, 2.0)), attack=0.020, decay=9, glide_to=midi(48))
    c["fail"] = lowpass(f, 0.25)
    # payoff: 1.2 s resolved C-major chord, voices entering 60 ms apart, long bell decay.
    po = silence(1.2)
    for k, n in enumerate((60, 64, 67, 72)):
        mix(po, tone(midi(n), 1.2 - 0.06 * k, BELL, attack=0.012, decay=3.2), at=0.06 * k, gain=0.8)
    c["payoff"] = po[: int(1.2 * SR)]
    return c


def finish(x):
    """Fade 5 ms in and out, then scale RMS to the target."""
    n, f = len(x), int(FADE_S * SR)
    for i in range(f):
        g = i / f
        x[i] *= g
        x[n - 1 - i] *= g
    rms = math.sqrt(sum(v * v for v in x) / n)
    gain = 10 ** (TARGET_RMS_DBFS / 20) / rms
    return [v * gain for v in x]


def stats(x):
    peak = max(abs(v) for v in x)
    rms = math.sqrt(sum(v * v for v in x) / len(x))
    return 20 * math.log10(peak), 20 * math.log10(rms)


def write(path, x):
    pcm = struct.pack("<%dh" % len(x), *(max(-32767, min(32767, int(round(v * 32767)))) for v in x))
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm)


def main():
    cues = build(random.Random(SEED))
    print("| file | duration (ms) | peak dBFS | RMS dBFS |")
    print("|---|---|---|---|")
    for name, x in cues.items():
        x = finish(x)
        peak, rms = stats(x)
        assert peak < -1.0, f"{name} peak {peak:.1f} dBFS would clip"
        write(os.path.join(OUT, name + ".wav"), x)
        print(f"| `{name}.wav` | {len(x) * 1000 // SR} | {peak:.1f} | {rms:.1f} |")


if __name__ == "__main__":
    main()
