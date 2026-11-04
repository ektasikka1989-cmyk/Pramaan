"""Synthetic demo photos for Pramaan's three demo workflows (fictional people, fictional numbers).

Every person, phone and ID number here is invented; the "Aadhaar" 0000 1111 2222 is deliberately
invalid (real ones never start with 0 or 1) and only shows the masking.

Usage:  python make_samples.py <out_dir> <yyyy-mm-dd demo date>
Dates on the photos are computed from the demo date, so regenerate on the day you record.

  W1_1_sarita_registration_slip.jpg   Workflow 1 · Add person (register extract, read row by row on the phone)
  W2_1_baby_of_neha_mcp_card.jpg      Workflow 2 · MCP card photo (ANM's birth-dose vaccines, day 3)
  W3_1_zinc_new_stock.jpg             Workflow 3 · Medicine scan (new zinc stock: name, batch, expiry, 30 tablets)
  W3_2_ors_6_sachets.jpg              Workflow 3 · Kit count with SAM 3.1 (online)
  X_register_page_8_people.jpg        Extra · Registers → Add person → photo of a register page (8 people)
  X_ifa_strip.jpg                     Extra · second medicine scan
"""
import os
import random
import sys
from datetime import date, timedelta
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = Path(sys.argv[1])
TODAY = date.fromisoformat(sys.argv[2])
OUT.mkdir(parents=True, exist_ok=True)
random.seed(7)

# Windows fonts when present, else Liberation (metric-compatible with Arial) / DejaVu.
_CANDIDATES = {
    "regular": [r"C:\Windows\Fonts\arial.ttf", "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"],
    "bold": [r"C:\Windows\Fonts\arialbd.ttf", "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"],
    "hand": [r"C:\Windows\Fonts\segoeui.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf"],
}


def font(kind, size):
    for p in _CANDIDATES[kind]:
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


def d(x):
    return x.strftime("%d/%m/%Y")


def cloth(w, h):
    """White cotton: fine weave, grain noise and two soft folds."""
    img = Image.new("RGB", (w, h), (244, 244, 240))
    px = img.load()
    for y in range(h):
        for x in range(w):
            v = 244 + random.randint(-6, 4) - (3 if x % 4 == 0 else 0) - (3 if y % 4 == 0 else 0)
            px[x, y] = (v, v, v - 3)
    folds = Image.new("L", (w, h), 0)
    fd = ImageDraw.Draw(folds)
    for _ in range(2):
        x0 = random.randint(0, w)
        fd.line([(x0, 0), (x0 + random.randint(-300, 300), h)], fill=60, width=40)
    folds = folds.filter(ImageFilter.GaussianBlur(40))
    shade = Image.new("RGB", (w, h), (180, 180, 176))
    return Image.composite(shade, img, folds.point(lambda v: int(v * 0.5)))


