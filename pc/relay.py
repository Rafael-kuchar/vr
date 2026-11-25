#!/usr/bin/env python3
import argparse, json, math, os, re, shutil, signal, socket, struct, subprocess, sys, threading, time

MAGIC = b"VRH1"
PKT = struct.Struct("<4si7fi")
CHUNK = 1316
ABOVE_NORMAL = 0x00008000


def q_mul(a, b):
    ax, ay, az, aw = a
    bx, by, bz, bw = b
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
    p = q_mul(q_mul(q, (v[0], v[1], v[2], 0.0)), q_conj(q))
    return (p[0], p[1], p[2])


def q_log(q):
    x, y, z, w = q
    if w < 0:
        x, y, z, w = -x, -y, -z, -w
    s = math.sqrt(x*x + y*y + z*z)
    if s < 1e-9:
        return (0.0, 0.0, 0.0)
    k = 2.0 * math.atan2(s, w) / s
    return (x*k, y*k, z*k)


def q_exp(w, dt):
    n = math.sqrt(w[0]*w[0] + w[1]*w[1] + w[2]*w[2])
    half = n * dt / 2.0
    if n < 1e-9 or half < 1e-9:
        return (0.0, 0.0, 0.0, 1.0)
    s = math.sin(half) / n
    return (w[0]*s, w[1]*s, w[2]*s, math.cos(half))


def yaw_quat(q):
    f = q_rot(q, (0.0, 0.0, -1.0))
    yaw = math.atan2(-f[0], -f[2])
    h = yaw / 2.0
    return (0.0, math.sin(h), 0.0, math.cos(h))


def euler_deg(q):
    f = q_rot(q, (0.0, 0.0, -1.0))
    r = q_rot(q, (1.0, 0.0, 0.0))
    u = q_rot(q, (0.0, 1.0, 0.0))
    yaw = math.degrees(math.atan2(-f[0], -f[2]))
    pitch = math.degrees(math.asin(max(-1.0, min(1.0, f[1]))))
    roll = math.degrees(math.atan2(-r[1], u[1]))
    return yaw, pitch, roll


def _pad(b):
    return b + b"\x00" * (-len(b) % 4)


def osc(address, *args):
    types, data = ",", b""
    for a in args:
        if isinstance(a, int):
            types += "i"
            data += struct.pack(">i", a)
        else:
            types += "f"
            data += struct.pack(">f", float(a))
    return _pad(address.encode() + b"\x00") + _pad(types.encode() + b"\x00") + data


class PoseState:
    def __init__(self):
        self.lock = threading.Lock()
        self.valid = False
        self.t = 0.0
        self.p = (0.0, 0.0, 0.0)
        self.q = (0.0, 0.0, 0.0, 1.0)
        self.v = (0.0, 0.0, 0.0)
        self.w = (0.0, 0.0, 0.0)

    def clear(self):
        with self.lock:
            self.valid = False

    def update(self, t, p, q):
        with self.lock:
            if self.valid:
                if q[0]*self.q[0] + q[1]*self.q[1] + q[2]*self.q[2] + q[3]*self.q[3] < 0:
                    q = (-q[0], -q[1], -q[2], -q[3])
                dt = t - self.t
                if 0.004 <= dt <= 0.15:
                    inv = 1.0 / dt
                    nv = ((p[0] - self.p[0]) * inv, (p[1] - self.p[1]) * inv, (p[2] - self.p[2]) * inv)
                    lw = q_log(q_mul(q_conj(self.q), q))
                    nw = (lw[0] * inv, lw[1] * inv, lw[2] * inv)
                    a = 0.6
                    self.v = tuple(self.v[i] * (1 - a) + nv[i] * a for i in range(3))
                    self.w = tuple(self.w[i] * (1 - a) + nw[i] * a for i in range(3))
                elif dt > 0.15:
                    self.v = (0.0, 0.0, 0.0)
                    self.w = (0.0, 0.0, 0.0)
            else:
                self.v = (0.0, 0.0, 0.0)
                self.w = (0.0, 0.0, 0.0)
            self.t, self.p, self.q, self.valid = t, p, q, True

    def predict(self, now, ahead, limit):
        with self.lock:
            if not self.valid:
                return None
            age = now - self.t
            if age > 0.5:
                return None
            tau = max(0.0, min(age + ahead, limit))
            p = (self.p[0] + self.v[0]*tau, self.p[1] + self.v[1]*tau, self.p[2] + self.v[2]*tau)
            q = q_norm(q_mul(self.q, q_exp(self.w, tau)))
            return p, q


