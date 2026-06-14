#!/usr/bin/env python3
"""
HUD design-lab bitmap generator.

Renders the "changed code files" diff card four ways so we can compare what the
Meta DAT *token* system can do vs. what the *bitmap escape hatch* (image(uri,
FILL)) buys us, and whether Meta's card chrome ("a frame around it") helps or
hurts.

Native display target: Meta Ray-Ban Display ~600x600 full-colour in-lens panel.
We render at SCALE x then downsample (LANCZOS) for crisp AA / rounded corners.
"""
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageChops
import os

OUT = os.path.dirname(os.path.abspath(__file__))
W = H = 600
S = 3  # supersample factor

# ---- palette (on-glass: black == transparent on an additive display) --------
BG       = (8, 9, 11)
CARD     = (22, 24, 28)
ROW      = (29, 32, 37)
STROKE   = (46, 50, 58)
WHITE    = (243, 245, 248)
SECOND   = (140, 147, 158)
BLUE     = (28, 132, 255)     # Meta blue
GREEN    = (61, 201, 122)     # additions
RED      = (240, 86, 110)     # deletions
TRACK    = (38, 42, 49)

# ---- real churn pulled from `git diff HEAD --numstat`, code files only -------
FILES = [
    ("HudPresenter.swift", "swift", 91, 24),
    ("app.js",             "js",    51, 28),
    ("ChipSet.swift",      "swift", 12, 31),
    ("HudPresenter.kt",    "kt",    24, 15),
    ("HudWidgets.kt",      "kt",    30,  8),
]
MAXTOTAL = max(a + d for _, _, a, d in FILES)

FONT_PATH = "/System/Library/Fonts/SFNS.ttf"
FALLBACK  = "/System/Library/Fonts/HelveticaNeue.ttc"
_font_cache = {}

def font(size, weight="Regular"):
    key = (size, weight)
    if key in _font_cache:
        return _font_cache[key]
    try:
        f = ImageFont.truetype(FONT_PATH, size * S)
        try:
            f.set_variation_by_name(weight)
        except Exception:
            pass
    except Exception:
        f = ImageFont.truetype(FALLBACK, size * S)
    _font_cache[key] = f
    return f

def rr(d, box, radius, fill=None, outline=None, width=1):
    d.rounded_rectangle([c * S for c in box], radius=radius * S, fill=fill,
                        outline=outline, width=max(1, width * S))

def txt(d, xy, s, f, fill, anchor="la"):
    d.text((xy[0] * S, xy[1] * S), s, font=f, fill=fill, anchor=anchor)

def textw(d, s, f):
    return d.textlength(s, font=f) / S

def new_canvas(bg=BG):
    img = Image.new("RGB", (W * S, H * S), bg)
    return img, ImageDraw.Draw(img)

def finish(img, name):
    img = img.resize((W, H), Image.LANCZOS)
    p = os.path.join(OUT, name)
    img.save(p)
    print("wrote", p)
    return img

# ============================================================================
# 1) NATIVE TOKENS — honest reproduction of what the DAT token system renders:
#    two text colours only, CARD backgrounds, a single-colour proportional bar
#    drawn the way flexGrow(CARD) + flexGrow(NONE) would. No green/red, no chips.
# ============================================================================
def render_native_tokens():
    img, d = new_canvas()
    m = 26
    rr(d, (m, m, W - m, H - m), 22, fill=CARD)            # the card
    x0, y = m + 22, m + 26
    txt(d, (x0, y), "changed files · code", font(20, "Medium"), SECOND)
    y += 44
    rowh, gap = 78, 12
    for name, lang, add, dele in FILES:
        total = add + dele
        rr(d, (x0, y, W - m - 22, y + rowh - gap), 14, fill=ROW)
        ix = x0 + 16
        txt(d, (ix, y + 14), name, font(24, "Semibold"), WHITE)
        churn = f"+{add}  -{dele}"
        txt(d, (W - m - 38, y + 14), churn, font(20, "Regular"), SECOND, anchor="ra")
        # proportional bar = filled(CARD) flexGrow:total  +  empty(NONE) flexGrow:rest
        bx0, bx1 = ix, W - m - 38
        by = y + 48
        full = bx1 - bx0
        filled = full * (total / MAXTOTAL)
        rr(d, (bx0, by, bx1, by + 8), 4, fill=TRACK)
        rr(d, (bx0, by, bx0 + filled, by + 8), 4, fill=WHITE)
        y += rowh
    # button row: PRIMARY + OUTLINE (real token styles)
    by = H - m - 60
    rr(d, (x0, by, x0 + 150, by + 44), 22, fill=WHITE)
    txt(d, (x0 + 75, by + 22), "open", font(22, "Semibold"), (10, 12, 16), anchor="mm")
    rr(d, (x0 + 166, by, x0 + 316, by + 44), 22, outline=STROKE, width=2)
    txt(d, (x0 + 241, by + 22), "dismiss", font(22, "Semibold"), WHITE, anchor="mm")
    return finish(img, "01_native_tokens.png")

