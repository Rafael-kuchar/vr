import gc
import json
import math
import os
import socket
import struct
import sys
import threading
import time
import urllib.request

# low-latency stream options: must be set BEFORE importing cv2
os.environ.setdefault("OPENCV_FFMPEG_CAPTURE_OPTIONS", "fflags;nobuffer|flags;low_delay")
os.environ.setdefault("OPENCV_VIDEOIO_MSMF_ENABLE_HW_TRANSFORMS", "0")

import cv2
import mediapipe as mp
import numpy as np
from mediapipe.tasks import python as mp_python
from mediapipe.tasks.python import vision

try:
    import openvr
except ImportError:
    openvr = None

# ---------------- CONFIG ----------------
ARGS = [a for a in sys.argv[1:] if not a.startswith("--")]
DEFAULT_SOURCE = "http://192.168.1.4:8080/video"
ARG = ARGS[0] if ARGS else DEFAULT_SOURCE
URL = ARG if ARG.startswith("http") or ARG.startswith("rtsp") else None
CAMERA_INDEX = int(ARG) if URL is None else 0  # webcam index, or phone URL
WIDTH, HEIGHT = 320, 240
DETECT_WIDTH = 320      # detection resolution: higher = more precise landmarks (was 320; go back to 320 if the fps drops)
ROTATION = None         # R key cycles: None / 90 / 180 / 270
MIRROR = False          # M key toggles
SWAP_HANDS = False      # H key toggles (swaps left/right)
INVERT_PALM = True      # P key toggles (palm facing vs back of the hand)
INVERT_HAND_ROT = True  # I key toggles (flips the hand 180 deg in VR: palm <-> back of the hand)
HAND_ROLL_DEG = {"Left": 70.0, "Right": -70.0}  # twist of each hand in VR around the finger axis (left hand to the left, right hand to the right)
HAND_TILT_DEG = {"Left": -30.0, "Right": 30.0}  # extra lean of the fingers sideways (left hand 30 to the right, right hand 30 to the left)
ROLL_FLIP = False       # O key toggles (swaps the roll direction if it went the wrong way)
TILT_INVERT = False
TWIST_AXIS = -1
ROT_PALM_ONLY = False
TWIST_AXIS_NAMES = {2: "controller z", 1: "controller y", 0: "controller x", -1: "finger axis (old)"}
TILT_FLIP = False       # L key toggles (swaps the lean direction if it went the wrong way)
INVERT_JOY_X = False    # V key toggles (joystick right/left)
INVERT_JOY_Y = False    # B key toggles (joystick up/down)
DEBUG_FINGERS = False   # D key toggles (shows how much each finger is bent)
BLACK_PREVIEW = True    # K key toggles (black background instead of the camera image)
UI_MAX_SCALE = 2.0      # lower = lighter window (was 3.0)
SHOW_EVERY = 2          # draws/shows the window every N frames (tracking and VR still run every frame)

# ---- SteamVR (via VMT, over OSC/UDP) ----
VR_ENABLED = "--no-vr" not in sys.argv   # VR starts ON by default; run with --no-vr to start off (F key toggles)
VMT_IP, VMT_PORT = "127.0.0.1", 39570
VMT_INDEX = {"Left": 0, "Right": 1}
VMT_MODE = {"Left": 5, "Right": 6}  # 5/6 = "Index compatible" controllers (joystick = thumb)
CAM_FOV_H = 70.0        # horizontal field of view of the phone camera, in degrees (unrotated image)
PALM_METERS = 0.10      # wrist -> middle finger base distance (real palm size)
CALIB_DIST = 0.35       # if depth was calibrated (C key): hand distance at that moment (m)
DEPTH_MIN, DEPTH_MAX = 0.15, 0.90
VR_INV_X = False        # N key toggles: if the hand goes the wrong way in VR
TILT_DOWN_DEG = {"Left": 55.0, "Right": 55.0}     # tilt of the hand in VR toward the floor (was 55). Keys 1 / 2 change it live
USE_ROT_FIX = False
FREEZE_WORLD_ON_LOSS = True
# Momentum: a fast hand that leaves the camera keeps its speed for a moment, then brakes (needs openvr, like the freeze above)
MOMENTUM = True          # U key toggles
MOMENTUM_MIN_SPEED = 1.0   # m/s: slower than this the hand just freezes as before
MOMENTUM_MAX_SPEED = 6.0   # m/s: speed cap
MOMENTUM_HOLD = 0.12       # seconds keeping the full speed before braking
MOMENTUM_TAU = 0.22        # seconds: braking time constant (lower = stops harder)
MOMENTUM_MAX_DIST = 0.8    # meters: it never travels farther than this
MOMENTUM_MAX_SECS = 0.9    # seconds: it always stops by then
MOMENTUM_EDGE = 0.25       # only when the hand vanished this close to the image border (fraction of the frame)
MOMENTUM_EDGE_ONLY = True  # False = also when detection fails in the middle of the image
MOMENTUM_VEL_SMOOTH = 0.5
GLIDE_TAU = 0.15        # seconds: smooth glide when the hand freezes / comes back (higher = slower and smoother)
POS_MIN_CUTOFF = 0.5    # lower = steadier hand when slow (was 1.0)
POS_BETA = 2.0         # higher = less lag when moving fast (still smooth when the hand is slow)
SIZE_MIN_CUTOFF = 0.35   # lower = steadier depth (hand distance) (was 0.8)
ROT_SMOOTH_MIN = 0.12   # lower = rotation reacts slower / calmer (less sensitive)
ROT_SMOOTH_GAIN = 3.0
CONTROLLER_MODE = False # Y key toggles: hand held like a VR controller (thumb up, back of the fingers to the camera)
CONTROLLER_STRICTER = 0.30   # in controller mode the fingers must bend this much MORE to count as pressed (a relaxed grip is half-closed)
ROT_RAY_GAIN = 1.0      # J key cycles 1.0 / 0.5 / 0. Corrects the hand rotation for the viewing angle of the camera
                        # (a hand near the camera and off-center is seen from the side: that was bending the rotation)
ROT_EDGE_HOLD = 0.0     # >0: rotation freezes while a palm point is within this fraction of the image border (0 = off). Try 0.03
ROT_MAX_DPS = 600.0
CHI_CONFIRM = 10
VEL_SMOOTH = 0.35
ROT_CALIB_WAIT = 2.0    # T key: seconds you have to get into the straight-hand pose
ROT_CALIB_SECS = 3.0    # T key: seconds holding still (the pose is averaged over this time)
REST = {"Left": (-0.04, -0.25, 0.35), "Right": (0.04, -0.25, 0.35)}  # before the hand is seen
STABLE_RADIUS = 0.008  # meters: the hand ignores tiny shakes smaller than this (0 = off). Keeps it still when placing things
HAND_CLOSE_X = 0.25    # meters: pushes each hand toward the center (keeps the movement multiplier)

GRACE = 0.70            # seconds holding the hand when detection fails
CONFIDENCE = 0.65       # lower = detects more, but makes more mistakes
TRACK_CONFIDENCE = 0.5

# joystick
PALM_DEBOUNCE = 0.15    # seconds stable before turning the joystick on/off
ARM_SECS = 0.30          # seconds holding the gesture to turn the joystick on
# the joystick turns on with an OPEN HAND, PALM FACING THE CAMERA, FINGERS SPREAD APART (fan).
# fan = sum of the 3 angles between neighbouring fingers (index-middle, middle-ring, ring-pinky), in degrees.
FAN_MIN_DEG = 40.0       # lower = easier to turn on (a relaxed open hand is usually well below this). Turn D on to see the live value
# the joystick follows the hand POSITION (rotation is not used): move the hand from where it was when the joystick turned on
JOY_POS_MAX_CM = 3.2    # how far to move the hand (cm, real size) for the stick to go all the way. Lower = more sensitive
JOY_POS_DEAD_CM = 1.2   # dead zone: while the hand is within this distance (cm) of the center the stick stays at 0. Bigger = easier to come back to center
DEADZONE = JOY_POS_DEAD_CM / JOY_POS_MAX_CM   # (only used to draw the inner circle)
PREDICT_SECS = 0.04    # prediction adds jitter: lower = steadier (was 0.08)
PREDICT_MAX = 0.10
FINGER_EXTENDED = 1.45
POS_GAIN = 2.5
BUTTON_DELAY = 0.05
# --- pose from the PALM only: the position, rotation and distance of the hand come from the wrist + the 4 knuckles
# (the finger joints are never used), so bending the fingers moves the hand as little as possible.
PALM_ONLY_POSE = True
POSE_WRIST_WEIGHT = 0.8   # position: how much comes from the wrist (steady) vs the knuckles. 1.0 = wrist only (steadiest)
HOLD_GRIP_ON_LOSS = True   # hand leaves the camera while gripping: the grip stays pressed (so what you hold is not dropped)
HOLD_GRIP_MAX_SECS = 0.0   # 0 = keeps it until the hand comes back; otherwise releases after this many seconds
BUTTON_FINGERS = ("middle", "ring", "pinky")
VMT_BUTTONS = {"middle": 7}   # middle finger down = A / X
VMT_THUMB_BUTTON = 1          # thumb opened wide = B / Y (the ring finger no longer presses a button)
# Thumb open: ratio = distance(thumb tip, index base) / palm length (wrist -> middle finger base), in 3D
# thumb tucked ~0.3-0.5; thumb opened wide ~0.8-1.0 (turn on D to see the live value)
THUMB_OPEN_ENABLED = True
THUMB_OPEN_ON = 0.80    # above this = thumb opened wide (lower = easier to press)
THUMB_OPEN_OFF = 0.65   # below this = released
THUMB_WAIT = 0.05       # seconds in the new position to count (anti-flicker)
VMT_SYSTEM_BUTTON = 0
SYSTEM_HOLD = 0.60      # seconds holding the system pose (was BUTTON_DELAY = 0.05)
SYSTEM_STRICT = 0.12    # ring and pinky must bend this much MORE than the normal limit

# fingers: ratio = distance(tip, wrist) / distance(finger base, wrist)
# extended ~1.8 to 2.0; half bent ~1.4; fist ~1.0
# Trigger = index finger down. Grip = middle + ring + pinky down (index free).
FINGER_DOWN = 1.50      # below this the finger counts as "down" (lower = needs more bending)
HYSTERESIS = 0.25       # to release, the finger must go past FINGER_DOWN + HYSTERESIS
FINGER_RELEASE_WAIT = 0.08
# Pinch (thumb tip + index tip together) also counts as the trigger.
# ratio = distance(thumb tip, index tip) / palm length (wrist -> middle finger base), measured in 3D
PINCH_ENABLED = True
PINCH_ON = 0.30         # below this = pinching (lower = needs to squeeze harder)
PINCH_OFF = 0.50        # above this = released
# "OK" sign (rounder pinch): index curved into a ring + middle, ring and pinky extended.
# Only in this shape the pinch is more forgiving, so it does not trigger by accident.
OK_ENABLED = True
OK_ON = 0.42            # thumb-index distance (same ratio as above) accepted in the OK shape
OK_OFF = 0.58           # release limit in the OK shape
OK_INDEX_MAX = 1.65     # index curl ratio must be below this (extended is ~1.8+)
FINGER_WAIT = 0.03      # seconds the finger must stay in the new position to count (anti-flicker)
CURL_SMOOTH = 0.6       # measurement smoothing (0 to 1, lower = smoother)
# Palm facing uses FINGER_DOWN as is (if the fingers bend too much they cover the thumb).
# Back of the hand is stricter: needs to bend BACK_STRICTER more, otherwise it presses by accident.
BACK_STRICTER = 0.15

