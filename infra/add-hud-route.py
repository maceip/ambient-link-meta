#!/usr/bin/env python3
import shutil, sys, re

PATH = "/etc/caddy/Caddyfile"
BLOCK = (
    "\thandle /hud/* {\n"
    "\t\troot * /home/devuser/ambient-link-meta/web\n"
    "\t\turi strip_prefix /hud\n"
    "\t\tfile_server\n"
    "\t}\n\n"
)
ANCHOR = "\treverse_proxy 127.0.0.1:5189\n"

with open(PATH, "r") as f:
    src = f.read()

if "/hud/*" in src:
    print("already-present")
    sys.exit(0)

idx = src.find(ANCHOR)
if idx == -1:
    print("anchor-not-found")
    sys.exit(2)

shutil.copy(PATH, PATH + ".bak.hud")
out = src[:idx] + BLOCK + src[idx:]
with open(PATH, "w") as f:
    f.write(out)
print("inserted")
