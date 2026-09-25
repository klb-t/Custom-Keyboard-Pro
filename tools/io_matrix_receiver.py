#!/usr/bin/env python3
"""
IO Matrix stream receiver: turns the phone's stream lines into a virtual gamepad.

The phone sends one UDP datagram per reading, a line of text:

    io <stream id> <value> [<value> ...]

This maps stream values onto gamepad axes and buttons, so a stream such as

    {"id": "steer", "from": "acceleration",
     "via": ["tilt wheel", "calibrate", "range -45 45", "deadzone 0.05", "curve 1.5", "smooth"],
     "to": ["udp <this computer's address>:26760"]}

steers a game: python3 io_matrix_receiver.py --axis steer=lx

Backends, tried in this order:
  - vgamepad (Windows, needs the ViGEmBus driver):  pip install vgamepad
  - python-evdev uinput (Linux, needs write access to /dev/uinput):  pip install evdev
  - none: the values are printed, which is also the way to check the phone is heard.

Axes: lx, ly, rx, ry (sticks, -1..1) and lt, rt (triggers, 0..1).
Buttons: --button <stream>[:<index>]=<name>@<level>, e.g. --button steer=a@0.8.

Not tested against a real game yet. Only listen on networks you trust: anything on the
network can send these lines.
"""
import argparse
import socket
import sys

AXES = ("lx", "ly", "rx", "ry", "lt", "rt")


def parse_targets(items, kind):
    """'steer=lx' or 'tilt:1=ly' into {(stream, index): target}."""
    out = {}
    for item in items or []:
        key, _, target = item.partition("=")
        stream, _, index = key.partition(":")
        if not stream or not target:
            sys.exit(f"bad --{kind} {item!r}: write stream[:index]={kind}")
        out[(stream, int(index or 0))] = target.lower()
    return out


class Printer:
    name = "print"

    def axis(self, axis, value):
        print(f"{axis} = {value:+.3f}")

    def button(self, button, down):
        print(f"{button} {'down' if down else 'up'}")

    def flush(self):
        pass


class VGamepad:
    name = "vgamepad"

    def __init__(self):
        import vgamepad as vg  # noqa: F401 — the import is the test for this backend
        self.vg = vg
        self.pad = vg.VX360Gamepad()
        self.sticks = {"lx": 0.0, "ly": 0.0, "rx": 0.0, "ry": 0.0}
        self.buttons = {
            "a": vg.XUSB_BUTTON.XUSB_GAMEPAD_A, "b": vg.XUSB_BUTTON.XUSB_GAMEPAD_B,
            "x": vg.XUSB_BUTTON.XUSB_GAMEPAD_X, "y": vg.XUSB_BUTTON.XUSB_GAMEPAD_Y,
            "lb": vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_SHOULDER, "rb": vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_SHOULDER,
            "start": vg.XUSB_BUTTON.XUSB_GAMEPAD_START, "back": vg.XUSB_BUTTON.XUSB_GAMEPAD_BACK,
        }

    def axis(self, axis, value):
        if axis in self.sticks:
            self.sticks[axis] = max(-1.0, min(1.0, value))
            self.pad.left_joystick_float(self.sticks["lx"], self.sticks["ly"])
            self.pad.right_joystick_float(self.sticks["rx"], self.sticks["ry"])
        elif axis == "lt":
            self.pad.left_trigger_float(max(0.0, min(1.0, value)))
        elif axis == "rt":
            self.pad.right_trigger_float(max(0.0, min(1.0, value)))

    def button(self, button, down):
        b = self.buttons.get(button)
        if b is None:
            return
        (self.pad.press_button if down else self.pad.release_button)(button=b)

    def flush(self):
        self.pad.update()