# Bad angle (hand "flat"/sideways or pointing at the camera): measurements stop being valid.
ANGLE_MIN_SPREAD = 0.12  # index-pinky spread seen by the camera (0 = sideways hand)
ANGLE_MIN_LENGTH = 0.65  # palm length / palm width (low = pointing at the camera)

# Upside-down hand (fingers pointing down in the image): trigger/grip turn off.
UPSIDE_ENTER = 0.60      # 0 = fingers horizontal, 1 = pointing straight down
UPSIDE_EXIT = 0.35       # must come up to here to go back to normal (avoids flicker)
BLOCK_GRIP_UPSIDE_DOWN = False  # False = only the trigger is blocked

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
TWIST_LOG_FILE = os.path.join(BASE_DIR, "twist_log.csv")
TWIST_LOG_SECS = 8.0
REC = {"until": 0.0, "rows": []}
ANGLES_FILE = os.path.join(BASE_DIR, "angles.json")   # saved by the angle keys (1-6); delete the file to go back to the defaults
CALIB_FILE = os.path.join(BASE_DIR, "calibration.json")
OLD_CALIB_FILE = os.path.join(BASE_DIR, "calibracao.json")
OLD_HAND_NAMES = {"Left": "Esquerda", "Right": "Direita"}
MODEL_URL = ("https://storage.googleapis.com/mediapipe-models/hand_landmarker/"
             "hand_landmarker/float16/1/hand_landmarker.task")
MODEL_PATH = "hand_landmarker.task"
ARM_ASSIST = False      # A key toggles (follows the arm by skin color when the hand is out of view)
ARM_TAKEOVER = 0.10
ARM_SEARCH = 4.0
ARM_EXT = 0.6
SKIN_THRESH = 40

CONNECTIONS = [(0,1),(1,2),(2,3),(3,4),(0,5),(5,6),(6,7),(7,8),(5,9),(9,10),(10,11),
               (11,12),(9,13),(13,14),(14,15),(15,16),(13,17),(17,18),(18,19),(19,20),(0,17)]
# (name, finger base, tip)
FINGERS = (("index", 5, 8), ("middle", 9, 12), ("ring", 13, 16), ("pinky", 17, 20))


def ray_rotation(cx, cy, w, h, f_px, gain=1.0):
    """Rotation that takes the optical axis to the viewing ray through the hand, frame (x right, y up, z forward).
    MediaPipe sees the hand as if it were in the middle of the image; this puts back the real camera angle."""
    d = np.array([(cx - w / 2.0) / f_px, -(cy - h / 2.0) / f_px, 1.0])
    d = d / np.linalg.norm(d)
    axis = np.cross(np.array([0.0, 0.0, 1.0]), d)
    s_ = float(np.linalg.norm(axis))
    if s_ < 1e-9:
        return np.eye(3)
    ang = math.atan2(s_, float(d[2])) * gain
    ax = axis / s_
    K = np.array([[0.0, -ax[2], ax[1]], [ax[2], 0.0, -ax[0]], [-ax[1], ax[0], 0.0]])
    return np.eye(3) + math.sin(ang) * K + (1.0 - math.cos(ang)) * (K @ K)


def palm_center(pts):
    """The point the hand is tracked by. Wrist-heavy: the wrist does not move when you bend the fingers."""
    ids = (0, 5, 9, 13, 17)
    if not PALM_ONLY_POSE:
        return (sum(pts[i][0] for i in ids) / len(ids), sum(pts[i][1] for i in ids) / len(ids))
    k = (5, 9, 13, 17)
    mx = sum(pts[i][0] for i in k) / 4.0
    my = sum(pts[i][1] for i in k) / 4.0
    wgt = POSE_WRIST_WEIGHT
    return (pts[0][0] * wgt + mx * (1 - wgt), pts[0][1] * wgt + my * (1 - wgt))


def palm_size(pts):
    """Palm length in pixels: average of wrist->each knuckle (one finger alone cannot change it much)."""
    if not PALM_ONLY_POSE:
        return dist(pts[0], pts[9])
    return sum(dist(pts[0], pts[i]) for i in (5, 9, 13, 17)) / 4.0 / 0.93   # 0.93 keeps it equal to the old wrist->middle size


def dist(a, b):
    return math.hypot(a[0] - b[0], a[1] - b[1])


# ---------------- One Euro Filter ----------------
class OneEuro:
    def __init__(self, min_cutoff=1.0, beta=0.02, d_cutoff=1.0):
        self.min_cutoff, self.beta, self.d_cutoff = min_cutoff, beta, d_cutoff
        self.x_prev = None
        self.dx_prev = 0.0
        self.t_prev = None

    @staticmethod
    def _alpha(cutoff, dt):
        tau = 1.0 / (2 * math.pi * cutoff)
        return 1.0 / (1.0 + tau / dt)

    def __call__(self, x, t):
        if self.x_prev is None:
            self.x_prev, self.t_prev = x, t
            return x
        dt = max(t - self.t_prev, 1e-6)
        dx = (x - self.x_prev) / dt
        a_d = self._alpha(self.d_cutoff, dt)
        dx_hat = a_d * dx + (1 - a_d) * self.dx_prev
        cutoff = self.min_cutoff + self.beta * abs(dx_hat)
        a = self._alpha(cutoff, dt)
        x_hat = a * x + (1 - a) * self.x_prev
        self.x_prev, self.dx_prev, self.t_prev = x_hat, dx_hat, t
        return x_hat

    def reset(self):
        self.x_prev = None


