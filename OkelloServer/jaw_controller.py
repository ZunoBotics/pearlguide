"""
jaw_controller.py — Preston Blair viseme-based jaw lip-sync for SCS125 ID 1.

Algorithm:
  - Phoneme stream → viseme blending with coarticulation anticipation
  - Audio amplitude fallback when no phoneme data available
  - Idle breathing + Perlin micro-motion when silent
  - PVT (Position-Velocity-Time) drive for natural trapezoidal motion
"""

import math, random, time, threading, logging
from collections import deque

log = logging.getLogger(__name__)

# ── Viseme table (Preston Blair 8-viseme set) ─────────────────────────────────
VISEMES = {
    'A': 0.95,   # "oh" / "aw" — widest drop
    'B': 0.75,   # "mbp" — lips close, jaw slightly open
    'C': 0.55,   # "eey" / "eh"
    'D': 0.65,   # "ai" / "ay"
    'E': 0.40,   # "oh" rounded
    'F': 0.25,   # "f/v" — jaw nearly closed, lower lip up
    'G': 0.30,   # "oo" — small rounded
    'H': 0.20,   # "kg" — barely open
    'X': 0.05,   # rest / closed
}

PHONEME_TO_VISEME = {
    'aa':'A','ao':'A','ah':'A','ae':'A','ey':'D','ay':'D','oy':'E',
    'eh':'C','ih':'C','iy':'C','uw':'G','uh':'G','ow':'E',
    'p':'B','b':'B','m':'B','f':'F','v':'F',
    'th':'F','dh':'F','t':'H','d':'H','n':'H','l':'H','s':'H','z':'H',
    'r':'C','sh':'G','zh':'G','ch':'G','jh':'G','y':'G','w':'G',
    'k':'H','g':'H','ng':'H','h':'B','sil':'X','sp':'X','pause':'X',
}


# ── Perlin-like 1D noise ──────────────────────────────────────────────────────
class _ValueNoise1D:
    def __init__(self, seed=42):
        rng = random.Random(seed)
        self._pts = [rng.random() for _ in range(256)]

    def __call__(self, x: float) -> float:
        i = int(math.floor(x)) % 256
        f = x - math.floor(x)
        t = (1 - math.cos(f * math.pi)) / 2      # smoothstep
        return self._pts[i] + (self._pts[(i+1) % 256] - self._pts[i]) * t


