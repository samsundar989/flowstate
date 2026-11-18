"""Generate original Overhand earcons. Python stdlib + FFmpeg, no external sounds.
48 kHz mono PCM16; normalize 400 ms padded measurement to -18 LUFS.
Padding is measurement-only: each shipped file remains shorter than 300 ms.
"""
import array, json, math, pathlib, shutil, subprocess, wave
ROOT=pathlib.Path(__file__).resolve().parents[2]
FF=shutil.which("ffmpeg") or str(next(pathlib.Path.home().glob("AppData/Local/Microsoft/WinGet/Packages/Gyan.FFmpeg*/*/bin/ffmpeg.exe")))
OUT=ROOT/"native/overhand/app/src/main/assets/audio"
OUT.mkdir(parents=True,exist_ok=True)
# frequency, start seconds, duration seconds: softened pentatonic motifs.
SCORES={
 "select": [(880,0,.085)],
 "confirm": [(660,0,.11),(990,.10,.13)],
 "defer": [(660,0,.11),(440,.10,.13)],
 "needs_you": [(784,0,.09),(784,.15,.09)],
 "done": [(523.25,0,.09),(659.25,.075,.09),(1046.5,.15,.12)],
 # t1282: a grab took hold. Low and falling, an octave-plus under select, so a grab never sounds like a choice.
 "grab": [(392,0,.05),(293.66,.035,.07)]}
def write(path,samples):
 with wave.open(str(path),"wb") as w:
  w.setparams((1,2,48000,0,"NONE","not compressed"))
  w.writeframes(array.array("h",[round(max(-1,min(1,x))*32767) for x in samples]).tobytes())
def measure(path):
 # EBU R128 requires >=400 ms. Silence padding gives reproducible short-cue energy.
 r=subprocess.run([FF,"-hide_banner","-i",str(path),"-af","apad=whole_dur=0.4,loudnorm=I=-18:TP=-3:LRA=7:print_format=json","-f","null","-"],capture_output=True,text=True,check=True)
 return json.JSONDecoder().raw_decode(r.stderr[r.stderr.rfind("{"):])[0]
table=[]
for name,notes in SCORES.items():
 n=round(max(start+dur for _,start,dur in notes)*48000)
 samples=[0.0]*n
 for hz,start,dur in notes:
  for j in range(round(dur*48000)):
   t=j/48000
   env=min(1,t/.008)*min(1,(dur-t)/.025)*math.exp(-t*8)
   samples[round(start*48000)+j]+=env*(math.sin(2*math.pi*hz*t)+.12*math.sin(4*math.pi*hz*t))
 target=OUT/(name+".wav")
 write(target,samples)
 gain=10**((-18-float(measure(target)["input_i"]))/20)
 samples=[x*gain for x in samples]
 assert max(map(abs,samples))<10**(-3/20)
 write(target,samples)
 m=measure(target)
 table.append(dict(earcon=name,duration_ms=round(n/48),lufs_400ms=float(m["input_i"]),true_peak_dbfs=float(m["input_tp"]),sample_peak_dbfs=round(20*math.log10(max(map(abs,samples))),2),format="mono PCM16 48000 Hz"))
evidence=ROOT/"runs/native/sound"
evidence.mkdir(parents=True,exist_ok=True)
(evidence/"loudness.json").write_text(json.dumps(table,indent=2)+"\n")
(evidence/"loudness.md").write_text("| Earcon | ms | LUFS (400 ms padded) | True peak dBFS |\n|---|---:|---:|---:|\n"+"".join(f"| {r['earcon']} | {r['duration_ms']} | {r['lufs_400ms']} | {r['true_peak_dbfs']} |\n" for r in table))
print(json.dumps(table,indent=2))