class Hand:
    """State of one hand: position, gestures (trigger/grip) and thumb joystick."""
    def __init__(self, name):
        self.name = name
        self.fx = OneEuro(min_cutoff=POS_MIN_CUTOFF, beta=POS_BETA)
        self.fy = OneEuro(min_cutoff=POS_MIN_CUTOFF, beta=POS_BETA)
        self.fs = OneEuro(min_cutoff=SIZE_MIN_CUTOFF, beta=0.03)
        self.fjx, self.fjy = OneEuro(min_cutoff=2.0, beta=0.05), OneEuro(min_cutoff=2.0, beta=0.05)
        self.x = self.y = None   # smoothed position (0..1)
        self.vx = self.vy = 0.0
        self.px = self.py = self.pt = None
        self.size = None         # smoothed hand size (in pixels)
        self.visible = False     # detected in this frame
        self.in_grace = False    # disappeared a moment ago, still holding the state
        self.last_seen = 0.0
        self.pts = None          # points in pixels
        # fingers / buttons
        self.curl = {d[0]: None for d in FINGERS}
        self.down = {d[0]: False for d in FINGERS}
        self.pending = {d[0]: None for d in FINGERS}   # start of each finger's pending change
        self.trigger = False     # index finger down (or pinch)
        self.pinch = False       # thumb + index pinch
        self.pinch_ratio = None
        self.pinch_pending = None
        self.stab = None         # stabilized position (shake filter)
        self.grip_hold_t = None  # when the grip started being held without seeing the hand
        self.dbg_ang = (0.0, 0.0)  # joystick: hand movement (cm) from the starting point
        self.thumb_open = False  # thumb opened wide (B / Y button)
        self.thumb_ratio = None
        self.thumb_pending = None
        self.grip = False        # middle + ring + pinky down
        self.buttons = {d: False for d in BUTTON_FINGERS}
        self.btn_since = {d: None for d in BUTTON_FINGERS}
        self.locked = set()
        self.system = False
        self.sys_since = None
        self.sys_sent = False
        self.world = None
        self.last_T = None       # last head-space pose sent (4x4), used to start the freeze glide
        self.prev_live = False
        self.gl_on = False       # gliding (or frozen) in world space
        self.gl_pos = None
        self.gl_q = None
        self.gl_t = 0.0
        self.est = False
        self.size_vis = None
        self.upside_down = False
        self.bad_angle = False
        self.bad_since = None
        self.spread = 0.0
        self.rel_length = 0.0
        self.basis = None        # hand rotation in VR: (right, up, forward) vectors
        self.rot_f = self.rot_u = None
        self.rot_R = None
        self.rot_t = None
        self.chi = None
        self.chi_cnt = 0
        self.basis_raw = None    # rotation before the straight-hand correction
        self.rot_fix = np.eye(3) # correction learned with the T key (identity = none)
        self.rot_acc = None      # samples while calibrating (T key)
        self.rot_start = 0.0
        self.rot_until = 0.0
        self.rot_saved = False
        # joystick
        self.anchor = None
        self.joy = (0.0, 0.0)
        self.palm = False        # palm facing the camera (with stabilization time)
        self.palm_cand = False
        self.palm_t0 = 0.0
        self.joy_active = False
        self.arm_since = None
        self.fan_deg = 0.0       # how spread apart the fingers are (degrees)
        # momentum (hand leaves the image fast)
        self.w_pos = None        # last world position while tracked
        self.w_t = 0.0
        self.w_vel = np.zeros(3) # smoothed world velocity (m/s)
        self.was_seen = False
        self.mom_on = False      # carrying the speed / braking right now
        self.mom_used = False    # momentum took over this loss
        self.mom_v = np.zeros(3)
        self.mom_t0 = 0.0
        self.mom_tl = 0.0
        self.mom_dist = 0.0

    def update(self, lm, t, w, h, wl):
        self.visible = True
        self.in_grace = False
        self.last_seen = t
        self.grip_hold_t = None
        self.pts = [(p.x * w, p.y * h) for p in lm]
        self.img_wh = (w, h)
        pts = self.pts
        pc = palm_center(pts)
        self.x, self.y = self.fx(pc[0] / w, t), self.fy(pc[1] / h, t)
        if self.pt is not None and t > self.pt:
            dt = t - self.pt
            self.vx += VEL_SMOOTH * ((self.x - self.px) / dt - self.vx)
            self.vy += VEL_SMOOTH * ((self.y - self.py) / dt - self.vy)
        self.px, self.py, self.pt = self.x, self.y, t
        size = palm_size(pts)
        self.size = self.fs(size, t)
        self.size_vis = self.size
        self.est = False

        # --- bad angle? (hand sideways/"flat" or pointing at the camera): measurements not reliable
        a = (pts[5][0] - pts[0][0], pts[5][1] - pts[0][1])
        b = (pts[17][0] - pts[0][0], pts[17][1] - pts[0][1])
        cross = a[0] * b[1] - a[1] * b[0]
        self.spread = abs(cross) / max(math.hypot(*a) * math.hypot(*b), 1e-6)
        self.rel_length = size / max(dist(pts[5], pts[17]), 1e-6)
        side = self.spread < ANGLE_MIN_SPREAD
        self.bad_angle = side or self.rel_length < ANGLE_MIN_LENGTH
        raw = cross < 0 if self.name == "Left" else cross > 0
        if MIRROR != INVERT_PALM:
            raw = not raw
        self._update_rotation(wl, raw, t)
        self._rot_calib_step(t)
        self.bad_since = None

        # --- upside down? (fingers pointing down in the image): buttons turn off
        cy = (pts[9][1] - pts[0][1]) / max(size, 1e-6)   # > 0 = fingers pointing down
        if self.bad_angle:
            pass
        elif self.upside_down:
            if cy < UPSIDE_EXIT:
                self.upside_down = False
        elif cy > UPSIDE_ENTER:
            self.upside_down = True

        # --- palm facing the camera? (sign of the cross product)
        if not side:
            self._debounce_palm(raw, t)

        # --- fingers down (hysteresis + smoothing + anti-flicker; limit depends on palm/back) ---
        lim_down, lim_release = self.limits()
        w3 = [(q.x, q.y, q.z) for q in wl]
        for name, mcp, tip in FINGERS:
            if self.bad_angle or CONTROLLER_MODE:
                r = math.dist(w3[tip], w3[0]) / max(math.dist(w3[mcp], w3[0]), 1e-6)
            else:
                r = dist(pts[tip], pts[0]) / max(dist(pts[mcp], pts[0]), 1e-6)
            prev = self.curl[name]
            r = r if prev is None else prev + CURL_SMOOTH * (r - prev)
            self.curl[name] = r
            target = self.down[name]
            if target:
                if r > lim_release:
                    target = False
            elif r < lim_down:
                target = True
            if target != self.down[name]:
                if self.pending[name] is None:
                    self.pending[name] = t
                elif t - self.pending[name] >= (FINGER_WAIT if target else FINGER_RELEASE_WAIT):
                    self.down[name] = target
                    self.pending[name] = None
            else:
                self.pending[name] = None
        # trigger = index; grip = middle, ring and pinky. You can have one, the other or both.
        # --- pinch: thumb tip touching index tip (3D, so it works at any angle) ---
        if PINCH_ENABLED and not CONTROLLER_MODE:
            pr = math.dist(w3[4], w3[8]) / max(math.dist(w3[9], w3[0]), 1e-6)
            self.pinch_ratio = pr if self.pinch_ratio is None else self.pinch_ratio + CURL_SMOOTH * (pr - self.pinch_ratio)
            ok_shape = (OK_ENABLED and not self.bad_angle
                        and all((self.curl[d] or 0.0) >= FINGER_EXTENDED for d in ("middle", "ring", "pinky"))
                        and (self.curl["index"] or 9.0) < OK_INDEX_MAX)
            lim_on = max(PINCH_ON, OK_ON) if ok_shape else PINCH_ON
            lim_off = max(PINCH_OFF, OK_OFF) if ok_shape else PINCH_OFF
            target = self.pinch
            if target:
                if self.pinch_ratio > lim_off:
                    target = False
            elif self.pinch_ratio < lim_on:
                target = True
            if target != self.pinch:
                if self.pinch_pending is None:
                    self.pinch_pending = t
                elif t - self.pinch_pending >= (FINGER_WAIT if target else FINGER_RELEASE_WAIT):
                    self.pinch = target
                    self.pinch_pending = None
            else:
                self.pinch_pending = None
        else:
            self.pinch = False
        # --- thumb opened wide (B / Y) ---
        if THUMB_OPEN_ENABLED and not CONTROLLER_MODE:
            tr = math.dist(w3[4], w3[5]) / max(math.dist(w3[9], w3[0]), 1e-6)
            self.thumb_ratio = tr if self.thumb_ratio is None else self.thumb_ratio + CURL_SMOOTH * (tr - self.thumb_ratio)
            target = self.thumb_open
            if target:
                if self.thumb_ratio < THUMB_OPEN_OFF or self.pinch:
                    target = False
            elif self.thumb_ratio > THUMB_OPEN_ON and not self.pinch:
                target = True
            if target != self.thumb_open:
                if self.thumb_pending is None:
                    self.thumb_pending = t
                elif t - self.thumb_pending >= THUMB_WAIT:
                    self.thumb_open = target
                    self.thumb_pending = None
            else:
                self.thumb_pending = None
        else:
            self.thumb_open = False
        # --- fan: angles between neighbouring fingers (3D, so it works at any angle) ---
        dirs = []
        for _, mcp, tip in FINGERS:
            v = tuple(w3[tip][i] - w3[mcp][i] for i in range(3))
            n_ = math.sqrt(sum(c * c for c in v)) or 1e-6
            dirs.append(tuple(c / n_ for c in v))
        fan = 0.0
        for a_, b_ in zip(dirs, dirs[1:]):
            fan += math.degrees(math.acos(max(-1.0, min(1.0, sum(a_[i] * b_[i] for i in range(3))))))
        self.fan_deg = fan
        # trigger = index finger down OR pinch
        enabled = not self.upside_down
        self.trigger = (enabled and self.down["index"]) or self.pinch
        all_down = all(self.down[d] for d in BUTTON_FINGERS)
        self.grip = (enabled or not BLOCK_GRIP_UPSIDE_DOWN) and all_down
        if all_down:
            self.locked = set(BUTTON_FINGERS)
        else:
            self.locked = {d for d in self.locked if self.down[d]}
        combo = (enabled and not self.down["middle"] and self.down["ring"] and self.down["pinky"]
                 and "ring" not in self.locked and "pinky" not in self.locked)
        strict = self.limits()[0] - SYSTEM_STRICT
        cv_ = self.curl
        solid = (not self.bad_angle
                 and cv_["middle"] is not None and cv_["middle"] >= FINGER_EXTENDED
                 and cv_["ring"] is not None and cv_["ring"] < strict
                 and cv_["pinky"] is not None and cv_["pinky"] < strict)
        if combo and solid:
            if self.sys_since is None:
                self.sys_since = t
            self.system = (t - self.sys_since) >= SYSTEM_HOLD
        else:
            self.sys_since = None
            self.system = False
        for d in BUTTON_FINGERS:
            if enabled and not all_down and not combo and self.down[d] and d not in self.locked:
                if self.btn_since[d] is None:
                    self.btn_since[d] = t
                self.buttons[d] = (t - self.btn_since[d]) >= BUTTON_DELAY
            else:
                self.btn_since[d] = None
                self.buttons[d] = False

        self._compute_joy(t, w, h)

    def _update_rotation(self, wl, palm_facing, t=None):
        p = [(q.x, -q.y, q.z) for q in wl]
        p_raw = list(p)
        hold = False
        self.ray_deg = 0.0
        wh = getattr(self, "img_wh", None)
        if wh is not None and self.pts is not None:
            w_, h_ = wh
            if ROT_RAY_GAIN > 0:
                w0_ = h_ if ROTATION in (90, 270) else w_
                f_ = (w0_ / 2.0) / math.tan(math.radians(CAM_FOV_H) / 2.0)
                pc_ = palm_center(self.pts)
                Rr = ray_rotation(pc_[0], pc_[1], w_, h_, f_, ROT_RAY_GAIN)
                self.ray_deg = math.degrees(math.atan2(math.hypot(pc_[0] - w_ / 2.0, pc_[1] - h_ / 2.0), f_))
                p = [tuple(float(v) for v in (Rr @ np.array(q))) for q in p]
            if self.rot_R is not None and ROT_EDGE_HOLD > 0:
                mx_, my_ = ROT_EDGE_HOLD * w_, ROT_EDGE_HOLD * h_
                hold = any(self.pts[i][0] < mx_ or self.pts[i][0] > w_ - mx_ or
                           self.pts[i][1] < my_ or self.pts[i][1] > h_ - my_ for i in (0, 5, 9, 13, 17))
        self.wl5 = [p[i] for i in (0, 5, 9, 13, 17)]
        self.rot_hold = hold
        if not hold:
            self._rot_filter(p, p_raw, palm_facing, t)
        self._rot_basis()

    def _rot_filter(self, p, p_raw, palm_facing, t):
        sub = lambda a, b: (a[0] - b[0], a[1] - b[1], a[2] - b[2])

        def geom(pp):
            if ROT_PALM_ONLY:
                c4 = tuple(sum(pp[i][j] for i in (5, 9, 13, 17)) / 4.0 for j in range(3))   # middle of the 4 knuckles
                return _norm(sub(c4, pp[0])), _cross(sub(pp[5], pp[17]), sub(c4, pp[0]))
            return _norm(sub(pp[9], pp[0])), _cross(sub(pp[5], pp[0]), sub(pp[17], pp[0]))

        f, n = geom(p)
        n_chi = geom(p_raw)[1]    # which face is showing: decided with the uncorrected landmarks (same as the palm flag)
        if self.chi is None:
            self.chi = 1.0 if (n_chi[2] < 0) == palm_facing else -1.0
            self.chi_cnt = 0
        elif abs(_norm(n_chi)[2]) > 0.5:
            cand = 1.0 if (n_chi[2] < 0) == palm_facing else -1.0
            if cand != self.chi:
                self.chi_cnt += 1
                if self.chi_cnt >= CHI_CONFIRM:
                    self.chi = cand
                    self.chi_cnt = 0
            else:
                self.chi_cnt = 0
        sign = self.chi if INVERT_HAND_ROT else -self.chi
        u = _norm(tuple(sign * c for c in n))
        d = sum(u[i] * f[i] for i in range(3))
        u = _norm(tuple(u[i] - d * f[i] for i in range(3)))
        Rt = np.array([_cross(u, f), u, f]).T
        if self.rot_R is None:
            self.rot_R = Rt
        else:
            dt = (t - self.rot_t) if (t is not None and self.rot_t is not None and t > self.rot_t) else 1.0 / 30.0
            X = Rt @ self.rot_R.T
            ang = math.acos(max(-1.0, min(1.0, (float(np.trace(X)) - 1.0) / 2.0)))
            if ang > 1e-4:
                k = min(1.0, ROT_SMOOTH_MIN + ROT_SMOOTH_GAIN * 2.0 * math.sin(ang / 2.0))
                step = min(ang * k, math.radians(ROT_MAX_DPS) * dt)
                ax = np.array([X[2, 1] - X[1, 2], X[0, 2] - X[2, 0], X[1, 0] - X[0, 1]])
                nrm = float(np.linalg.norm(ax))
                if nrm < 1e-6:
                    cols = X + np.eye(3)
                    ax = cols[:, int(np.argmax(np.linalg.norm(cols, axis=0)))]
                    nrm = float(np.linalg.norm(ax))
                ax = ax / max(nrm, 1e-9)
                Kx = np.array([[0.0, -ax[2], ax[1]], [ax[2], 0.0, -ax[0]], [-ax[1], ax[0], 0.0]])
                self.rot_R = (np.eye(3) + math.sin(step) * Kx + (1.0 - math.cos(step)) * (Kx @ Kx)) @ self.rot_R
        self.rot_t = t

    def _rot_basis(self):
        f = _norm(tuple(float(v) for v in self.rot_R[:, 2]))
        u = _norm(tuple(float(v) for v in self.rot_R[:, 1]))
        d = sum(u[i] * f[i] for i in range(3))
        u = _norm(tuple(u[i] - d * f[i] for i in range(3)))
        self.rot_f, self.rot_u = f, u
        r = _cross(u, f)
        self.basis_raw = (r, u, f)
        a = math.radians(TILT_DOWN_DEG.get(self.name, 40.0))
        tilt = np.array([[1.0, 0.0, 0.0],
                         [0.0, math.cos(a), -math.sin(a)],
                         [0.0, math.sin(a), math.cos(a)]])
        fix = self.rot_fix if USE_ROT_FIX else np.eye(3)
        roll = math.radians(HAND_ROLL_DEG.get(self.name, 0.0)) * (-1.0 if ROLL_FLIP else 1.0)
        cr, sr = math.cos(roll), math.sin(roll)
        roll_m = np.array([[cr, -sr, 0.0], [sr, cr, 0.0], [0.0, 0.0, 1.0]])
        lean = math.radians(HAND_TILT_DEG.get(self.name, 0.0)) * (-1.0 if TILT_FLIP else 1.0)
        cl, sl = math.cos(lean), math.sin(lean)
        lean_m = np.array([[cl, 0.0, sl], [0.0, 1.0, 0.0], [-sl, 0.0, cl]])
        B = np.array([r, u, f]).T
        if TILT_INVERT:
            S = np.diag([-1.0, 1.0, 1.0])
            B = S @ B @ S
        if TWIST_AXIS >= 0:
            yv = np.array([0.0, 1.0, 0.0])
            cf = float(yv @ B[:, 2])
            if cf < -0.999:
                sw = np.diag([1.0, -1.0, -1.0])
            else:
                vv = np.cross(yv, B[:, 2])
                K = np.array([[0.0, -vv[2], vv[1]], [vv[2], 0.0, -vv[0]], [-vv[1], vv[0], 0.0]])
                sw = np.eye(3) + K + K @ K / (1.0 + cf)
            Bref = np.array([[-1.0, 0.0, 0.0], [0.0, 0.0, 1.0], [0.0, 1.0, 0.0]])
            T = Bref.T @ sw.T @ B
            th = math.atan2(T[1, 0], T[0, 0])
            self.twist_deg = math.degrees(th)
            ct, st = math.cos(th), math.sin(th)
            Q = lean_m @ roll_m
            ax = Q[:, TWIST_AXIS]
            Kx = np.array([[0.0, -ax[2], ax[1]], [ax[2], 0.0, -ax[0]], [-ax[1], ax[0], 0.0]])
            twist_m = np.eye(3) + st * Kx + (1.0 - ct) * (Kx @ Kx)
            M = tilt @ fix @ (sw @ Bref) @ twist_m @ Q
        else:
            M = tilt @ fix @ B @ lean_m @ roll_m
        self.basis = tuple(tuple(float(v) for v in M[:, i]) for i in range(3))

    def start_rot_calib(self, t):
        """T key: get into the pose (back to the camera, fingers up), then hold still."""
        self.rot_acc = []
        self.rot_start = t + ROT_CALIB_WAIT
        self.rot_until = self.rot_start + ROT_CALIB_SECS

    def _rot_calib_step(self, t):
        if self.rot_acc is None or self.basis_raw is None:
            return
        if self.rot_start <= t < self.rot_until:
            self.rot_acc.append(np.array(self.basis_raw).T)
        self.rot_calib_tick(t)

    def rot_calib_tick(self, t):
        """Finishes the calibration when the time is up (also called every frame from main)."""
        if self.rot_acc is None or t < self.rot_until:
            return
        samples, self.rot_acc = self.rot_acc, None
        if len(samples) < 5:
            print(f"{self.name}: straight-hand calibration failed (hand not seen). Try again.")
            return
        U, _, Vt = np.linalg.svd(sum(samples) / len(samples))   # average, back to a clean rotation
        Mn = U @ Vt
        if np.linalg.det(Mn) < 0:
            U[:, -1] *= -1
            Mn = U @ Vt
        self.rot_fix = _snap_rotation(Mn) @ Mn.T
        self.rot_saved = True
        tilt = math.degrees(math.acos(max(-1.0, min(1.0, (np.trace(self.rot_fix) - 1) / 2))))
        print(f"{self.name}: straight-hand calibration done (corrected a tilt of {tilt:.0f} degrees).")

    def predicted(self):
        dx = max(-PREDICT_MAX, min(PREDICT_MAX, self.vx * PREDICT_SECS))
        dy = max(-PREDICT_MAX, min(PREDICT_MAX, self.vy * PREDICT_SECS))
        return self.x + dx, self.y + dy

    def limits(self):
        """(down, release): palm facing uses FINGER_DOWN; back of the hand needs more bending."""
        base = FINGER_DOWN if self.palm else FINGER_DOWN - BACK_STRICTER
        if CONTROLLER_MODE:
            base -= CONTROLLER_STRICTER
        base = max(base, 0.5)
        return base, base + HYSTERESIS

    def _debounce_palm(self, raw, t):
        if raw == self.palm:
            self.palm_cand = raw
            return
        if self.palm_cand != raw:
            self.palm_cand = raw
            self.palm_t0 = t
        elif t - self.palm_t0 >= PALM_DEBOUNCE:
            self.palm = raw

    def _compute_joy(self, t, w, h):
        if not self.palm or self.grip:
            self.joy = (0.0, 0.0)
            self.joy_active = False
            self.arm_since = None
            self.anchor = None
            self.fjx.reset(); self.fjy.reset()
            return
        if not self.joy_active:
            ready = (all((self.curl[d[0]] or 0.0) >= FINGER_EXTENDED for d in FINGERS)
                     and self.fan_deg >= FAN_MIN_DEG and not self.bad_angle)
            if not ready:
                self.arm_since = None
            elif self.arm_since is None:
                self.arm_since = t
            elif t - self.arm_since >= ARM_SECS:
                if self.x is not None and self.size:
                    self.joy_active = True
                    self.anchor = (self.x, self.y)      # center of the stick = where the hand is now
            self.joy = (0.0, 0.0)
            return
        if self.anchor is None or not self.size:
            self.joy = (0.0, 0.0)
            return
        # hand movement since the stick turned on, in cm (the palm size gives the scale, so distance does not matter)
        cm_px = PALM_METERS * 100.0 / self.size
        dx = (self.x - self.anchor[0]) * w * cm_px
        dy = -(self.y - self.anchor[1]) * h * cm_px     # hand up = positive
        if VR_INV_X:
            dx = -dx                                    # same direction the hand moves in VR
        self.dbg_ang = (dx, dy)
        # round dead zone: inside the circle the stick is 0, outside it grows smoothly up to 1
        r = math.hypot(dx, dy)
        if r <= JOY_POS_DEAD_CM:
            jx = jy = 0.0
        else:
            span = max(JOY_POS_MAX_CM - JOY_POS_DEAD_CM, 0.1)
            k = (r - JOY_POS_DEAD_CM) / span / r
            jx, jy = dx * k, dy * k
        if INVERT_JOY_Y:
            jy = -jy
        jx, jy = self.fjx(jx, t), self.fjy(jy, t)
        mag = math.hypot(jx, jy)
        if mag > 1.0:
            jx, jy = jx / mag, jy / mag
        sx = -1.0 if INVERT_JOY_X else 1.0
        self.joy = (sx * jx, jy)

    def _release_all(self):
        """Releases buttons and joystick (the hand position is not touched).
        The grip is kept if the hand was gripping (HOLD_GRIP_ON_LOSS)."""
        keep_grip = HOLD_GRIP_ON_LOSS and self.grip
        if keep_grip:
            if self.grip_hold_t is None:
                self.grip_hold_t = time.time()
            elif HOLD_GRIP_MAX_SECS > 0 and time.time() - self.grip_hold_t > HOLD_GRIP_MAX_SECS:
                keep_grip = False
        if not keep_grip:
            self.grip_hold_t = None
        self.upside_down = False
        self.palm = False
        self.palm_cand = False
        self.trigger = False
        self.pinch = False
        self.pinch_ratio = None
        self.pinch_pending = None
        self.thumb_open = False
        self.thumb_ratio = None
        self.thumb_pending = None
        self.grip = keep_grip
        self.buttons = {d: False for d in BUTTON_FINGERS}
        self.btn_since = {d: None for d in BUTTON_FINGERS}
        self.locked = set(BUTTON_FINGERS) if keep_grip else set()
        self.system = False
        self.sys_since = None
        self.joy_active = False
        self.arm_since = None
        self.anchor = None
        self.curl = {d[0]: None for d in FINGERS}
        self.down = {d[0]: (keep_grip and d[0] in BUTTON_FINGERS) for d in FINGERS}   # kept down so there is no blip when the hand returns
        self.pending = {d[0]: None for d in FINGERS}
        self.joy = (0.0, 0.0)
        self.fjx.reset(); self.fjy.reset()

    def update_from_arm(self, t, ex, ey, size):
        if not self.est:
            self._release_all()
            self.est = True
        self.visible = False
        self.in_grace = False
        self.pts = None
        self.bad_angle = False
        self.x, self.y = self.fx(ex, t), self.fy(ey, t)
        self.size = self.fs(size, t)
        self.vx = self.vy = 0.0
        self.px, self.py, self.pt = self.x, self.y, t

    def lost(self):
        # position (x, y) stays frozen; buttons and joystick are released
        self.est = False
        self.visible = False
        self.in_grace = False
        self.pts = None
        self.bad_angle = False
        self.bad_since = None
        self._release_all()
        self.fx.reset(); self.fy.reset(); self.fs.reset()
        self.vx = self.vy = 0.0
        self.pt = None