# ============================================================================
# 2) BITMAP RICH — the image() escape hatch: full colour, green/red stacked
#    diff bars, language chips, proper footer pills. Same data, expanded design.
# ============================================================================
def diff_content(d, ox, oy, w, h, header=True):
    x0 = ox + 24
    y = oy + (24 if header else 16)
    if header:
        # accent code glyph chip
        rr(d, (x0, y, x0 + 46, y + 46), 12, fill=(BLUE[0]//4, BLUE[1]//4, BLUE[2]//3))
        txt(d, (x0 + 23, y + 23), "</>", font(20, "Bold"), BLUE, anchor="mm")
        txt(d, (x0 + 60, y + 4), "Changed files", font(28, "Bold"), WHITE)
        txt(d, (x0 + 60, y + 36), "code only · 5 of 12", font(18, "Regular"), SECOND)
        y += 66
    rowh = 70
    for name, lang, add, dele in FILES:
        total = add + dele
        # filename + lang chip
        txt(d, (x0, y), name, font(23, "Semibold"), WHITE)
        nw = textw(d, name, font(23, "Semibold"))
        cw = textw(d, lang, font(15, "Medium")) + 16
        rr(d, (x0 + nw + 12, y + 1, x0 + nw + 12 + cw, y + 22), 7, fill=ROW)
        txt(d, (x0 + nw + 12 + cw / 2, y + 12), lang, font(15, "Medium"), SECOND, anchor="mm")
        # churn numbers, coloured
        cx = ox + w - 24
        dtxt = f"-{dele}"
        txt(d, (cx, y), dtxt, font(20, "Semibold"), RED, anchor="ra")
        dw = textw(d, dtxt, font(20, "Semibold"))
        txt(d, (cx - dw - 12, y), f"+{add}", font(20, "Semibold"), GREEN, anchor="ra")
        # stacked bar: total length ∝ total/MAX; split green:red by add:dele
        bx0, bx1 = x0, ox + w - 24
        by = y + 34
        full = bx1 - bx0
        seg = full * (total / MAXTOTAL)
        rr(d, (bx0, by, bx1, by + 9), 4, fill=TRACK)
        gpx = seg * (add / total)
        rr(d, (bx0, by, bx0 + seg, by + 9), 4, fill=RED)        # whole seg red
        rr(d, (bx0, by, bx0 + max(gpx, 8), by + 9), 4, fill=GREEN)  # green overlay
        y += rowh
    # footer pills
    by = oy + h - 64
    pw = 168
    rr(d, (x0, by, x0 + pw, by + 46), 23, fill=BLUE)
    txt(d, (x0 + pw / 2, by + 23), "Open in editor", font(20, "Semibold"), WHITE, anchor="mm")
    rr(d, (x0 + pw + 14, by, x0 + pw + 14 + 130, by + 46), 23, outline=STROKE, width=2)
    txt(d, (x0 + pw + 14 + 65, by + 23), "Dismiss", font(20, "Semibold"), SECOND, anchor="mm")

def render_bitmap_rich():
    img, d = new_canvas()
    m = 26
    rr(d, (m, m, W - m, H - m), 24, fill=CARD)
    diff_content(d, m, m, W - 2 * m, H - 2 * m, header=True)
    return finish(img, "02_bitmap_rich.png")

# ============================================================================
# 3) BITMAP FRAMED — same content wrapped in a simulated Meta app-card "frame"
#    (identity strip + inset, corner-clipped content). Tests the frame hunch.
# ============================================================================
def render_bitmap_framed():
    img, d = new_canvas()
    m = 18
    rr(d, (m, m, W - m, H - m), 26, fill=(15, 16, 19), outline=STROKE, width=1)
    # identity strip
    sx, sy = m + 20, m + 18
    rr(d, (sx, sy, sx + 30, sy + 30), 9, fill=(BLUE[0]//4, BLUE[1]//4, BLUE[2]//3))
    txt(d, (sx + 15, sy + 15), "al", font(15, "Bold"), BLUE, anchor="mm")
    txt(d, (sx + 40, sy + 1), "ambient link", font(19, "Semibold"), WHITE)
    txt(d, (W - m - 20, sy + 4), "now", font(16, "Regular"), SECOND, anchor="ra")
    d.line([( (m+18)*S, (sy+44)*S), ((W-m-18)*S, (sy+44)*S)], fill=STROKE, width=max(1, S))
    # inset content area
    iy = sy + 56
    inset = (m + 8, iy, W - m - 8, H - m - 8)
    rr(d, inset, 18, fill=CARD)
    diff_content(d, inset[0], inset[1], inset[2]-inset[0], inset[3]-inset[1], header=False)
    return finish(img, "03_bitmap_framed.png")

# ============================================================================
# 4) ON-GLASS — variant 2 screen-blended over a synthetic room backdrop to
#    simulate the see-through additive display (black emits nothing).
# ============================================================================
def render_on_glass():
    # synthetic backdrop: vertical gradient + soft warm light blobs
    bd = Image.new("RGB", (W * S, H * S), (18, 20, 26))
    g = ImageDraw.Draw(bd)
    for yy in range(H * S):
        t = yy / (H * S)
        c = (int(30 - 18*t), int(34 - 20*t), int(44 - 24*t))
        g.line([(0, yy), (W * S, yy)], fill=c)
    blob = Image.new("RGB", (W * S, H * S), (0, 0, 0))
    gb = ImageDraw.Draw(blob)
    gb.ellipse([int(W*S*0.55), int(H*S*0.05), int(W*S*1.05), int(H*S*0.5)], fill=(70, 60, 40))
    gb.ellipse([int(-W*S*0.2), int(H*S*0.55), int(W*S*0.35), int(H*S*1.1)], fill=(30, 36, 60))
    blob = blob.filter(ImageFilter.GaussianBlur(60 * S))
    bd = ImageChops.add(bd, blob)
    # modal on pure black, then screen-blend (additive feel) at ~0.92
    modal, dm = new_canvas((0, 0, 0))
    m = 26
    rr(dm, (m, m, W - m, H - m), 24, fill=CARD)
    diff_content(dm, m, m, W - 2 * m, H - 2 * m, header=True)
    modal = Image.eval(modal, lambda v: int(v * 0.95))
    comp = ImageChops.screen(bd, modal)
    return finish(comp, "04_on_glass.png")

if __name__ == "__main__":
    render_native_tokens()
    render_bitmap_rich()
    render_bitmap_framed()
    render_on_glass()
    print("done")