def paper(w, h, tint=(252, 250, 242)):
    img = Image.new("RGB", (w, h), tint)
    dr = ImageDraw.Draw(img)
    for _ in range(w * h // 300):
        x, y = random.randrange(w), random.randrange(h)
        c = random.randint(236, 248)
        dr.point((x, y), fill=(c, c, c - 6))
    return img


def place(bg, fg, xy, angle):
    """Paste [fg] on [bg] slightly rotated, with a soft shadow (a sheet lying on a surface)."""
    rot = fg.convert("RGBA").rotate(angle, expand=True, resample=Image.BICUBIC)
    shadow = Image.new("RGBA", rot.size, (0, 0, 0, 0))
    shadow.putalpha(rot.split()[3].point(lambda a: int(a * 0.35)))
    shadow = shadow.filter(ImageFilter.GaussianBlur(12))
    bg.paste((60, 55, 50), (xy[0] + 14, xy[1] + 16), shadow)
    bg.paste(rot, xy, rot)


def save(img, name):
    p = OUT / name
    img.filter(ImageFilter.GaussianBlur(0.5)).save(p, "JPEG", quality=90)
    print(p.name, img.size)


def ruled_table(title, sub, cols, rows, tint=(250, 249, 238), row_h=96, head_size=30, cell_size=30):
    """A ruled register page: title, a header row with the official column names, one person per row."""
    w = sum(cw for _, cw in cols) + 80
    h = 170 + row_h * (len(rows) + 1) + 70
    sh = paper(w, h, tint)
    dr = ImageDraw.Draw(sh)
    dr.text((40, 34), title, font=font("bold", 44), fill=(20, 20, 20))
    dr.text((40, 94), sub, font=font("regular", 28), fill=(70, 70, 70))
    x0, y0 = 40, 150
    xs = [x0]
    for _, cw in cols:
        xs.append(xs[-1] + cw)
    bottom = y0 + row_h * (len(rows) + 1)
    dr.rectangle([x0, y0, xs[-1], bottom], outline=(40, 40, 40), width=3)
    for x in xs[1:-1]:
        dr.line([x, y0, x, bottom], fill=(90, 90, 90), width=2)
    for r in range(1, len(rows) + 1):
        dr.line([x0, y0 + row_h * r, xs[-1], y0 + row_h * r], fill=(120, 120, 120), width=2)
    for i, (name, _) in enumerate(cols):
        dr.text((xs[i] + 12, y0 + 30), name, font=font("bold", head_size), fill=(20, 20, 20))
    for r, row in enumerate(rows, start=1):
        for i, v in enumerate(row):
            dr.text((xs[i] + 12, y0 + row_h * r + 28), v, font=font("hand", cell_size), fill=(20, 40, 140))
    return sh


# ── Workflow 1 · Sarita's pregnancy registration (one row; the phone reads it row by row) ──
def w1_slip():
    lmp = TODAY - timedelta(weeks=10)
    cols = [("Name", 300), ("Husband", 290), ("Age", 90), ("Mobile", 250), ("House No.", 190), ("Village", 180), ("Category", 200), ("LMP", 210)]
    rows = [("Sarita Yadav", "Ramesh Yadav", "23", "9000000301", "H-051", "Rampur", "Pregnant", d(lmp))]
    sh = ruled_table("PREGNANCY REGISTRATION - VILLAGE RAMPUR", "Sub-centre Rampur  |  ASHA: Sunita Devi  |  Aadhaar seen: 0000 1111 2222",
                     cols, rows, row_h=110)
    dr = ImageDraw.Draw(sh)
    dr.text((40, sh.size[1] - 56), "Family consent taken: Yes          Signature: ______________", font=font("regular", 26), fill=(50, 50, 50))
    bg = cloth(sh.size[0] + 260, sh.size[1] + 320)
    place(bg, sh, (110, 140), 0.4)
    save(bg, "W1_1_sarita_registration_slip.jpg")


# ── Workflow 2 · Baby of Neha's MCP card, immunization page (written by the ANM on day 3) ──
def w2_card():
    dob = TODAY - timedelta(days=6)        # the sample village's Baby of Neha is 6 days old today (day 7 visit)
    given = dob + timedelta(days=2)        # ANM's day-3 visit: birth doses for a home birth
    w, h = 1300, 1750
    sh = paper(w, h, (253, 247, 252))
    dr = ImageDraw.Draw(sh)
    dr.rectangle([0, 0, w, 120], fill=(154, 108, 180))
    dr.text((40, 30), "MOTHER AND CHILD PROTECTION CARD", font=font("bold", 46), fill=(255, 255, 255))
    dr.text((40, 150), "Immunization (MCP Card p.36)", font=font("bold", 38), fill=(90, 40, 120))
    info = [("Child name", "Baby of Neha"), ("Mother name", "Neha"), ("Date of birth", d(dob)),
            ("Birth weight", "2.8 kg"), ("Place of birth", "Home")]
    y = 220
    for k, v in info:
        dr.text((50, y), k + ":", font=font("bold", 32), fill=(40, 40, 40))
        dr.text((360, y), v, font=font("hand", 34), fill=(20, 40, 140))
        y += 58
    rows = [("Birth", "OPV-0", d(given)), ("Birth", "Hep B", d(given)), ("Birth", "BCG", d(given)),
            ("1½ months", "OPV-1", ""), ("1½ months", "Penta-1", ""), ("1½ months", "Rota-1", ""),
            ("1½ months", "PCV-1", ""), ("1½ months", "IPV-1", ""), ("2½ months", "OPV-2", ""),
            ("2½ months", "Penta-2", ""), ("3½ months", "OPV-3", ""), ("3½ months", "Penta-3", ""),
            ("9 months", "MR-1", ""), ("9 months", "Vitamin A-1", "")]
    ty = 540
    cx = [50, 330, 640, w - 50]
    dr.rectangle([cx[0], ty, cx[-1], ty + 62], fill=(228, 210, 238))
    for i, t in enumerate(["Age", "Vaccine", "Date given"]):
        dr.text((cx[i] + 14, ty + 14), t, font=font("bold", 32), fill=(60, 20, 90))
    rh = 66
    for r, (age, vac, dt) in enumerate(rows):
        yy = ty + 62 + r * rh
        dr.line([cx[0], yy + rh, cx[-1], yy + rh], fill=(170, 150, 180), width=2)
        dr.text((cx[0] + 14, yy + 16), age, font=font("regular", 30), fill=(40, 40, 40))
        dr.text((cx[1] + 14, yy + 16), vac, font=font("bold", 30), fill=(30, 30, 30))
        if dt:
            dr.text((cx[2] + 14, yy + 12), dt, font=font("hand", 34), fill=(20, 40, 140))
    for x in cx:
        dr.line([x, ty, x, ty + 62 + len(rows) * rh], fill=(150, 120, 170), width=2)
    wy = ty + 62 + len(rows) * rh + 30
    dr.text((50, wy), "Weight record:  " + d(given) + "  -  2.85 kg   (ANM, day 3)", font=font("bold", 32), fill=(40, 40, 40))
    bg = cloth(1700, 2150)
    place(bg, sh, (180, 170), 1.2)
    save(bg, "W2_1_baby_of_neha_mcp_card.jpg")


# ── Medicine strips / boxes on white cloth ──
def strip(lines, color, blister=(206, 210, 214)):
    w, h = 1150, 470
    s = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    dr = ImageDraw.Draw(s)
    dr.rounded_rectangle([0, 0, w - 1, h - 1], radius=26, fill=(214, 218, 222, 255), outline=(150, 154, 160, 255), width=3)
    for i in range(0, w, 6):
        dr.line([i, 0, i, h], fill=(222, 226, 230, 255), width=1)
    for row in range(2):
        for col in range(5):
            cx, cy = 120 + col * 145, 120 + row * 210
            dr.ellipse([cx - 52, cy - 52, cx + 52, cy + 52], fill=blister + (255,), outline=(170, 174, 180, 255), width=3)
    dr.rectangle([780, 20, w - 20, h - 20], fill=(250, 250, 250, 255))
    y = 40
    for t, size, bold in lines:
        dr.text((800, y), t, font=font("bold" if bold else "regular", size), fill=color)
        y += size + 14
    return s


def medicine(lines, color, fname):
    bg = cloth(1600, 1150)
    place(bg, strip(lines, color), (200, 300), -4)
    save(bg, fname)


# ── Workflow 3 · six ORS sachets spread on white cloth (SAM 3.1 count) ──
def w3_ors():
    bg = cloth(1800, 1300)
    spots = [(140, 150), (700, 120), (1240, 170), (180, 700), (740, 690), (1280, 720)]
    for x, y in spots:
        sw, shh = 400, 470
        s = Image.new("RGBA", (sw, shh), (0, 0, 0, 0))
        dr = ImageDraw.Draw(s)
        dr.rounded_rectangle([0, 0, sw - 1, shh - 1], radius=18, fill=(250, 250, 250, 255), outline=(30, 110, 60, 255), width=5)
        dr.rectangle([0, 0, sw, 110], fill=(30, 130, 70, 255))
        dr.text((120, 18), "ORS", font=font("bold", 72), fill=(255, 255, 255))
        dr.text((30, 140), "Oral Rehydration", font=font("bold", 36), fill=(20, 80, 40))
        dr.text((30, 185), "Salts IP", font=font("bold", 36), fill=(20, 80, 40))
        dr.text((30, 250), "WHO formula", font=font("regular", 32), fill=(40, 40, 40))
        dr.text((30, 300), "20.5 g sachet", font=font("regular", 32), fill=(40, 40, 40))
        dr.text((30, 360), "Mix in 1 litre", font=font("regular", 30), fill=(40, 40, 40))
        dr.text((30, 400), "of clean water", font=font("regular", 30), fill=(40, 40, 40))
        place(bg, s, (x, y), random.uniform(-8, 8))
    save(bg, "W3_2_ors_6_sachets.jpg")


# ── Extra · register page with 8 people (Registers → Add person → photo) ──
def x_register():
    cols = [("S.No", 90), ("Name", 300), ("Husband/Father", 300), ("Age", 90), ("Sex", 80), ("Category", 270), ("House No.", 170), ("Mobile", 230)]
    rows = [("1", "Pooja Kumari", "Sanjay Kumar", "22", "F", "Eligible couple", "H-052", "9000000302"),
            ("2", "Aarav Kumar", "Pooja Kumari", "2", "M", "Child", "H-052", ""),
            ("3", "Meena Singh", "Raju Singh", "31", "F", "Mother", "H-053", "9000000304"),
            ("4", "Anjali Verma", "Deepak Verma", "26", "F", "Pregnant", "H-054", "9000000305"),
            ("5", "Sushila Devi", "Ram Prasad", "58", "F", "Other", "H-055", "9000000306"),
            ("6", "Geeta Rani", "Ram Prasad", "16", "F", "Other", "H-055", ""),
            ("7", "Lata Kumari", "Vijay Pal", "29", "F", "Eligible couple", "H-056", "9000000308"),
            ("8", "Rahul Pal", "Lata Kumari", "4", "M", "Child", "H-056", "")]
    sh = ruled_table("ASHA HOUSEHOLD REGISTER - VILLAGE RAMPUR", "Page 12  |  Sub-centre Rampur  |  ASHA: Sunita Devi", cols, rows)
    bg = cloth(sh.size[0] + 260, sh.size[1] + 300)
    place(bg, sh, (120, 140), -0.8)
    save(bg, "X_register_page_8_people.jpg")


w1_slip()
w2_card()
medicine([("ZINC SULPHATE", 38, True), ("DISPERSIBLE TABLETS IP", 26, True), ("20 mg", 30, True), ("Batch No. ZN2611A", 26, False),
          ("Mfg. 08/2026", 26, False), ("Exp. 07/2028", 28, True), ("30 Tablets", 28, True)], (20, 90, 60), "W3_1_zinc_new_stock.jpg")
w3_ors()
x_register()
medicine([("IRON & FOLIC ACID", 34, True), ("TABLETS IP (IFA)", 30, True), ("Batch No. IFA2610C", 26, False),
          ("Mfg. 04/2026", 26, False), ("Exp. 03/2028", 28, True), ("10 Tablets", 26, False),
          ("Govt. supply", 24, False)], (150, 20, 30), "X_ifa_strip.jpg")