def raise_priority():
    if os.name != "nt":
        return
    try:
        import ctypes
        ctypes.windll.winmm.timeBeginPeriod(1)
        ctypes.windll.kernel32.SetPriorityClass(ctypes.windll.kernel32.GetCurrentProcess(), 0x00000080)
    except Exception:
        pass


def monitor_count():
    try:
        import ctypes
        return int(ctypes.windll.user32.GetSystemMetrics(80))
    except Exception:
        return 1


def parse_rate(text):
    t = str(text).strip().lower()
    mult = 1
    if t.endswith("m"):
        mult, t = 1_000_000, t[:-1]
    elif t.endswith("k"):
        mult, t = 1_000, t[:-1]
    try:
        return int(float(t) * mult)
    except ValueError:
        return 8_000_000


def probe(cmd, timeout=20):
    try:
        r = subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=timeout)
        return r.returncode == 0
    except Exception:
        return False


def ffmpeg_list(ffmpeg, flag):
    try:
        r = subprocess.run([ffmpeg, "-hide_banner", flag], capture_output=True, text=True, timeout=15)
        return r.stdout
    except Exception:
        return None


ENCODERS = [
    ("h264_nvenc", [["-preset", "p1", "-tune", "ll", "-rc", "cbr", "-zerolatency", "1", "-rc-lookahead", "0", "-forced-idr", "1"],
                    ["-preset", "llhp", "-rc", "cbr", "-zerolatency", "1"],
                    []]),
    ("h264_amf", [["-usage", "ultralowlatency", "-quality", "speed", "-rc", "cbr"], []]),
    ("h264_qsv", [["-preset", "veryfast", "-look_ahead", "0"], []]),
    ("libx264", [["-preset", "ultrafast", "-tune", "zerolatency"]]),
]


def pick_encoder(args):
    listing = ffmpeg_list(args.ffmpeg, "-encoders")
    if listing is None:
        print("!! ffmpeg nao encontrado. Instale (https://www.gyan.dev/ffmpeg/builds/) ou use --ffmpeg C:\\caminho\\ffmpeg.exe")
        return None
    wanted = ENCODERS if args.encoder == "auto" else [e for e in ENCODERS if e[0] == args.encoder] or [(args.encoder, [[]])]
    print("Procurando o melhor encoder de video...")
    for name, variants in wanted:
        if name not in listing:
            continue
        for opts in variants:
            cmd = [args.ffmpeg, "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "color=c=black:size=1280x720:rate=30",
                   "-frames:v", "8", "-pix_fmt", "yuv420p", "-c:v", name] + opts + ["-f", "null", "-"]
            if probe(cmd):
                return name, opts
    print("!! Nenhum encoder funcionou.")
    return None


def build_cmd(args, plan, target):
    w, h = plan["w"], plan["h"]
    fps = plan["fps"]
    bitrate = plan["bitrate"]
    bufsize = max(100_000, int(bitrate / fps * 2))
    if args.test_video:
        inp = ["-re", "-f", "lavfi", "-i", f"testsrc2=size={w}x{h}:rate={fps}"]
        vf = "format=yuv420p"
    elif plan["grab"] == "ddagrab":
        src = f"ddagrab=output_idx={args.monitor}:framerate={fps}:draw_mouse=0"
        if plan["capture"]:
            x, y, cw, ch = plan["capture"]
            src += f":offset_x={x}:offset_y={y}:video_size={cw}x{ch}"
        inp = ["-f", "lavfi", "-i", src]
        vf = f"hwdownload,format=bgra,scale={w}:{h}:flags=fast_bilinear,format=yuv420p"
    else:
        inp = ["-f", "gdigrab", "-framerate", str(fps), "-draw_mouse", "0"]
        if plan["capture"]:
            x, y, cw, ch = plan["capture"]
            inp += ["-offset_x", str(x), "-offset_y", str(y), "-video_size", f"{cw}x{ch}"]
        inp += ["-i", "desktop"]
        vf = f"scale={w}:{h}:flags=fast_bilinear,format=yuv420p"
    cmd = [args.ffmpeg, "-hide_banner", "-loglevel", "warning"] + inp + ["-vf", vf, "-c:v", plan["enc"]] + plan["opts"]
    cmd += ["-b:v", str(bitrate), "-maxrate", str(bitrate), "-bufsize", str(bufsize), "-g", str(fps), "-bf", "0",
            "-bsf:v", "h264_metadata=aud=insert"]
    if target is None:
        return cmd + ["-frames:v", "6", "-f", "null", "-"]
    return cmd + ["-f", "h264", "-flush_packets", "1", target]