# ── Jaw Controller ────────────────────────────────────────────────────────────
class JawController:
    """
    Drive SCS125 ID 1 for natural lip-sync.

    Usage:
        jaw = JawController(bus)          # bus = ServoBus instance
        jaw.start()                       # starts background tick thread

        jaw.load_phonemes(phoneme_list)   # feed TTS phoneme track
        jaw.update_from_audio(rms)        # OR feed live audio RMS amplitude
        jaw.set_silent()                  # return to idle breathing
    """

    JAW_ID   = 1
    TICK_HZ  = 50       # update rate

    def __init__(self, bus, closed: int = None, open_max: int = None):
        self._bus = bus

        # Load limits from servo_limits.json via bus
        lim = bus.limits.get('1', {})
        lo  = min(lim.get('min_step', 203), lim.get('max_step', 973))
        hi  = max(lim.get('min_step', 203), lim.get('max_step', 973))
        # Lower step = open, higher step = closed on this jaw servo
        self.CLOSED   = closed   if closed   is not None else hi
        self.OPEN_MAX = open_max if open_max is not None else lo
        self.RANGE    = self.OPEN_MAX - self.CLOSED   # negative: jaw opens by subtracting

        log.info("JawController: closed=%d open_max=%d range=%d",
                 self.CLOSED, self.OPEN_MAX, self.RANGE)

        self._noise    = _ValueNoise1D(seed=42)
        self._idle_t0  = time.time()

        self._timeline    = []           # [(t, viseme, dur, emph), ...]
        self._speaking    = False
        self._speak_start = None

        self._env_history  = deque(maxlen=64)
        self._audio_drive_t = 0.0       # last time update_from_audio called _drive

        self._last_norm = 0.0
        self._last_t    = time.time()

        self._thread  = None
        self._running = False

        # Enable torque on jaw servo
        bus.torque(self.JAW_ID, True)

    # ── Thread control ────────────────────────────────────────────────────────

    def start(self):
        if self._running:
            return
        self._running = True
        self._thread = threading.Thread(target=self._loop, daemon=True, name="jaw-tick")
        self._thread.start()
        log.info("JawController started at %d Hz", self.TICK_HZ)

    def stop(self):
        self._running = False
        self._bus.torque(self.JAW_ID, False)

    def _loop(self):
        interval = 1.0 / self.TICK_HZ
        while self._running:
            t0 = time.time()
            try:
                self.tick()
            except Exception as e:
                log.debug("jaw tick error: %s", e)
            elapsed = time.time() - t0
            time.sleep(max(0.0, interval - elapsed))

    # ── Public API ────────────────────────────────────────────────────────────

    def load_phonemes(self, phoneme_stream: list):
        """
        phoneme_stream: [{'phoneme':'ah','start':0.12,'end':0.27,'emphasis':1.0}, ...]
        Call this at the moment TTS audio playback begins.
        """
        timeline = []
        for ph in phoneme_stream:
            v    = PHONEME_TO_VISEME.get(ph['phoneme'].lower(), 'X')
            emph = ph.get('emphasis', 1.0)
            timeline.append((ph['start'], v, ph['end'] - ph['start'], emph))
        self._timeline    = timeline
        self._speaking    = True
        self._speak_start = time.time()

    def update_from_audio(self, rms_amp: float):
        """
        Amplitude-based fallback (0.0–1.0 RMS).
        Use when no phoneme timing data is available (e.g. streaming Gemini audio).
        """
        alpha = 0.25
        prev  = self._env_history[-1] if self._env_history else 0.0
        env   = (1 - alpha) * prev + alpha * rms_amp
        self._env_history.append(env)

        if len(self._env_history) >= 8:
            mn  = min(self._env_history)
            mx  = max(self._env_history)
            rng = max(mx - mn, 0.05)
            norm = (env - mn) / rng
        else:
            norm = env

        now   = time.time()
        noise = 0.04 * (self._noise(now * 12.0) - 0.5)
        self._audio_drive_t = now
        self._drive(0.15 + 0.80 * norm + noise, now)

    def set_silent(self):
        """Stop speaking mode — return to idle breathing."""
        self._speaking = False
        self._timeline = []

    # ── Internal ──────────────────────────────────────────────────────────────

    def tick(self):
        now = time.time()

        if self._speaking:
            elapsed = now - self._speak_start
            end_t   = (self._timeline[-1][0] + self._timeline[-1][2] + 0.25
                       if self._timeline else 0)
            if elapsed > end_t:
                self._speaking = False
                self._timeline = []
            else:
                self._drive(self._coarticulated_target(elapsed), now)
                return

        # If update_from_audio drove the jaw within the last 150 ms, yield to it
        if now - self._audio_drive_t < 0.15:
            return

        self._idle_tick(now)

    def _coarticulated_target(self, elapsed: float) -> float:
        num, den = 0.0, 0.0
        for (t, v, dur, emph) in self._timeline:
            center = t + dur * 0.5
            dt     = center - elapsed
            if abs(dt) > 0.25:
                continue
            w    = self._coarticulation_weight(dt) * (0.6 + 0.4 * emph)
            num += VISEMES.get(v, VISEMES['X']) * w
            den += w
        return (num / den) if den > 1e-6 else VISEMES['X']

    @staticmethod
    def _coarticulation_weight(dt: float, anticipation=0.080, hold=0.040) -> float:
        if dt > 0:
            return math.exp(-(dt / anticipation) ** 2)
        else:
            return math.exp((dt / hold) ** 2 * -1)

    def _idle_tick(self, now: float):
        t           = now - self._idle_t0
        breath_freq = 1.0 / 4.5
        depth       = 0.025 * (0.7 + 0.3 * math.sin(t * 0.21))
        breath      = depth * (0.5 - 0.5 * math.cos(t * 2 * math.pi * breath_freq))
        micro       = 0.015 * (self._noise(t * 3.7)  - 0.5)
        micro2      = 0.008 * (self._noise(t * 17.0) - 0.5)
        self._drive(0.04 + breath + micro + micro2, now)

    def _drive(self, target_norm: float, now: float):
        target_norm = max(0.0, min(1.0, target_norm))

        delta   = abs(target_norm - self._last_norm)
        time_ms = int(60 + delta * 260)
        time_ms = max(40, min(time_ms, 400))

        pos      = int(self.CLOSED + target_norm * self.RANGE)
        velocity = int(5000 * delta / max(time_ms / 1000.0, 0.001))
        velocity = max(50, min(velocity, 4000))

        self._bus.write_pvt(self.JAW_ID, pos, velocity, time_ms)
        self._last_norm = target_norm
        self._last_t    = now