class Evdev:
    name = "evdev"

    def __init__(self):
        from evdev import AbsInfo, UInput, ecodes as e
        self.e = e
        stick = AbsInfo(value=0, min=-32768, max=32767, fuzz=0, flat=0, resolution=0)
        trigger = AbsInfo(value=0, min=0, max=255, fuzz=0, flat=0, resolution=0)
        self.codes = {"lx": e.ABS_X, "ly": e.ABS_Y, "rx": e.ABS_RX, "ry": e.ABS_RY, "lt": e.ABS_Z, "rt": e.ABS_RZ}
        self.keys = {"a": e.BTN_SOUTH, "b": e.BTN_EAST, "x": e.BTN_WEST, "y": e.BTN_NORTH,
                     "lb": e.BTN_TL, "rb": e.BTN_TR, "start": e.BTN_START, "back": e.BTN_SELECT}
        caps = {
            e.EV_ABS: [(self.codes[a], trigger if a in ("lt", "rt") else stick) for a in AXES],
            e.EV_KEY: list(self.keys.values()),
        }
        self.ui = UInput(caps, name="IO Matrix pad", vendor=0x045E, product=0x028E)

    def axis(self, axis, value):
        code = self.codes.get(axis)
        if code is None:
            return
        if axis in ("lt", "rt"):
            v = int(max(0.0, min(1.0, value)) * 255)
        else:
            v = int(max(-1.0, min(1.0, value)) * 32767)
        self.ui.write(self.e.EV_ABS, code, v)

    def button(self, button, down):
        code = self.keys.get(button)
        if code is not None:
            self.ui.write(self.e.EV_KEY, code, 1 if down else 0)

    def flush(self):
        self.ui.syn()


def backend(name):
    choices = {"vgamepad": [VGamepad], "evdev": [Evdev], "print": [Printer], "auto": [VGamepad, Evdev, Printer]}[name]
    for make in choices:
        try:
            return make()
        except Exception as problem:  # a missing library or driver: try the next one
            if name != "auto":
                sys.exit(f"{make.name}: {problem}")
    return Printer()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=26760)
    ap.add_argument("--bind", default="0.0.0.0", help="address to listen on")
    ap.add_argument("--axis", action="append", help="stream[:index]=lx|ly|rx|ry|lt|rt")
    ap.add_argument("--button", action="append", help="stream[:index]=a|b|x|y|lb|rb|start|back@level")
    ap.add_argument("--backend", default="auto", choices=["auto", "vgamepad", "evdev", "print"])
    ap.add_argument("--verbose", action="store_true", help="print every line received")
    args = ap.parse_args()

    axes = parse_targets(args.axis, "axis")
    for target in axes.values():
        if target not in AXES:
            sys.exit(f"unknown axis {target!r}: use one of {', '.join(AXES)}")
    buttons = {}
    for key, target in parse_targets(args.button, "button").items():
        name, _, level = target.partition("@")
        buttons[key] = (name, float(level or 0.5))
    pressed = {}

    pad = backend(args.backend)
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind((args.bind, args.port))
    print(f"listening on {args.bind}:{args.port}, backend {pad.name}", flush=True)
    if not axes and not buttons:
        print("no --axis or --button given: printing what arrives", flush=True)

    while True:
        data, sender = sock.recvfrom(2048)
        for line in data.decode("utf-8", "replace").splitlines():
            parts = line.split()
            if len(parts) < 3 or parts[0] != "io":
                continue
            stream = parts[1]
            try:
                values = [float(v) for v in parts[2:]]
            except ValueError:
                continue
            if args.verbose or (not axes and not buttons):
                print(f"{sender[0]}: {line}", flush=True)
            for i, v in enumerate(values):
                if (stream, i) in axes:
                    pad.axis(axes[(stream, i)], v)
                if (stream, i) in buttons:
                    name, level = buttons[(stream, i)]
                    down = abs(v) >= level
                    if pressed.get((stream, i)) != down:
                        pressed[(stream, i)] = down
                        pad.button(name, down)
            pad.flush()


if __name__ == "__main__":
    main()
