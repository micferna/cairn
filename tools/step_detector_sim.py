"""
Banc d'essai du détecteur de pas.

Réimplémente StepDetector.kt en Python pour pouvoir régler ses seuils en
quelques secondes plutôt qu'en installant un APK à chaque essai. Les signaux
sont synthétiques : marche à cadence variable, course, téléphone en poche,
mais aussi vibration de voiture et gesticulation à la main — les deux sources
de faux positifs qui ont motivé le verrouillage de cadence.

    python3 tools/step_detector_sim.py

Toute modification des seuils dans StepDetector.kt doit être reportée ici, et
inversement. Ce fichier n'est pas compilé : il sert à décider, pas à livrer.
"""

import math, random

GRAVITY_ALPHA=0.02; SMOOTH_ALPHA=0.45; WINDOW=25
MIN_AMP=0.55; MIN_PROM=0.18; MIN_INT=250; MAX_INT=2000

class Detector:
    """Ajoute un verrouillage de cadence : il faut LOCK intervalles réguliers
    consécutifs avant de créditer quoi que ce soit. Les pas ayant servi à
    établir le verrou sont crédités rétroactivement."""
    def __init__(s, drift, lock):
        s.DRIFT=drift; s.LOCK=lock
        s.g=None; s.sm=0.0; s.win=[0.0]*WINDOW; s.i=0; s.fill=0
        s.above=False; s.last=0; s.lastInt=0; s.peak=0.0
        s.steps=0; s.run=0
    def accept(s,x,y,z,t):
        m=math.sqrt(x*x+y*y+z*z)
        if s.g is None: s.g=m; return
        s.g += GRAVITY_ALPHA*(m-s.g)
        s.sm += SMOOTH_ALPHA*((m-s.g)-s.sm)
        s.win[s.i]=s.sm; s.i=(s.i+1)%WINDOW
        if s.fill<WINDOW: s.fill+=1; return
        lo=min(s.win); hi=max(s.win); th=(lo+hi)/2
        if (hi-lo)<MIN_AMP:
            s.above = s.sm>th; s.run=0; s.lastInt=0; return
        if s.sm>th:
            s.above=True; s.peak=max(s.peak,s.sm); return
        if not s.above: return
        s.above=False; peak=s.peak; s.peak=0.0
        s.validate(peak-th, t)
    def validate(s, prom, t):
        first = s.last==0; interval=t-s.last
        if prom<MIN_PROM or (not first and interval<MIN_INT): return
        if first or interval>MAX_INT:
            s.last=t; s.lastInt=0; s.run=0; return
        regular = s.lastInt>0 and abs(interval-s.lastInt)/s.lastInt <= s.DRIFT
        s.last=t; s.lastInt=interval
        if not regular:
            s.run=0; return
        s.run+=1
        if s.run==s.LOCK:      # verrou : on crédite la séquence fondatrice
            s.steps += s.LOCK+1
        elif s.run>s.LOCK:
            s.steps += 1

rnd=random.Random(3)
def walk(freq, amp):
    def g(t):
        v = amp*math.sin(2*math.pi*freq*t)+0.35*amp*math.sin(4*math.pi*freq*t)
        return (0.3*rnd.gauss(0,0.05),0.3*rnd.gauss(0,0.05),9.81+v+rnd.gauss(0,0.06))
    return g
def still(t): return (rnd.gauss(0,0.02),rnd.gauss(0,0.02),9.81+rnd.gauss(0,0.02))
def car(t):
    v=0.5*math.sin(2*math.pi*11*t)+0.9*rnd.gauss(0,0.5)
    return (rnd.gauss(0,0.3),rnd.gauss(0,0.3),9.81+v)
def jitter(t):
    v=0.4*math.sin(2*math.pi*6*t)+rnd.gauss(0,0.25)
    return (rnd.gauss(0,0.2),rnd.gauss(0,0.2),9.81+v)

CASES=[("marche 1,8 Hz",walk(1.8,1.6),108),("marche lente 1,4 Hz",walk(1.4,2.4),84),
       ("course 2,8 Hz",walk(2.8,3.5),168),("faible amplitude",walk(1.7,0.9),102),
       ("immobile",still,0),("voiture",car,0),("gesticulation",jitter,0)]

for drift,lock in [(0.40,3),(0.30,3),(0.25,4),(0.20,4),(0.25,3)]:
    print(f"\n--- dérive max {drift:.0%}, verrou {lock} intervalles ---")
    for name,gen,exp in CASES:
        rnd.seed(3)
        d=Detector(drift,lock)
        for k in range(60*25):
            x,y,z=gen(k/25); d.accept(x,y,z,int(k*1000/25))
        mark = "OK " if (exp==0 and d.steps==0) or (exp>0 and abs(d.steps-exp)/exp<0.08) else "!! "
        print(f"  {mark}{name:22} attendu {exp:4d}  détecté {d.steps:4d}")