# ---------------- Which detection is which hand ----------------
def bounding_box(pts):
    xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
    return min(xs), min(ys), max(xs), max(ys)


def iou(a, b):
    ix = max(0, min(a[2], b[2]) - max(a[0], b[0]))
    iy = max(0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = ix * iy
    area = lambda r: max(0, r[2] - r[0]) * max(0, r[3] - r[1])
    union = area(a) + area(b) - inter
    return inter / union if union > 0 else 0.0


def assign_hands(res, hands, w, h):
    """Decides which detection is the left hand and which is the right.
    Does not trust the MediaPipe label: uses image position and continuity."""
    dets = []
    for lm, wl, cat in zip(res.hand_landmarks, res.hand_world_landmarks, res.handedness):
        pts = [(p.x * w, p.y * h) for p in lm]
        dets.append({"lm": lm, "wl": wl, "pts": pts, "score": cat[0].score,
                     "label": cat[0].category_name, "box": bounding_box(pts)})

    # remove duplicate detections (the same hand detected twice)
    dets.sort(key=lambda d: -d["score"])
    unique = []
    for d in dets:
        if all(iou(d["box"], u["box"]) < 0.35 for u in unique):
            unique.append(d)
    dets = unique[:2]

    out = {}
    if len(dets) == 2:
        dets.sort(key=lambda d: d["pts"][0][0])
        left, right = dets
        if SWAP_HANDS:
            left, right = right, left
        out["Left"], out["Right"] = left, right
    elif len(dets) == 1:
        d = dets[0]
        wx, wy = palm_center(d["pts"])
        cost = {n: math.hypot(wx - m.x * w, wy - m.y * h)
                for n, m in hands.items()
                if m.x is not None and (m.visible or m.in_grace or m.est)}
        if len(cost) == 2:
            name = min(cost, key=cost.get)
        elif len(cost) == 1:
            known = next(iter(cost))
            other = "Right" if known == "Left" else "Left"
            name = known if cost[known] < 0.35 * w else other
        else:
            name = "Left" if d["label"] == "Left" else "Right"
            if not SWAP_HANDS:
                name = "Right" if name == "Left" else "Left"
        out[name] = d
    return out


# ---------------- Camera and model ----------------
def open_camera():
    """Tries the requested index first, then the others, with several backends."""
    if URL:
        print(f"Connecting to {URL} (waits up to 5 s)...")
        try:
            cap = cv2.VideoCapture(URL, cv2.CAP_FFMPEG,
                                   [cv2.CAP_PROP_OPEN_TIMEOUT_MSEC, 5000,
                                    cv2.CAP_PROP_READ_TIMEOUT_MSEC, 5000])
        except TypeError:
            cap = cv2.VideoCapture(URL)
        if cap.isOpened():
            print("Connected to", URL)
            return cap
        cap.release()
        return None
    indices = [CAMERA_INDEX] + [i for i in range(6) if i != CAMERA_INDEX]
    backends = [("default", None), ("MSMF", cv2.CAP_MSMF), ("DSHOW", cv2.CAP_DSHOW)]
    for i in indices:
        for name, be in backends:
            cap = cv2.VideoCapture(i) if be is None else cv2.VideoCapture(i, be)
            if cap.isOpened():
                ok, _ = cap.read()
                if ok:
                    print(f"Camera opened: index {i}, backend {name}")
                    return cap
            cap.release()
    return None


def raise_priority():
    if os.name != "nt":
        return
    try:
        import ctypes
        ctypes.windll.kernel32.SetPriorityClass(ctypes.windll.kernel32.GetCurrentProcess(), 0x00000080)
        ctypes.windll.winmm.timeBeginPeriod(1)   # 1 ms timer: waits and waitKey stop adding up to 15 ms
    except Exception:
        pass


class CameraReader:
    def __init__(self, cap):
        self.cap = cap
        self.frame = None
        self.fresh = False
        self.running = True
        self.lock = threading.Lock()
        self.event = threading.Event()
        self.thread = threading.Thread(target=self._loop, daemon=True)
        self.thread.start()

    def _loop(self):
        while self.running:
            ok, f = self.cap.read()
            if not ok:
                time.sleep(0.005)
                continue
            with self.lock:
                self.frame = f
                self.fresh = True
            self.event.set()

    def read(self):
        # wakes up the instant a new frame arrives (no 1 ms polling)
        if not self.event.wait(0.5):
            return False, None
        with self.lock:
            self.event.clear()
            self.fresh = False
            return True, self.frame

    def release(self):
        self.running = False
        self.thread.join(timeout=1.0)
        self.cap.release()


def download_model():
    if not os.path.exists(MODEL_PATH):
        print("Downloading hand model (first time only)...")
        urllib.request.urlretrieve(MODEL_URL, MODEL_PATH)


class SkinArm:
    def __init__(self):
        self.hist = None
        self.k3 = np.ones((3, 3), np.uint8)
        self.k5 = np.ones((5, 5), np.uint8)

    def learn(self, ycc, hands, sx, sy):
        mask = np.zeros(ycc.shape[:2], np.uint8)
        used = False
        for m in hands.values():
            if not m.visible or m.pts is None or m.bad_angle:
                continue
            pts = np.array([(m.pts[i][0] * sx, m.pts[i][1] * sy) for i in (0, 1, 5, 9, 13, 17)], np.int32)
            cv2.fillConvexPoly(mask, cv2.convexHull(pts), 255)
            used = True
        if not used:
            return
        mask = cv2.erode(mask, self.k3, iterations=2)
        if cv2.countNonZero(mask) < 40:
            return
        hist = cv2.calcHist([ycc], [1, 2], mask, [32, 32], [0, 256, 0, 256])
        cv2.normalize(hist, hist, 0, 255, cv2.NORM_MINMAX)
        self.hist = hist if self.hist is None else cv2.addWeighted(self.hist, 0.95, hist, 0.05, 0)

    def find(self, ycc, last, size):
        if self.hist is None or size is None or size < 4:
            return None
        hh, ww = ycc.shape[:2]
        bp = cv2.calcBackProject([ycc], [1, 2], self.hist, [0, 256, 0, 256], 1)
        bp = cv2.GaussianBlur(bp, (5, 5), 0)
        _, mask = cv2.threshold(bp, SKIN_THRESH, 255, cv2.THRESH_BINARY)
        mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, self.k3)
        mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, self.k5)
        contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        lp = (float(last[0]), float(last[1]))
        best, best_score = None, 0.0
        for c in contours:
            area = cv2.contourArea(c)
            if area < 0.8 * size * size:
                continue
            d = max(0.0, -cv2.pointPolygonTest(c, lp, True))
            if d > ARM_SEARCH * size:
                continue
            score = area / (1.0 + d)
            if score > best_score:
                best, best_score = c, score
        if best is None:
            return None
        pts = best.reshape(-1, 2).astype(np.float32)
        mean = pts.mean(axis=0)
        _, _, vt = np.linalg.svd(pts - mean, full_matrices=False)
        axis = vt[0]
        proj = (pts - mean) @ axis
        lo, hi = float(proj.min()), float(proj.max())
        length = hi - lo
        if length < 1.0:
            return None
        cands = []
        for sign, val in ((-1.0, lo), (1.0, hi)):
            wide = pts[np.abs(proj - val) <= 0.12 * length]
            near = pts[np.abs(proj - val) <= 0.05 * length]
            cands.append((wide, near, axis * sign))
        da = float(np.hypot(*(cands[0][1].mean(axis=0) - lp)))
        db = float(np.hypot(*(cands[1][1].mean(axis=0) - lp)))
        wide, near, out = cands[0] if da <= db else cands[1]
        edge = wide[(wide[:, 0] <= 1) | (wide[:, 1] <= 1) | (wide[:, 0] >= ww - 2) | (wide[:, 1] >= hh - 2)]
        if len(edge) >= 2:
            tip = edge.mean(axis=0) + out * (ARM_EXT * size)
        else:
            tip = near.mean(axis=0)
        return float(tip[0]), float(tip[1])