def make_plan(args):
    chosen = pick_encoder(args)
    if chosen is None:
        return None
    enc, opts = chosen
    cpu = enc == "libx264"
    size = args.size or ("960x540" if cpu else "1280x720")
    fps = args.fps or 60
    bitrate = parse_rate(args.bitrate or ("5M" if cpu else "10M"))
    w, h = [int(v) for v in size.lower().split("x")]
    capture = None
    if not args.test_video and args.capture:
        capture = tuple(int(v) for v in args.capture.split(","))
    plan = {"enc": enc, "opts": opts, "w": w, "h": h, "fps": fps, "bitrate": bitrate, "capture": capture, "grab": "gdigrab"}
    if not args.test_video and os.name == "nt":
        want = args.grab
        if want == "auto":
            ok = "ddagrab" in (ffmpeg_list(args.ffmpeg, "-filters") or "") and monitor_count() == 1
        else:
            ok = want == "ddagrab"
        if ok:
            plan["grab"] = "ddagrab"
            if not probe(build_cmd(args, plan, None)):
                plan["grab"] = "gdigrab"
                if want == "ddagrab":
                    print("!! ddagrab nao funcionou, usando gdigrab.")
    print(f"Video: encoder {enc}, captura {'teste' if args.test_video else plan['grab']}, {w}x{h} a {fps} fps, {bitrate/1e6:.1f} Mbps")
    if capture:
        print(f"Capturando a area x,y,w,h = {capture} (janela do headset do SteamVR)")
    return plan


def start_process(cmd):
    return subprocess.Popen(cmd, creationflags=ABOVE_NORMAL if os.name == "nt" else 0)


def video_worker(args, plan, st):
    fails = 0
    while not st["quit"]:
        ip = st["phone_ip"]
        cmd = build_cmd(args, plan, f"udp://{ip}:{args.video_port}?pkt_size={CHUNK}")
        print("\nIniciando ffmpeg:", " ".join(cmd), "\n")
        t0 = time.time()
        try:
            p = start_process(cmd)
        except FileNotFoundError:
            print("!! ffmpeg nao encontrado.")
            return
        st["ffmpeg"] = p
        while p.poll() is None and not st["quit"] and st["phone_ip"] == ip:
            time.sleep(0.3)
        if p.poll() is None:
            p.terminate()
            try:
                p.wait(timeout=3)
            except Exception:
                p.kill()
        if st["quit"]:
            break
        if st["phone_ip"] != ip:
            fails = 0
            continue
        fails = fails + 1 if time.time() - t0 < 4 else 0
        print(f"\nffmpeg parou (codigo {p.returncode}).")
        if fails >= 3:
            print("!! ffmpeg falhou 3 vezes seguidas, desisti. Veja a mensagem de erro acima.")
            return
        time.sleep(1.5)


def key_loop(st):
    try:
        import msvcrt
        while not st["quit"]:
            if msvcrt.kbhit():
                handle_key(st, msvcrt.getwch())
            time.sleep(0.05)
    except ImportError:
        while not st["quit"]:
            try:
                line = sys.stdin.readline()
            except Exception:
                return
            if not line:
                return
            handle_key(st, line.strip()[:1])


def handle_key(st, ch):
    ch = ch.lower()
    if ch == "r":
        st["recenter"] = True
    elif ch == "q":
        st["quit"] = True


