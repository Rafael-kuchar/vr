#!/usr/bin/env python3
"""
relay.py  -  PC side of the phone app.

  phone (ARCore head pose, UDP :6000)  ->  OpenTrack UDP 127.0.0.1:4242   (VRto3D head tracking, rotation)
                                       ->  VMT OSC 127.0.0.1:39570        (a "head tracker" with FULL 6DOF)
  (optional) --stream: starts ffmpeg to send the screen to the phone (UDP :5000)

Keys in this window:  r = recenter (looking straight ahead, now)    q = quit
No pip packages needed (only the standard library).
"""
import argparse, math, socket, struct, subprocess, sys, threading, time

MAGIC = b"VRH1"
PKT = struct.Struct("<4si7fi")      # magic, seq, x y z qx qy qz qw, state (1 = tracking)

# ---------- small quaternion helpers (x, y, z, w) ----------
def q_mul(a, b):
    ax, ay, az, aw = a; bx, by, bz, bw = b
    return (aw*bx + ax*bw + ay*bz - az*by,
            aw*by - ax*bz + ay*bw + az*bx,
            aw*bz + ax*by - ay*bx + az*bw,
            aw*bw - ax*bx - ay*by - az*bz)

def q_conj(q):
    return (-q[0], -q[1], -q[2], q[3])

def q_norm(q):
    n = math.sqrt(sum(c*c for c in q)) or 1.0
    return tuple(c / n for c in q)

def q_rot(q, v):
    """rotates vector v by quaternion q"""
    p = q_mul(q_mul(q, (v[0], v[1], v[2], 0.0)), q_conj(q))
    return (p[0], p[1], p[2])

def yaw_quat(q):
    """the rotation around the vertical axis only (where the head looks, ignoring pitch/roll)"""
    f = q_rot(q, (0.0, 0.0, -1.0))
    yaw = math.atan2(-f[0], -f[2])           # positive = turning left (right-handed, y up)
    h = yaw / 2.0
    return (0.0, math.sin(h), 0.0, math.cos(h))

def euler_deg(q):
    """yaw (left +), pitch (up +), roll (tilt to the right +) in degrees"""
    f = q_rot(q, (0.0, 0.0, -1.0))
    r = q_rot(q, (1.0, 0.0, 0.0))
    u = q_rot(q, (0.0, 1.0, 0.0))
    yaw = math.degrees(math.atan2(-f[0], -f[2]))
    pitch = math.degrees(math.asin(max(-1.0, min(1.0, f[1]))))
    roll = math.degrees(math.atan2(-r[1], u[1]))
    return yaw, pitch, roll

# ---------- OSC (same format hands.py uses for VMT) ----------
def _pad(b):
    return b + b"\x00" * (-len(b) % 4)

def osc(address, *args):
    types, data = ",", b""
    for a in args:
        if isinstance(a, int):
            types += "i"; data += struct.pack(">i", a)
        else:
            types += "f"; data += struct.pack(">f", float(a))
    return _pad(address.encode() + b"\x00") + _pad(types.encode() + b"\x00") + data

# ---------- ffmpeg: screen -> phone ----------
def start_ffmpeg(args, phone_ip):
    enc = args.encoder
    if enc == "libx264":
        enc_opts = ["-preset", "ultrafast", "-tune", "zerolatency"]
    elif enc == "h264_nvenc":
        enc_opts = ["-preset", "p1", "-tune", "ll", "-rc", "cbr"]
    else:
        enc_opts = []
    grab = ["-f", "gdigrab", "-framerate", str(args.fps), "-draw_mouse", "0"]
    if args.capture:
        x, y, w, h = [int(v) for v in args.capture.split(",")]
        grab += ["-offset_x", str(x), "-offset_y", str(y), "-video_size", f"{w}x{h}"]
    grab += ["-i", "desktop"]
    cmd = ([args.ffmpeg, "-hide_banner", "-loglevel", "warning"] + grab +
           ["-vf", f"scale={args.size.replace('x', ':')}", "-pix_fmt", "yuv420p",
            "-c:v", enc] + enc_opts +
           ["-b:v", args.bitrate, "-maxrate", args.bitrate, "-bufsize", "1M", "-g", str(args.fps), "-bf", "0",
            "-f", "mpegts", f"udp://{phone_ip}:{args.video_port}?pkt_size=1316"])
    print("\nStarting ffmpeg:", " ".join(cmd), "\n")
    try:
        return subprocess.Popen(cmd)
    except FileNotFoundError:
        print("!! ffmpeg not found. Install it (https://www.gyan.dev/ffmpeg/builds/) or use --ffmpeg C:\\path\\ffmpeg.exe")
        return None

# ---------- keyboard ----------
def key_loop(state):
    try:
        import msvcrt
        while not state["quit"]:
            if msvcrt.kbhit():
                handle_key(state, msvcrt.getwch())
            time.sleep(0.05)
    except ImportError:
        while not state["quit"]:
            try:
                line = sys.stdin.readline()
            except Exception:
                return
            if not line:
                return
            handle_key(state, line.strip()[:1])

def handle_key(state, ch):
    ch = ch.lower()
    if ch == "r":
        state["recenter"] = True
    elif ch == "q":
        state["quit"] = True

