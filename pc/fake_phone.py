#!/usr/bin/env python3
"""Pretends to be the phone: sends a head pose that turns left/right and moves a little.
Use it to test relay.py + VRto3D + VMT without the phone:   python fake_phone.py
"""
import math, socket, struct, sys, time
ip = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
port = int(sys.argv[2]) if len(sys.argv) > 2 else 6000
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
seq, t0 = 0, time.time()
print(f"Sending a fake head pose to {ip}:{port}  (Ctrl+C to stop)")
while True:
    t = time.time() - t0
    yaw = math.radians(35 * math.sin(t * 0.8))        # turns left/right
    pitch = math.radians(10 * math.sin(t * 0.5))      # nods
    # quaternion = yaw (around Y) * pitch (around X)
    qy = (0, math.sin(yaw / 2), 0, math.cos(yaw / 2))
    qx = (math.sin(pitch / 2), 0, 0, math.cos(pitch / 2))
    ax, ay, az, aw = qy; bx, by, bz, bw = qx
    q = (aw*bx + ax*bw + ay*bz - az*by, aw*by - ax*bz + ay*bw + az*bx,
         aw*bz + ax*by - ay*bx + az*bw, aw*bw - ax*bx - ay*by - az*bz)
    x, y, z = 0.10 * math.sin(t * 0.6), 0.03 * math.sin(t), 0.10 * math.cos(t * 0.6) - 0.10
    s.sendto(struct.pack("<4si7fi", b"VRH1", seq, x, y, z, q[0], q[1], q[2], q[3], 1), (ip, port))
    seq += 1
    time.sleep(1 / 60)