# ---------------- Saved calibration ----------------
def save_angles():
    try:
        with open(ANGLES_FILE, "w", encoding="utf-8") as f:
            json.dump({"tilt_down": TILT_DOWN_DEG, "roll": HAND_ROLL_DEG, "tilt": HAND_TILT_DEG}, f)
    except Exception as e:
        print("Could not save the angles:", e)


def load_angles():
    try:
        with open(ANGLES_FILE, encoding="utf-8") as f:
            d = json.load(f)
        td = d.get("tilt_down")
        if isinstance(td, dict):
            for n in TILT_DOWN_DEG:
                if n in td:
                    TILT_DOWN_DEG[n] = float(td[n])
        elif td is not None:
            for n in TILT_DOWN_DEG:
                TILT_DOWN_DEG[n] = float(td)
        for key, target in (("roll", HAND_ROLL_DEG), ("tilt", HAND_TILT_DEG)):
            for n in ("Left", "Right"):
                if n in d.get(key, {}):
                    target[n] = float(d[key][n])
        print(f"Angles loaded: down {TILT_DOWN_DEG}, roll {HAND_ROLL_DEG}, lateral {HAND_TILT_DEG}")
    except FileNotFoundError:
        pass
    except Exception as e:
        print("Could not load the angles:", e)


ANGLE_MIRROR = {"roll": {"Left": 1.0, "Right": -1.0}, "tilt": {"Left": -1.0, "Right": 1.0}}   # the two hands turn opposite ways


def _wrap(v):
    return ((v + 180.0) % 360.0) - 180.0        # angles are circular: no limit, 190 becomes -170


ANGLE_TARGET = "Both"


def cycle_angle_target():
    global ANGLE_TARGET
    ANGLE_TARGET = {"Both": "Left", "Left": "Right", "Right": "Both"}[ANGLE_TARGET]
    print("Angle keys now adjust:", ANGLE_TARGET)


def adjust_angle(which, step):
    """which: 'down' / 'tilt' / 'roll'. No limits: the values go positive or negative freely.
    Both hands move together, mirrored, or only the hand chosen with the 7 key."""
    targets = ("Left", "Right") if ANGLE_TARGET == "Both" else (ANGLE_TARGET,)
    if which == "down":
        for n in targets:
            TILT_DOWN_DEG[n] = _wrap(TILT_DOWN_DEG[n] + step)
    else:
        d = HAND_TILT_DEG if which == "tilt" else HAND_ROLL_DEG
        for n in targets:
            d[n] = _wrap(d[n] + step * ANGLE_MIRROR[which].get(n, 1.0))
    save_angles()
    print(f"Angles -> down {TILT_DOWN_DEG} | lateral {HAND_TILT_DEG} | roll {HAND_ROLL_DEG}")


def save_calib(ref_size, hands=None):
    try:
        data = {"ref_size": ref_size}
        if hands is not None:
            data["rot_fix"] = {n: hd.rot_fix.tolist() for n, hd in hands.items()}
            data["aim_mode"] = 0
        with open(CALIB_FILE, "w", encoding="utf-8") as f:
            json.dump(data, f)
        print("Calibration saved.")
    except Exception as e:
        print("Could not save the calibration:", e)


def load_calib(ref_size, hands=None):
    path = CALIB_FILE if os.path.exists(CALIB_FILE) else OLD_CALIB_FILE
    try:
        with open(path, encoding="utf-8") as f:
            d = json.load(f)
        ref = d.get("ref_size") or d.get("tam_ref") or {}
        for k in ref_size:
            value = ref.get(k) or ref.get(OLD_HAND_NAMES[k])
            if value:
                ref_size[k] = value
        if d.get("rot_fix") and d.get("aim_mode", 0) != 0:
            print("Saved straight-hand correction was made with another aim mode: press T again.")
        else:
            for name, hd in (hands or {}).items():
                fix = (d.get("rot_fix") or {}).get(name)
                if fix:
                    hd.rot_fix = np.array(fix, dtype=float)
        print("Calibration loaded.")
    except FileNotFoundError:
        print("No saved depth calibration yet (press C with the hands at the reference distance).")
    except Exception as e:
        print("Could not read the calibration (ignoring):", e)


# ---------------- SteamVR via VMT (OSC over UDP) ----------------
def _osc_pad(b):
    return b + b"\x00" * (-len(b) % 4)


def osc_msg(address, *args):
    types, data = ",", b""
    for a in args:
        if isinstance(a, str):
            types += "s"
            data += _osc_pad(a.encode("utf-8") + b"\x00")
        elif isinstance(a, int):
            types += "i"
            data += struct.pack(">i", a)
        else:
            types += "f"
            data += struct.pack(">f", float(a))
    return (_osc_pad(address.encode() + b"\x00") + _osc_pad(types.encode() + b"\x00") + data)


class VMT:
    def __init__(self, ip, port):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.addr = (ip, port)
        self._last = {}

    def send(self, address, *args):
        if address.startswith("/VMT/Input/"):
            # buttons/triggers/joystick: only send when the value changed (refresh every 0.5 s for safety)
            key = (address, args[:2])
            now = time.time()
            last = self._last.get(key)
            if last is not None and last[0] == args and now - last[1] < 0.5:
                return
            self._last[key] = (args, now)
        try:
            self.sock.sendto(osc_msg(address, *args), self.addr)
        except OSError:
            pass

    def pose(self, idx, mode, pos, q):
        # Joint: position in HMD space and rotation relative to it (the hand follows the head)
        self.send("/VMT/Joint/Unity", idx, mode, 0.0, pos[0], pos[1], pos[2],
                  q[0], q[1], q[2], q[3], "HMD")

    def room_pose(self, idx, mode, pos, q):
        self.send("/VMT/Room/Unity", idx, mode, 0.0, pos[0], pos[1], pos[2],
                  q[0], q[1], q[2], q[3])

    def shutdown(self):
        for idx in VMT_INDEX.values():
            self.send("/VMT/Joint/Unity", idx, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, "HMD")


class HeadPose:
    def __init__(self):
        self.vr = None
        self.next_try = 0.0
        self.warned = False

    def _connect(self):
        now = time.time()
        if openvr is None:
            if not self.warned:
                print("openvr not installed (pip install openvr): lost hands stay in head space")
                self.warned = True
            return
        if now < self.next_try:
            return
        self.next_try = now + 5.0
        try:
            self.vr = openvr.init(openvr.VRApplication_Background)
        except Exception:
            self.vr = None

    def get(self):
        if self.vr is None:
            self._connect()
            if self.vr is None:
                return None
        try:
            poses = self.vr.getDeviceToAbsoluteTrackingPose(
                openvr.TrackingUniverseRawAndUncalibrated, 0, openvr.k_unMaxTrackedDeviceCount)
            p = poses[openvr.k_unTrackedDeviceIndex_Hmd]
            if not p.bPoseIsValid:
                return None
            m = p.mDeviceToAbsoluteTracking
            T = np.eye(4)
            for i in range(3):
                for j in range(4):
                    T[i, j] = m[i][j]
        except Exception:
            self.vr = None
            return None
        S = np.diag([1.0, 1.0, -1.0, 1.0])
        return S @ T @ S