def find_vrsettings(custom=""):
    if custom:
        return custom
    cands = []
    try:
        import winreg
        for root, sub in ((winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam"),
                          (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\WOW6432Node\Valve\Steam")):
            try:
                with winreg.OpenKey(root, sub) as k:
                    cands.append(os.path.join(winreg.QueryValueEx(k, "SteamPath" if root == winreg.HKEY_CURRENT_USER else "InstallPath")[0],
                                              "config", "steamvr.vrsettings"))
            except OSError:
                pass
    except ImportError:
        pass
    cands += [r"C:\Program Files (x86)\Steam\config\steamvr.vrsettings", r"C:\Program Files\Steam\config\steamvr.vrsettings"]
    for c in cands:
        if os.path.isfile(c):
            return c
    return ""


def steamvr_running():
    try:
        out = subprocess.run(["tasklist"], capture_output=True, text=True).stdout.lower()
    except Exception:
        return False
    return any(n in out for n in ("vrserver.exe", "vrmonitor.exe", "vrcompositor.exe"))


def steam_root_from_vrsettings(path):
    if not path:
        return ""
    return os.path.dirname(os.path.dirname(os.path.abspath(path)))


def vrto3d_driver_installed(vrsettings_path):
    steam_root = steam_root_from_vrsettings(vrsettings_path)
    if not steam_root:
        return False
    libraries = [steam_root]
    vdf = os.path.join(steam_root, "steamapps", "libraryfolders.vdf")
    try:
        with open(vdf, encoding="utf-8") as f:
            for candidate in re.findall(r'"path"\s+"([^"]+)"', f.read()):
                candidate = candidate.replace("\\\\", "\\")
                if candidate not in libraries:
                    libraries.append(candidate)
    except OSError:
        pass
    for library in libraries:
        manifest = os.path.join(library, "steamapps", "common", "SteamVR", "drivers",
                                "vrto3d", "driver.vrdrivermanifest")
        if os.path.isfile(manifest):
            return True
    return False


def vrto3d_config_path(vrsettings_path):
    steam_root = steam_root_from_vrsettings(vrsettings_path)
    candidates = []
    if steam_root:
        candidates.append(os.path.join(steam_root, "config", "vrto3d", "default_config.json"))
    userprofile = os.environ.get("USERPROFILE", "")
    if userprofile:
        candidates.append(os.path.join(userprofile, "Documents", "My Games", "vrto3d", "default_config.json"))
    return next((p for p in candidates if os.path.isfile(p)), candidates[0] if candidates else "")


def configure_vrto3d(path, undo=False):
    cfg_path = vrto3d_config_path(path)
    if not cfg_path:
        print("!! Nao encontrei a pasta de configuracao do VRto3D.")
        return False
    backup = cfg_path + ".bak_relay"
    if undo:
        if os.path.isfile(backup):
            shutil.copy2(backup, cfg_path)
            print(f"Configuracao do VRto3D restaurada. Backup: {backup}")
        elif os.path.isfile(cfg_path):
            os.remove(cfg_path)
        return True
    try:
        if os.path.isfile(cfg_path):
            with open(cfg_path, encoding="utf-8") as f:
                cfg = json.load(f)
        else:
            cfg = {}
    except Exception as e:
        print(f"!! Nao consegui ler a configuracao do VRto3D: {e}")
        return False
    if not os.path.exists(backup) and os.path.isfile(cfg_path):
        shutil.copy2(cfg_path, backup)
    cfg["output_mode"] = "SbS"
    cfg["use_open_track"] = True
    cfg["open_track_port"] = 4242
    os.makedirs(os.path.dirname(cfg_path), exist_ok=True)
    try:
        with open(cfg_path, "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=2)
    except Exception as e:
        print(f"!! Nao consegui salvar a configuracao do VRto3D: {e}")
        return False
    print("VRto3D configurado: saida SbS + OpenTrack 6DOF na porta 4242.")
    return True


def head_is_setup(index, custom_path=""):
    path = find_vrsettings(custom_path)
    if not path:
        return False
    try:
        if not vrto3d_driver_installed(path):
            return False
        with open(path, encoding="utf-8") as f:
            cfg = json.load(f)
        sv = cfg.get("steamvr") or {}
        if sv.get("forcedDriver") != "vrto3d" or not sv.get("requireHmd", False):
            return False
        vcfg = vrto3d_config_path(path)
        with open(vcfg, encoding="utf-8") as f:
            v3d = json.load(f)
        return (v3d.get("use_open_track") is True and
                int(v3d.get("open_track_port", 0)) == 4242 and
                v3d.get("output_mode", "SbS") == "SbS")
    except Exception:
        return False


def start_steamvr():
    if steamvr_running():
        return
    try:
        os.startfile("steam://rungameid/250820")
        print("Abrindo o SteamVR...")
    except Exception:
        print("Nao consegui abrir o SteamVR sozinho: abra manualmente.")


def find_hands(custom=""):
    if custom:
        return custom if os.path.isfile(custom) else ""
    here = os.path.dirname(os.path.abspath(__file__))
    cands = [os.path.join(here, f) for f in os.listdir(here) if f.lower().startswith("hands") and f.lower().endswith(".py")]
    return max(cands, key=os.path.getmtime) if cands else ""


STEAMVR_KEYS = {"activateMultipleDrivers": True, "forcedDriver": "vrto3d", "requireHmd": True}


def setup_head(index, undo, custom_path="", chained=False):
    path = find_vrsettings(custom_path)
    if not path:
        print('!! Nao achei o steamvr.vrsettings. Use --vrsettings "C:\\...\\Steam\\config\\steamvr.vrsettings"')
        return False
    if steamvr_running():
        print("!! O SteamVR esta aberto. Feche ele por completo (inclusive a janela SteamVR Status) e rode de novo.")
        return False
    bak = path + ".bak_relay"
    if undo:
        if not os.path.isfile(bak):
            print("!! Nao encontrei o backup .bak_relay; nada foi restaurado.")
            return False
        try:
            shutil.copy2(bak, path)
            configure_vrto3d(path, undo=True)
            print(f"Configuracao anterior do SteamVR restaurada. Backup: {bak}")
            return True
        except Exception as e:
            print(f"!! Nao consegui restaurar o backup: {e}")
            return False
    if not vrto3d_driver_installed(path):
        print("!! Driver VRto3D nao encontrado na pasta de drivers do SteamVR.")
        print("   Instale o VRto3D antes de continuar: https://github.com/oneup03/VRto3D/releases")
        return False
    try:
        with open(path, encoding="utf-8") as f:
            cfg = json.load(f)
    except Exception as e:
        print(f"!! Nao consegui ler {path}: {e}\\n   Nada foi alterado.")
        return False
    if not os.path.exists(bak):
        shutil.copy2(path, bak)
    sv = cfg.setdefault("steamvr", {})
    sv.update(STEAMVR_KEYS)
    # Remove the previous fake Null HMD and its VMT-as-head override.
    cfg.pop("driver_null", None)
    ov = cfg.get("TrackingOverrides") or {}
    ov.pop(f"/devices/vmt/VMT_{index}", None)
    if ov:
        cfg["TrackingOverrides"] = ov
    else:
        cfg.pop("TrackingOverrides", None)
    try:
        with open(path, "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=3)
    except Exception as e:
        print(f"!! Nao consegui salvar steamvr.vrsettings: {e}")
        return False
    if not configure_vrto3d(path):
        try:
            shutil.copy2(bak, path)
        except Exception:
            pass
        print("!! A configuracao do VRto3D falhou; restaurei steamvr.vrsettings pelo backup.")
        return False
    print("Pronto. O SteamVR vai usar o HMD real do driver VRto3D (sem Null Driver).")
    print(f"Backup do SteamVR: {bak}")
    if not chained:
        print("Agora: abra o SteamVR, abra o app no celular e rode: python relay.py")
    print("Para voltar ao normal: python relay.py --undo-head")
    return True

def pose_sender(args, state, st, tx, ot_addr, vmt_addr, sgn):
    period = 1.0 / max(30.0, args.rate)
    ahead = max(0.0, args.predict_ms) / 1000.0
    nxt = time.perf_counter()
    while not st["quit"]:
        nxt += period
        delay = nxt - time.perf_counter()
        if delay > 0:
            time.sleep(delay)
        else:
            nxt = time.perf_counter()
        r = state.predict(time.time(), ahead, 0.06)
        if r is None:
            continue
        rp, rq = r
        if ot_addr:
            yaw, pitch, roll = euler_deg(rq)
            tx.sendto(struct.pack("<6d", sgn("x") * rp[0] * 100.0, sgn("y") * rp[1] * 100.0,
                                  sgn("z") * rp[2] * 100.0, sgn("yaw") * yaw,
                                  sgn("pitch") * pitch, sgn("roll") * roll), ot_addr)
        if vmt_addr:
            tx.sendto(osc("/VMT/Room/Driver", args.vmt_index, 1, 0.0,
                          rp[0], rp[1] + args.height, rp[2],
                          rq[0], rq[1], rq[2], rq[3]), vmt_addr)


def main():
    ap = argparse.ArgumentParser(description="Lado do PC do app do celular: recebe a pose da cabeca, manda pro SteamVR e transmite a tela pro celular.")
    ap.add_argument("--port", type=int, default=6000)
    ap.add_argument("--opentrack", default="127.0.0.1:4242")
    ap.add_argument("--vmt", default="off", help="VMT OSC para tracker extra de cabeca; normalmente off (maos usam VMT separadamente)")
    ap.add_argument("--vmt-index", type=int, default=2)
    ap.add_argument("--height", type=float, default=1.6)
    ap.add_argument("--invert", default="")
    ap.add_argument("--rate", type=float, default=90.0, help="quantas vezes por segundo a pose e enviada ao SteamVR")
    ap.add_argument("--predict-ms", type=float, default=20.0, help="quanto a pose e adiantada (0 desliga)")
    ap.add_argument("--stream", action="store_true")
    ap.add_argument("--no-stream", action="store_true")
    ap.add_argument("--test-video", action="store_true", help="manda uma imagem de teste em vez da tela, para testar so o celular")
    ap.add_argument("--manual", action="store_true")
    ap.add_argument("--no-steamvr", action="store_true")
    ap.add_argument("--no-hands", action="store_true")
    ap.add_argument("--hands", default="")
    ap.add_argument("--ffmpeg", default="ffmpeg")
    ap.add_argument("--encoder", default="auto", help="auto | h264_nvenc | h264_amf | h264_qsv | libx264")
    ap.add_argument("--grab", default="auto", help="auto | gdigrab | ddagrab")
    ap.add_argument("--monitor", type=int, default=0, help="monitor usado pelo ddagrab")
    ap.add_argument("--fps", type=int, default=None)
    ap.add_argument("--size", default=None)
    ap.add_argument("--bitrate", default=None)
    ap.add_argument("--capture", default="")
    ap.add_argument("--video-port", type=int, default=5000)
    ap.add_argument("--setup-head", action="store_true")
    ap.add_argument("--undo-head", action="store_true")
    ap.add_argument("--vrsettings", default="")
    args = ap.parse_args()

    if args.setup_head or args.undo_head:
        setup_head(args.vmt_index, args.undo_head, args.vrsettings)
        return

    configured = head_is_setup(args.vmt_index, args.vrsettings)
    if not args.manual and not configured:
        if steamvr_running():
            print("Primeira vez: feche o SteamVR por completo e rode de novo (ele se configura sozinho).")
            return
        print("Primeira vez: configurando o SteamVR...\n")
        if not setup_head(args.vmt_index, False, args.vrsettings, chained=True):
            return
        configured = head_is_setup(args.vmt_index, args.vrsettings)
        if not configured:
            print("!! Configuracao do VRto3D incompleta. Corrija o OpenTrack e rode novamente.")
            return
    auto = configured and not args.manual
    stream_on = (args.stream or args.test_video or (auto and not args.no_stream))
    hands_path = "" if (args.manual or args.no_hands) else find_hands(args.hands)
    if auto and not args.no_steamvr:
        start_steamvr()

    plan = make_plan(args) if stream_on else None
    if stream_on and plan is None:
        stream_on = False

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

    raise_priority()
    st = {"recenter": True, "quit": False, "phone_ip": None, "ffmpeg": None}
    state = PoseState()
    threading.Thread(target=key_loop, args=(st,), daemon=True).start()
    threading.Thread(target=pose_sender, args=(args, state, st, tx, ot_addr, vmt_addr, sgn), daemon=True).start()
    for sig_name in ("SIGTERM", "SIGBREAK"):
        if hasattr(signal, sig_name):
            try:
                signal.signal(getattr(signal, sig_name), lambda *a: st.__setitem__("quit", True))
            except Exception:
                pass

    origin_p = (0.0, 0.0, 0.0)
    origin_q = (0.0, 0.0, 0.0, 1.0)
    video_thread, hands_proc = None, None
    n_pkts, t_stat, last_pose, last_rx, last_seq = 0, time.time(), None, 0.0, None
    lost = 0

    print(f"Esperando o celular na porta UDP :{args.port}   |   teclas: r = recentralizar, q = sair")
    print(f"OpenTrack -> {args.opentrack}   VMT -> {args.vmt} (tracker {args.vmt_index})   envio {args.rate:.0f} Hz, adianta {args.predict_ms:.0f} ms\n")
    try:
        while not st["quit"]:
            try:
                data, src = rx.recvfrom(256)
            except socket.timeout:
                data = None
            now = time.time()
            if data is not None and len(data) >= PKT.size:
                magic, seq, x, y, z, qx, qy, qz, qw, tracking = PKT.unpack_from(data)
                if magic == MAGIC:
                    if st["phone_ip"] != src[0]:
                        st["phone_ip"] = src[0]
                        print(f"\nCelular encontrado: {src[0]}")
                        print(f"  camera pro hands.py:  python hands.py http://{src[0]}:8080/video\n")
                        if plan and video_thread is None:
                            video_thread = threading.Thread(target=video_worker, args=(args, plan, st), daemon=True)
                            video_thread.start()
                        if hands_path and hands_proc is None:
                            print(f"Iniciando o hands: {os.path.basename(hands_path)}")
                            hands_proc = subprocess.Popen([sys.executable, hands_path, f"http://{src[0]}:8080/video", "--vr"],
                                                          cwd=os.path.dirname(os.path.abspath(hands_path)))
                        elif not hands_path and not args.manual and not args.no_hands:
                            print("(coloque o hands.py ao lado do relay.py e ele abre sozinho)")
                    if last_seq is not None and seq > last_seq + 1:
                        lost += seq - last_seq - 1
                    last_seq = seq
                    n_pkts += 1
                    last_rx = now
                    q = q_norm((qx, qy, qz, qw))
                    p = (x, y, z)
                    if tracking != 1:
                        last_pose = None
                        state.clear()
                    else:
                        if st["recenter"]:
                            origin_p, origin_q = p, yaw_quat(q)
                            st["recenter"] = False
                            state.clear()
                            print("\nRecentralizado.")
                        inv = q_conj(origin_q)
                        rp = q_rot(inv, (p[0] - origin_p[0], p[1] - origin_p[1], p[2] - origin_p[2]))
                        rq = q_norm(q_mul(inv, q))
                        last_pose = (rp, rq)
                        state.update(now, rp, rq)
            if now - t_stat >= 1.0:
                rate = n_pkts / (now - t_stat)
                if last_pose and now - last_rx < 1.0:
                    rp, rq = last_pose
                    yaw, pitch, roll = euler_deg(rq)
                    msg = (f"{rate:4.0f} pkt/s  perdidos {lost}  pos {rp[0]*100:+6.1f} {rp[1]*100:+6.1f} {rp[2]*100:+6.1f} cm"
                           f"  yaw {yaw:+6.1f} pitch {pitch:+6.1f} roll {roll:+6.1f}")
                elif now - last_rx < 1.0:
                    msg = "celular conectado, mas o ARCore ainda nao rastreia (mexa o celular devagar olhando algo com textura)"
                else:
                    msg = "esperando o celular..."
                print("\r" + msg.ljust(110), end="", flush=True)
                n_pkts, t_stat = 0, now
    except KeyboardInterrupt:
        pass
    finally:
        st["quit"] = True
        time.sleep(0.15)
        if vmt_addr:
            tx.sendto(osc("/VMT/Room/Driver", args.vmt_index, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0), vmt_addr)
        proc = st.get("ffmpeg")
        if proc and proc.poll() is None:
            proc.terminate()
        if hands_proc and hands_proc.poll() is None:
            hands_proc.terminate()
        print("\nTchau.")


if __name__ == "__main__":
    main()