# ---------- main ----------
def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=6000, help="UDP port the phone sends the pose to")
    ap.add_argument("--opentrack", default="127.0.0.1:4242", help="OpenTrack UDP target (VRto3D). 'off' to disable")
    ap.add_argument("--vmt", default="127.0.0.1:39570", help="VMT OSC target. 'off' to disable")
    ap.add_argument("--vmt-index", type=int, default=2, help="VMT tracker slot (hands.py uses 0 and 1)")
    ap.add_argument("--height", type=float, default=1.6, help="head height in meters for the VMT tracker")
    ap.add_argument("--invert", default="", help="comma list of axes to flip: x,y,z,yaw,pitch,roll (e.g. --invert yaw)")
    ap.add_argument("--stream", action="store_true", help="start ffmpeg to stream the screen to the phone")
    ap.add_argument("--ffmpeg", default="ffmpeg")
    ap.add_argument("--encoder", default="libx264", help="libx264 (CPU) | h264_nvenc (NVIDIA) | h264_amf (AMD) | h264_qsv (Intel)")
    ap.add_argument("--fps", type=int, default=60)
    ap.add_argument("--size", default="1280x720", help="video size sent to the phone (side-by-side, both eyes)")
    ap.add_argument("--bitrate", default="8M")
    ap.add_argument("--capture", default="", help="x,y,w,h of the screen area to capture (default: whole primary screen)")
    ap.add_argument("--video-port", type=int, default=5000)
    args = ap.parse_args()

    flip = set(s.strip() for s in args.invert.split(",") if s.strip())
    sgn = lambda name: -1.0 if name in flip else 1.0

    def addr(s):
        if s.lower() == "off":
            return None
        h, p = s.rsplit(":", 1)
        return (h, int(p))
    ot_addr, vmt_addr = addr(args.opentrack), addr(args.vmt)

    rx = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    rx.bind(("0.0.0.0", args.port))
    rx.settimeout(0.25)
    tx = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

    state = {"recenter": True, "quit": False}
    threading.Thread(target=key_loop, args=(state,), daemon=True).start()

    origin_p = (0.0, 0.0, 0.0)
    origin_q = (0.0, 0.0, 0.0, 1.0)
    phone_ip, ffmpeg_proc = None, None
    n_pkts, t_stat, last_pose, last_rx, last_seq = 0, time.time(), None, 0.0, None
    lost = 0

    print(f"Listening for the phone on UDP :{args.port}   |   keys: r = recenter, q = quit")
    print(f"OpenTrack -> {args.opentrack}   VMT -> {args.vmt} (tracker slot {args.vmt_index})\n")
    try:
        while not state["quit"]:
            try:
                data, src = rx.recvfrom(256)
            except socket.timeout:
                data = None
            now = time.time()
            if data is not None and len(data) >= PKT.size:
                magic, seq, x, y, z, qx, qy, qz, qw, tracking = PKT.unpack_from(data)
                if magic == MAGIC:
                    if phone_ip != src[0]:
                        phone_ip = src[0]
                        print(f"\nPhone found: {phone_ip}")
                        print(f"  hands.py camera:  python hands.py http://{phone_ip}:8080/video\n")
                        if args.stream and ffmpeg_proc is None:
                            ffmpeg_proc = start_ffmpeg(args, phone_ip)
                    if last_seq is not None and seq > last_seq + 1:
                        lost += seq - last_seq - 1
                    last_seq = seq
                    n_pkts += 1
                    last_rx = now
                    q = q_norm((qx, qy, qz, qw))
                    p = (x, y, z)
                    if tracking != 1:
                        last_pose = None
                    else:
                        if state["recenter"]:
                            origin_p, origin_q = p, yaw_quat(q)
                            state["recenter"] = False
                            print("\nRecentered.")
                        inv = q_conj(origin_q)
                        rp = q_rot(inv, (p[0] - origin_p[0], p[1] - origin_p[1], p[2] - origin_p[2]))
                        rq = q_norm(q_mul(inv, q))
                        last_pose = (rp, rq)
                        yaw, pitch, roll = euler_deg(rq)
                        if ot_addr:
                            tx.sendto(struct.pack("<6d", sgn("x") * rp[0] * 100.0, sgn("y") * rp[1] * 100.0,
                                                  sgn("z") * rp[2] * 100.0, sgn("yaw") * yaw,
                                                  sgn("pitch") * pitch, sgn("roll") * roll), ot_addr)
                        if vmt_addr:
                            tx.sendto(osc("/VMT/Room/Driver", args.vmt_index, 1, 0.0,
                                          rp[0], rp[1] + args.height, rp[2],
                                          rq[0], rq[1], rq[2], rq[3]), vmt_addr)
            if now - t_stat >= 1.0:
                rate = n_pkts / (now - t_stat)
                if last_pose and now - last_rx < 1.0:
                    rp, rq = last_pose
                    yaw, pitch, roll = euler_deg(rq)
                    msg = (f"{rate:4.0f} pkt/s  lost {lost}  pos {rp[0]*100:+6.1f} {rp[1]*100:+6.1f} {rp[2]*100:+6.1f} cm"
                           f"  yaw {yaw:+6.1f} pitch {pitch:+6.1f} roll {roll:+6.1f}")
                elif now - last_rx < 1.0:
                    msg = "phone connected, but ARCore is not tracking yet (move the phone slowly, look at a textured surface)"
                else:
                    msg = "waiting for the phone..."
                print("\r" + msg.ljust(110), end="", flush=True)
                n_pkts, t_stat = 0, now
    except KeyboardInterrupt:
        pass
    finally:
        if vmt_addr:      # turn the VMT head tracker off
            tx.sendto(osc("/VMT/Room/Driver", args.vmt_index, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0), vmt_addr)
        if ffmpeg_proc:
            ffmpeg_proc.terminate()
        print("\nBye.")

if __name__ == "__main__":
    main()