def _norm(v):
    n = math.sqrt(sum(c * c for c in v)) or 1e-9
    return tuple(c / n for c in v)


def _cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def quat_from_basis(r, u, f):
    """Quaternion (qx, qy, qz, qw) of the rotation whose columns are right, up, forward."""
    m00, m01, m02 = r[0], u[0], f[0]
    m10, m11, m12 = r[1], u[1], f[1]
    m20, m21, m22 = r[2], u[2], f[2]
    tr = m00 + m11 + m22
    if tr > 0:
        s = math.sqrt(tr + 1) * 2
        return ((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25 * s)
    if m00 > m11 and m00 > m22:
        s = math.sqrt(1 + m00 - m11 - m22) * 2
        return (0.25 * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
    if m11 > m22:
        s = math.sqrt(1 + m11 - m00 - m22) * 2
        return ((m01 + m10) / s, 0.25 * s, (m12 + m21) / s, (m02 - m20) / s)
    s = math.sqrt(1 + m22 - m00 - m11) * 2
    return ((m02 + m20) / s, (m12 + m21) / s, 0.25 * s, (m10 - m01) / s)


def _snap_rotation(M):
    """Nearest axis-aligned rotation to M (columns: right, up, forward)."""
    axes = np.eye(3)

    def dominant(v, banned=None):
        i = max((k for k in range(3) if k != banned), key=lambda k: abs(v[k]))
        return i, (1.0 if v[i] >= 0 else -1.0) * axes[i]

    fi, f_t = dominant(M[:, 2])
    _, u_t = dominant(M[:, 1], banned=fi)
    r_t = np.array(_cross(tuple(u_t), tuple(f_t)))
    return np.array([r_t, u_t, f_t]).T


def hand_pose(m, name, ref_size, w, h, f_px):
    """Position (m) and rotation of the hand in head space: x right, y up, z forward."""
    if m.x is None or not m.size or m.basis is None:
        return REST[name], (0.0, 0.0, 0.0, 1.0)
    if ref_size.get(name):
        z = CALIB_DIST * ref_size[name] / m.size        # calibrated with the C key
    else:
        z = f_px * PALM_METERS / m.size                 # estimate from the real palm size
    z = min(max(z, DEPTH_MIN), DEPTH_MAX)
    mx, my = m.predicted()
    px, py = mx * w, my * h
    x = (px - w / 2) * z / f_px * POS_GAIN
    y = -(py - h / 2) * z / f_px * POS_GAIN
    if VR_INV_X:
        x = -x
    x += HAND_CLOSE_X if name == "Left" else -HAND_CLOSE_X
    pos = (x, y, z)
    if STABLE_RADIUS > 0:
        p_ = np.array(pos)
        if m.stab is None:
            m.stab = p_
        else:
            d_ = p_ - m.stab
            n_ = float(np.linalg.norm(d_))
            if n_ > STABLE_RADIUS:
                m.stab = p_ - d_ * (STABLE_RADIUS / n_)   # follows the hand, staying STABLE_RADIUS behind
        pos = tuple(float(v) for v in m.stab)
    r, u, f = m.basis
    return pos, quat_from_basis(r, u, f)


def send_vr(vmt, hands, ref_size, w, h, f_px, head=None):
    hm = head.get() if (head is not None and FREEZE_WORLD_ON_LOSS) else None
    for name, m in hands.items():
        idx = VMT_INDEX[name]
        live = m.visible or m.in_grace or m.est
        pos, q = hand_pose(m, name, ref_size, w, h, f_px)
        now_s = time.time()
        dt = min(max(now_s - m.gl_t, 1e-3), 0.1)
        m.gl_t = now_s
        T = None
        if hm is not None and m.x is not None and m.size and m.basis is not None:
            T = np.eye(4)
            T[:3, :3] = np.array(m.basis).T
            T[:3, 3] = pos
        # --- momentum: world velocity while tracked; carries it when the hand leaves the image fast ---
        seen = m.visible or m.est
        if hm is not None and T is not None and seen:
            pw = (hm @ T)[:3, 3]
            if m.w_pos is not None and 1e-3 < now_s - m.w_t < 0.15:
                m.w_vel = m.w_vel + MOMENTUM_VEL_SMOOTH * ((pw - m.w_pos) / (now_s - m.w_t) - m.w_vel)
            else:
                m.w_vel = np.zeros(3)
            m.w_pos, m.w_t = pw.copy(), now_s
            m.mom_on = m.mom_used = False
        elif MOMENTUM and hm is not None and m.was_seen and not seen and not m.mom_used \
                and m.world is not None and m.x is not None and now_s - m.w_t < 0.2:
            speed = float(np.linalg.norm(m.w_vel))
            edge = min(m.x, 1.0 - m.x, m.y, 1.0 - m.y) < MOMENTUM_EDGE
            if speed >= MOMENTUM_MIN_SPEED and (edge or not MOMENTUM_EDGE_ONLY):
                m.mom_v = m.w_vel * min(1.0, MOMENTUM_MAX_SPEED / speed)
                m.mom_on = m.mom_used = True
                m.mom_t0 = m.mom_tl = now_s
                m.mom_dist = 0.0
        m.was_seen = seen
        live = m.visible or m.est or (m.in_grace and not m.mom_used)
        mom_active = m.mom_on and not live and m.world is not None and hm is not None
        # hand just got lost: start gliding from where it was actually shown
        if hm is not None and m.prev_live and not live and m.last_T is not None and not m.gl_on:
            M0 = hm @ m.last_T
            m.gl_pos = M0[:3, 3].copy()
            m.gl_q = np.array(quat_from_basis(tuple(float(v) for v in M0[:3, 0]),
                                              tuple(float(v) for v in M0[:3, 1]),
                                              tuple(float(v) for v in M0[:3, 2])))
            m.gl_on = True
        if live and T is not None:
            tgt = hm @ T                       # where the hand really is now (world)
            m.world = tgt
        else:
            tgt = m.world                      # frozen spot (world)
        glided = False
        if mom_active:
            el = now_s - m.mom_t0
            dtm = min(max(now_s - m.mom_tl, 1e-3), 0.05)
            m.mom_tl = now_s
            if el > MOMENTUM_HOLD:
                m.mom_v = m.mom_v * math.exp(-dtm / MOMENTUM_TAU)
            step = m.mom_v * dtm
            m.world[:3, 3] += step
            m.mom_dist += float(np.linalg.norm(step))
            if float(np.linalg.norm(m.mom_v)) < 0.15 or m.mom_dist >= MOMENTUM_MAX_DIST or el > MOMENTUM_MAX_SECS:
                m.mom_on = False                     # stopped: stays frozen right here
            mq = quat_from_basis(tuple(float(v) for v in m.world[:3, 0]),
                                 tuple(float(v) for v in m.world[:3, 1]),
                                 tuple(float(v) for v in m.world[:3, 2]))
            m.gl_pos = m.world[:3, 3].copy()         # so the glide back (when the hand returns) starts from here
            m.gl_q = np.array(mq)
            m.gl_on = True
            vmt.room_pose(idx, VMT_MODE[name], tuple(float(v) for v in m.gl_pos), mq)
            glided = True
        elif m.gl_on and tgt is not None and hm is not None:
            tq = np.array(quat_from_basis(tuple(float(v) for v in tgt[:3, 0]),
                                          tuple(float(v) for v in tgt[:3, 1]),
                                          tuple(float(v) for v in tgt[:3, 2])))
            if float(np.dot(m.gl_q, tq)) < 0:
                tq = -tq
            k = 1.0 - math.exp(-dt / GLIDE_TAU)
            m.gl_pos = m.gl_pos + k * (tgt[:3, 3] - m.gl_pos)
            nq = m.gl_q + k * (tq - m.gl_q)
            m.gl_q = nq / (np.linalg.norm(nq) or 1e-9)
            if live and np.linalg.norm(tgt[:3, 3] - m.gl_pos) < 0.01 and float(np.dot(m.gl_q, tq)) > 0.9995:
                m.gl_on = False                # caught up: back to normal head-space tracking
            else:
                vmt.room_pose(idx, VMT_MODE[name], tuple(float(v) for v in m.gl_pos),
                              tuple(float(v) for v in m.gl_q))
                glided = True
        elif m.gl_on:
            m.gl_on = False
        if not glided:
            if not live and m.world is not None:
                wp = tuple(float(v) for v in m.world[:3, 3])
                wq = quat_from_basis(tuple(float(v) for v in m.world[:3, 0]),
                                     tuple(float(v) for v in m.world[:3, 1]),
                                     tuple(float(v) for v in m.world[:3, 2]))
                vmt.room_pose(idx, VMT_MODE[name], wp, wq)
            else:
                vmt.pose(idx, VMT_MODE[name], pos, q)
                if T is not None and live:
                    m.last_T = T
        m.prev_live = live
        # Index compatible: Trigger 0 = trigger, Trigger 1 = grip, Joystick 1 = thumb
        vmt.send("/VMT/Input/Trigger", idx, 0, 0.0, 1.0 if m.trigger else 0.0)
        vmt.send("/VMT/Input/Trigger/Click", idx, 0, 0.0, 1 if m.trigger else 0)
        vmt.send("/VMT/Input/Trigger", idx, 1, 0.0, 1.0 if m.grip else 0.0)
        for finger, button in VMT_BUTTONS.items():
            vmt.send("/VMT/Input/Button", idx, button, 0.0, 1 if m.buttons[finger] else 0)
        vmt.send("/VMT/Input/Button", idx, VMT_THUMB_BUTTON, 0.0, 1 if m.thumb_open else 0)
        if VMT_SYSTEM_BUTTON is not None and m.system != m.sys_sent:
            vmt.send("/VMT/Input/Button", idx, VMT_SYSTEM_BUTTON, 0.0, 1 if m.system else 0)
            m.sys_sent = m.system
        vmt.send("/VMT/Input/Joystick/Click", idx, 1, 0.0, 1 if m.buttons["pinky"] else 0)
        vmt.send("/VMT/Input/Joystick", idx, 1, 0.0, m.joy[0], m.joy[1])
        vmt.send("/VMT/Input/Joystick/Touch", idx, 1, 0.0, 1 if m.joy != (0.0, 0.0) else 0)


# ---------------- Drawing ----------------
class _UI:
    FONT_HERSHEY_SIMPLEX = cv2.FONT_HERSHEY_SIMPLEX
    LINE_AA = cv2.LINE_AA
    s = 1.0

    def _p(self, p):
        return (int(p[0] * self.s), int(p[1] * self.s))

    def _t(self, th):
        return th if th < 0 else max(1, int(round(th * self.s)))

    def line(self, img, a, b, color, thickness=1):
        cv2.line(img, self._p(a), self._p(b), color, self._t(thickness))

    def circle(self, img, c, r, color, thickness=1):
        cv2.circle(img, self._p(c), max(1, int(r * self.s)), color, self._t(thickness))

    def putText(self, img, text, org, font, scale, color, thickness=1, line_type=cv2.LINE_AA):
        cv2.putText(img, text, self._p(org), font, scale * self.s, color, self._t(thickness), line_type)


UI = _UI()


def clip_px(v):
    return int(max(-5000, min(5000, v)))


def draw_joystick(frame, m, cx, cy, r=40):
    UI.circle(frame, (cx, cy), r, (200, 200, 200), 1)
    UI.circle(frame, (cx, cy), int(r * DEADZONE), (120, 120, 120), 1)
    px, py = int(cx + m.joy[0] * r), int(cy - m.joy[1] * r)
    active = m.joy_active
    UI.circle(frame, (px, py), 6, (0, 255, 255) if active else (110, 110, 110), -1)
    UI.putText(frame, f"{m.name[:3].upper()} {'ON' if active else 'OFF'}",
                (cx - 20, cy - r - 8), UI.FONT_HERSHEY_SIMPLEX, 0.45,
                (0, 255, 0) if active else (110, 110, 110), 1, UI.LINE_AA)
    UI.putText(frame, f"{m.joy[0]:+.2f} {m.joy[1]:+.2f}", (cx - 38, cy + r + 16),
                UI.FONT_HERSHEY_SIMPLEX, 0.45, (0, 255, 255), 1, UI.LINE_AA)


def main():
    global CONTROLLER_MODE, ROT_RAY_GAIN, ROTATION, MIRROR, SWAP_HANDS, INVERT_PALM, INVERT_HAND_ROT, ROLL_FLIP, TILT_FLIP, TILT_INVERT, TWIST_AXIS, MOMENTUM, INVERT_JOY_X, INVERT_JOY_Y, DEBUG_FINGERS, BLACK_PREVIEW, ARM_ASSIST, FINGER_DOWN
    global VR_ENABLED, VR_INV_X
    raise_priority()
    download_model()
    options = vision.HandLandmarkerOptions(
        base_options=mp_python.BaseOptions(model_asset_path=MODEL_PATH),
        running_mode=vision.RunningMode.VIDEO,
        num_hands=2,
        min_hand_detection_confidence=CONFIDENCE,
        min_hand_presence_confidence=TRACK_CONFIDENCE,
        min_tracking_confidence=TRACK_CONFIDENCE,
    )
    detector = vision.HandLandmarker.create_from_options(options)
    skin = SkinArm()

    cap = open_camera()
    if cap is None:
        if URL:
            print(f"Could not connect to {URL}. Open {URL.rsplit('/', 1)[0]} in the PC browser: "
                  "if it does not load, the phone and the PC are not on the same Wi-Fi, "
                  "the server is not started in the app, or a firewall/VPN is blocking it.")
        else:
            print("Could not open any camera. Check that Iriun is connected "
                  "and that no other program is using the camera.")
        return
    if URL is None:
        cap.set(cv2.CAP_PROP_FOURCC, cv2.VideoWriter_fourcc(*"MJPG"))
        cap.set(cv2.CAP_PROP_FPS, 60)
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, WIDTH)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, HEIGHT)
    cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
    cap = CameraReader(cap)

    hands = {"Left": Hand("Left"), "Right": Hand("Right")}
    ref_size = {"Left": None, "Right": None}
    vmt = VMT(VMT_IP, VMT_PORT)
    head = HeadPose()
    t0 = time.time()
    t_prev = t0
    fps = 0.0
    det_ms = 0.0
    peak = 0.0
    load_calib(ref_size, hands)
    load_angles()
    gc.collect()
    gc.freeze()
    gc.set_threshold(100000, 50, 50)   # fewer garbage-collector pauses (micro-stutters)

    print("Keys: C depth | X erases calibration | P inverts palm | I flips hand rotation in VR | O flips roll direction | L flips lean direction | G records a twist log | J viewing-angle correction | Y controller-hold mode | U momentum on/off | V inverts joystick X | "
          "D finger debug | , and . adjust sensitivity | F VR on/off | N inverts X in VR | B inverts joystick Y | K black preview | A arm assist | "
          "T straight-hand rotation calibration | 1-6 adjust angles, 7 picks the hand, 8 inverts the sideways rotation, 9 twist around its own axis | R rotates | M mirrors | H swaps left/right | Q quits")

    cv2.namedWindow("Hand test", cv2.WINDOW_NORMAL | cv2.WINDOW_KEEPRATIO)
    cv2.resizeWindow("Hand test", 480, 360)
    last_ok = time.time()
    last_warn = 0.0
    frame_i = 0
    shown = 0
    ui_s_cache = 1.0
    while True:
        ok, frame = cap.read()
        if not ok:
            now_w = time.time()
            if now_w - last_ok > 3.0 and now_w - last_warn > 3.0:
                print("No frames from the camera for a while. Check the app/server on the phone and the Wi-Fi.")
                last_warn = now_w
            continue
        last_ok = time.time()
        frame_i += 1
        w0 = frame.shape[1]   # original width (before rotating), for the VR calculation
        if ROTATION == 90:
            frame = cv2.rotate(frame, cv2.ROTATE_90_CLOCKWISE)
        elif ROTATION == 180:
            frame = cv2.rotate(frame, cv2.ROTATE_180)
        elif ROTATION == 270:
            frame = cv2.rotate(frame, cv2.ROTATE_90_COUNTERCLOCKWISE)
        if MIRROR:
            frame = cv2.flip(frame, 1)
        h, w = frame.shape[:2]

        t = time.time() - t0
        now = time.time()
        if now > t_prev:
            fps = fps * 0.9 + (1.0 / (now - t_prev)) * 0.1
            peak = max(now - t_prev, peak * 0.98)
        t_prev = now
        small = frame
        if w > DETECT_WIDTH:
            small = cv2.resize(frame, (DETECT_WIDTH, int(h * DETECT_WIDTH / w)),
                               interpolation=cv2.INTER_LINEAR)
        rgb = cv2.cvtColor(small, cv2.COLOR_BGR2RGB)
        img = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
        t_det = time.time()
        res = detector.detect_for_video(img, int(t * 1000))
        det_ms = det_ms * 0.9 + (time.time() - t_det) * 1000 * 0.1

        assigned = assign_hands(res, hands, w, h)
        for name, d in assigned.items():
            hands[name].update(d["lm"], t, w, h, d["wl"])
        for hd in hands.values():
            hd.rot_calib_tick(t)
        if any(hd.rot_saved for hd in hands.values()):
            for hd in hands.values():
                hd.rot_saved = False
            save_calib(ref_size, hands)
        sh_, sw_ = small.shape[:2]
        sx, sy = sw_ / w, sh_ / h
        ycc = None
        if ARM_ASSIST:
            # converts only when a hand is missing (arm search) or on the sparse learning frames
            learn_now = frame_i % 4 == 0
            if learn_now or len(assigned) < 2:
                ycc = cv2.cvtColor(small, cv2.COLOR_BGR2YCrCb)
                if learn_now:
                    skin.learn(ycc, hands, sx, sy)
        for name, m in hands.items():
            if name in assigned:
                continue
            est = None
            if ycc is not None and m.x is not None and m.size_vis and (m.est or (t - m.last_seen) >= ARM_TAKEOVER):
                pt = skin.find(ycc, (m.x * sw_, m.y * sh_), m.size_vis * sx)
                if pt is not None:
                    est = (max(-0.3, min(1.3, pt[0] / sw_)), max(-0.3, min(1.3, pt[1] / sh_)), m.size_vis)
            if est is not None:
                m.update_from_arm(t, est[0], est[1], est[2])
            elif m.pts is not None and (t - m.last_seen) < GRACE:
                m.visible = False
                m.in_grace = True      # holds gestures and joystick for a moment
                m.vx = m.vy = 0.0
            else:
                m.lost()

        if VR_ENABLED:
            f_px = (w0 / 2) / math.tan(math.radians(CAM_FOV_H) / 2)
            send_vr(vmt, hands, ref_size, w, h, f_px, head)

        if frame_i % SHOW_EVERY == 0:
            shown += 1
            if shown % 20 == 1:
                try:
                    _, _, rw, rh = cv2.getWindowImageRect("Hand test")
                    if rw > 0 and rh > 0:
                        ui_s_cache = max(1.0, min(UI_MAX_SCALE, min(rw / w, rh / h)))
                except (cv2.error, AttributeError):
                    pass
            ui_s = ui_s_cache
            UI.s = ui_s
            if BLACK_PREVIEW:
                frame = np.zeros((int(h * ui_s), int(w * ui_s), 3), dtype=np.uint8)
            elif ui_s > 1.0:
                frame = cv2.resize(frame, (int(w * ui_s), int(h * ui_s)), interpolation=cv2.INTER_LINEAR)
            # ---------- drawing ----------
            # hands are always drawn; the rest of the UI only with debug on
            for name, m in hands.items():
                base = (0, 255, 0) if name == "Right" else (255, 128, 0)
                color = base if m.visible else (0, 255, 255)
                if m.pts is not None:
                    px = [(int(x), int(y)) for x, y in m.pts]
                    for a, b in CONNECTIONS:
                        UI.line(frame, px[a], px[b], color, 2)
                    for p in px:
                        UI.circle(frame, p, 3, (255, 255, 255), -1)
                if m.x is not None:
                    UI.circle(frame, (clip_px(m.x * w), clip_px(m.y * h)), 10,
                               color if m.pts is not None else ((200, 0, 200) if m.est else (0, 0, 255)), 2)

            if DEBUG_FINGERS:
                for name, m in hands.items():
                    base = (0, 255, 0) if name == "Right" else (255, 128, 0)
                    depth = "-"
                    if m.size and ref_size[name]:
                        depth = f"{ref_size[name] / m.size:.2f}x"
                    state = "OK" if m.visible else ("ARM (estimated)" if m.est else ("HOLDING" if m.in_grace else "LOST (frozen)"))
                    if m.visible and m.bad_angle:
                        state = "BAD ANGLE (holding)"
                    gestures = []
                    if m.trigger: gestures.append("TRIGGER")
                    if m.pinch: gestures.append("PINCH")
                    if m.grip: gestures.append("GRIP")
                    if m.palm: gestures.append("PALM")
                    if m.joy_active: gestures.append("JOY")
                    if m.buttons["middle"]: gestures.append("MIDDLE")
                    if m.thumb_open: gestures.append("THUMB OPEN")
                    if m.buttons["pinky"]: gestures.append("PINKY")
                    if m.system: gestures.append("SYSTEM")
                    if m.upside_down: gestures.append("UPSIDE DOWN (buttons off)")
                    y = 20 if name == "Left" else 42
                    UI.putText(frame, f"{name}: {state} depth={depth} {' '.join(gestures)}", (8, y),
                                UI.FONT_HERSHEY_SIMPLEX, 0.5, base, 1, UI.LINE_AA)
                    if DEBUG_FINGERS and m.curl["index"] is not None:
                        profile = "palm" if m.palm else "back"
                        lim_d, lim_r = m.limits()
                        dbg = " ".join(f"{k[:3]}={v:.2f}" for k, v in m.curl.items() if v is not None)
                        if m.pinch_ratio is not None:
                            dbg += f" pinch={m.pinch_ratio:.2f} (on<{PINCH_ON:.2f})"
                        if m.thumb_ratio is not None:
                            dbg += f" thumb={m.thumb_ratio:.2f} (on>{THUMB_OPEN_ON:.2f})"
                        dbg += f" fan={m.fan_deg:.0f}deg (on>{FAN_MIN_DEG:.0f})"
                        if m.joy_active:
                            dbg += f" joy x={m.dbg_ang[0]:+.1f}cm y={m.dbg_ang[1]:+.1f}cm (full at {JOY_POS_MAX_CM:.1f})"
                        yb = 86 if name == "Left" else 118
                        UI.putText(frame, f"{name[:3]} {profile}: {dbg} (down<{lim_d:.2f} release>{lim_r:.2f})",
                                    (8, yb), UI.FONT_HERSHEY_SIMPLEX, 0.42, base, 1, UI.LINE_AA)
                        UI.putText(frame, f"{name[:3]} angle: spread={m.spread:.2f} (min {ANGLE_MIN_SPREAD}) "
                                           f"length={m.rel_length:.2f} (min {ANGLE_MIN_LENGTH})",
                                    (8, yb + 14), UI.FONT_HERSHEY_SIMPLEX, 0.42, base, 1, UI.LINE_AA)
                        if m.basis is not None:
                            fx, fy, fz = m.basis[2]
                            ux, uy, uz = m.basis[1]
                            UI.putText(frame, f"{name[:3]} rot: fwd=({fx:+.2f},{fy:+.2f},{fz:+.2f}) "
                                               f"up=({ux:+.2f},{uy:+.2f},{uz:+.2f}) "
                                               f"pinch={(m.pinch_ratio if m.pinch_ratio is not None else 0):.2f} "
                                               f"thumb={(m.thumb_ratio if m.thumb_ratio is not None else 0):.2f} "
                                               f"chi={m.chi if m.chi is not None else 0:+.0f} ray={getattr(m, 'ray_deg', 0.0):.0f}deg "
                                               f"{'HOLD' if getattr(m, 'rot_hold', False) else ''}",
                                        (8, 150 if name == "Left" else 164),
                                        UI.FONT_HERSHEY_SIMPLEX, 0.42, base, 1, UI.LINE_AA)

            cal = [hd for hd in hands.values() if hd.rot_acc is not None]
            if cal:
                c0 = cal[0]
                if t < c0.rot_start:
                    msg = f"GET READY: hand straight, back to the camera, fingers up ({c0.rot_start - t:.0f}s)"
                else:
                    msg = f"HOLD STILL ({max(0.0, c0.rot_until - t):.0f}s)"
                UI.putText(frame, msg, (8, 64), UI.FONT_HERSHEY_SIMPLEX, 0.5, (0, 0, 255), 1, UI.LINE_AA)

            if DEBUG_FINGERS:
                for name, cx in (("Left", 60), ("Right", w - 60)):
                    draw_joystick(frame, hands[name], cx, h - 70)

                UI.putText(frame, f"angle keys 1-6 adjust: {ANGLE_TARGET} (7 switches)",
                           (6, h - 56), UI.FONT_HERSHEY_SIMPLEX, 0.38, (200, 200, 120), 1, UI.LINE_AA)
                for row, n in enumerate(("Left", "Right")):
                    UI.putText(frame, f"{n}: 1/2 down {TILT_DOWN_DEG[n]:+.0f} | 3/4 lateral {HAND_TILT_DEG[n] * ANGLE_MIRROR['tilt'][n]:+.0f} | 5/6 roll {HAND_ROLL_DEG[n] * ANGLE_MIRROR['roll'][n]:+.0f}",
                               (6, h - 42 + row * 14), UI.FONT_HERSHEY_SIMPLEX, 0.38, (200, 200, 120), 1, UI.LINE_AA)
                UI.putText(frame, "VR: ON (VMT)" if VR_ENABLED else "VR: OFF (F key)", (w // 2 - 60, h - 12),
                            UI.FONT_HERSHEY_SIMPLEX, 0.45, (0, 255, 0) if VR_ENABLED else (110, 110, 110), 1, UI.LINE_AA)
                UI.putText(frame, f"{fps:.0f} fps | det {det_ms:.0f} ms | peak {peak * 1000:.0f} ms", (w - 290, 20),
                            UI.FONT_HERSHEY_SIMPLEX, 0.45, (255, 255, 255), 1, UI.LINE_AA)
            cv2.imshow("Hand test", frame)
        if REC["until"] > 0.0:
            for name, m in hands.items():
                if m.visible and m.rot_f is not None and getattr(m, "wl5", None) is not None and m.pts is not None:
                    ia = math.degrees(math.atan2(sum(m.pts[i][0] for i in (5, 9, 13, 17)) / 4.0 - m.pts[0][0],
                                                 m.pts[0][1] - sum(m.pts[i][1] for i in (5, 9, 13, 17)) / 4.0))
                    row = [t, name, *m.rot_f, *m.rot_u, m.spread, m.rel_length, int(m.bad_angle), int(bool(m.palm)),
                           getattr(m, "twist_deg", 0.0), ia]
                    for q in m.wl5:
                        row.extend(q)
                    for i in (0, 5, 9, 13, 17):
                        row.extend((m.pts[i][0] / w, m.pts[i][1] / h))
                    REC["rows"].append(row)
            if t >= REC["until"]:
                REC["until"] = 0.0
                head = ["t", "hand", "fx", "fy", "fz", "ux", "uy", "uz", "spread", "rel_length", "bad", "palm",
                        "twist_deg", "img_angle_deg"]
                for i in (0, 5, 9, 13, 17):
                    head.extend((f"w{i}x", f"w{i}y", f"w{i}z"))
                for i in (0, 5, 9, 13, 17):
                    head.extend((f"p{i}x", f"p{i}y"))
                with open(TWIST_LOG_FILE, "w", encoding="utf-8") as fh:
                    fh.write(",".join(head) + "\n")
                    for row in REC["rows"]:
                        fh.write(",".join(f"{v:.4f}" if isinstance(v, float) else str(v) for v in row) + "\n")
                print(f"Twist log saved: {TWIST_LOG_FILE} ({len(REC['rows'])} rows)")
                REC["rows"] = []
        k = cv2.waitKey(1) & 0xFF
        if k == ord("q"):
            break
        elif k == ord("c"):
            for name, m in hands.items():
                if m.pts is not None:
                    ref_size[name] = m.size
            print("Calibrated:", ref_size)
            save_calib(ref_size, hands)
        elif k == ord("x"):
            for n in ref_size:
                ref_size[n] = None
            for hd in hands.values():
                hd.rot_fix = np.eye(3)
            for f_path in (CALIB_FILE, OLD_CALIB_FILE):
                if os.path.exists(f_path):
                    os.remove(f_path)
            print("Calibration erased. Press C to redo the depth calibration.")
        elif k == ord("p"):
            INVERT_PALM = not INVERT_PALM
            print("Palm detection inverted:", INVERT_PALM)
        elif k == ord("i"):
            INVERT_HAND_ROT = not INVERT_HAND_ROT
            for hd in hands.values():
                hd.rot_f = hd.rot_u = None
                hd.rot_R = None   # resets smoothing so it flips right away
            print("Hand rotation in VR inverted:", INVERT_HAND_ROT)
        elif k == ord("y"):
            CONTROLLER_MODE = not CONTROLLER_MODE
            for hd in hands.values():
                hd.pinch = False
                hd.thumb_open = False
                hd.down = {d[0]: False for d in FINGERS}
                hd.curl = {d[0]: None for d in FINGERS}
            print("Controller-hold mode:", CONTROLLER_MODE,
                  "(thumb up, back of the fingers to the camera; pinch and thumb-open off, fingers need a real fist)")
        elif k == ord("j"):
            ROT_RAY_GAIN = {1.0: 0.5, 0.5: 0.0}.get(ROT_RAY_GAIN, 1.0)
            for hd in hands.values():
                hd.rot_R = None
            print("Viewing-angle correction of the rotation:", ROT_RAY_GAIN, "(1.0 full, 0.5 half, 0 off)")
        elif k == ord("u"):
            MOMENTUM = not MOMENTUM
            print("Momentum when the hand leaves the image:", MOMENTUM)
        elif k == ord("l"):
            TILT_FLIP = not TILT_FLIP
            print("Hand lean direction flipped:", TILT_FLIP)
        elif k == ord("o"):
            ROLL_FLIP = not ROLL_FLIP
            print("Hand roll direction flipped:", ROLL_FLIP)
        elif k == ord("v"):
            INVERT_JOY_X = not INVERT_JOY_X
            print("Joystick X inverted:", INVERT_JOY_X)
        elif k == ord("k"):
            BLACK_PREVIEW = not BLACK_PREVIEW
        elif k == ord("a"):
            ARM_ASSIST = not ARM_ASSIST
            print("Arm assist:", ARM_ASSIST)
        elif k == ord("b"):
            INVERT_JOY_Y = not INVERT_JOY_Y
            print("Joystick Y inverted:", INVERT_JOY_Y)
        elif k == ord("d"):
            DEBUG_FINGERS = not DEBUG_FINGERS
        elif k in (ord(","), ord(".")):
            # "," = less sensitive (bend more), "." = more sensitive (bend less)
            FINGER_DOWN = round(min(1.6, max(0.8, FINGER_DOWN + (0.05 if k == ord(".") else -0.05))), 2)
            print(f"Finger sensitivity: palm facing goes down with ratio < {FINGER_DOWN:.2f}, "
                  f"back of the hand < {max(FINGER_DOWN - BACK_STRICTER, 0.5):.2f} (releases {HYSTERESIS:.2f} above)")
        elif k == ord("g"):
            REC["rows"] = []
            REC["until"] = t + TWIST_LOG_SECS
            print(f"Recording {TWIST_LOG_SECS:.0f} s: fingers up, twist the forearm like a doorknob "
                  f"(palm to camera, palm sideways, back) a few times, slowly")
        elif k == ord("7"):
            cycle_angle_target()
        elif k == ord("9"):
            TWIST_AXIS = {2: 1, 1: 0, 0: -1, -1: 2}[TWIST_AXIS]
            print("Forearm twist keeps this still while the controller turns:", TWIST_AXIS_NAMES[TWIST_AXIS])
        elif k == ord("8"):
            TILT_INVERT = not TILT_INVERT
            print("Sideways rotation (tilt and turn) of the hand inverted in VR:", TILT_INVERT)
        elif k in (ord("1"), ord("2")):
            adjust_angle("down", -5.0 if k == ord("1") else 5.0)     # 1 = less tilted toward the floor, 2 = more
        elif k in (ord("3"), ord("4")):
            adjust_angle("tilt", -5.0 if k == ord("3") else 5.0)     # lateral lean
        elif k in (ord("5"), ord("6")):
            adjust_angle("roll", -5.0 if k == ord("5") else 5.0)     # twist around the fingers
        elif k == ord("f"):
            VR_ENABLED = not VR_ENABLED
            if not VR_ENABLED:
                vmt.shutdown()
            print("VR:", "on" if VR_ENABLED else "off")
        elif k == ord("t"):
            for m in hands.values():
                m.start_rot_calib(t)
            print(f"Get into the pose (back to the camera, fingers up) - you have {ROT_CALIB_WAIT:.0f}s, "
                  f"then hold still for {ROT_CALIB_SECS:.0f}s...")
        elif k == ord("n"):
            VR_INV_X = not VR_INV_X
            print("X position inverted in VR:", VR_INV_X)
        elif k == ord("r"):
            ROTATION = {None: 90, 90: 180, 180: 270, 270: None}[ROTATION]
        elif k == ord("m"):
            MIRROR = not MIRROR
        elif k == ord("h"):
            SWAP_HANDS = not SWAP_HANDS

    vmt.shutdown()
    cap.release()
    cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
