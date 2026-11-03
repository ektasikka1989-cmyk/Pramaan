"""Synthetic demo photos for Pramaan (fictional people, fictional numbers).
Every person, phone and ID number here is invented; the "Aadhaar" 0000 1111 2222 is
deliberately invalid (real ones never start with 0 or 1) and only shows the masking.
Usage: python make_samples.py <out_dir> <yyyy-mm-dd today>
"""
import random
import sys
from datetime import date, timedelta
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = Path(sys.argv[1])
TODAY = date.fromisoformat(sys.argv[2])
OUT.mkdir(parents=True, exist_ok=True)
F = r"C:\Windows\Fonts"
random.seed(7)


def font(name, size):
    return ImageFont.truetype(f"{F}\\{name}", size)


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
    """Paste [fg] on [bg] rotated, with a soft shadow (a photo of a sheet lying on a surface)."""
    rot = fg.rotate(angle, expand=True, resample=Image.BICUBIC, fillcolor=(0, 0, 0, 0)) if fg.mode == "RGBA" else \
        fg.convert("RGBA").rotate(angle, expand=True, resample=Image.BICUBIC)
    shadow = Image.new("RGBA", rot.size, (0, 0, 0, 0))
    shadow.putalpha(rot.split()[3].point(lambda a: int(a * 0.35)))
    shadow = shadow.filter(ImageFilter.GaussianBlur(12))
    bg.paste((60, 55, 50), (xy[0] + 14, xy[1] + 16), shadow)
    bg.paste(rot, xy, rot)


def camera(img):
    img = img.filter(ImageFilter.GaussianBlur(0.6))
    return img


def save(img, name):
    p = OUT / name
    img.save(p, "JPEG", quality=90)
    print(p.name, img.size)


hand = font("segoeui.ttf", 34)


# 1. New family slip (one person: what "Add person from a photo" reads) ──────────────
def new_family_slip():
    w, h = 1100, 1350
    sh = paper(w, h)
    dr = ImageDraw.Draw(sh)
    dr.rectangle([30, 30, w - 30, h - 30], outline=(40, 40, 40), width=3)
    dr.text((60, 60), "HOUSEHOLD SURVEY - NEW FAMILY", font=font("arialbd.ttf", 44), fill=(20, 20, 20))
    dr.text((60, 120), "Sub-centre Rampur  |  ASHA: Sunita Devi", font=font("arial.ttf", 30), fill=(60, 60, 60))
    dr.line([60, 170, w - 60, 170], fill=(40, 40, 40), width=2)
    rows = [("Name", "Kavita Yadav"), ("Relative", "W/o Ramesh Yadav"), ("Age", "24 years"),
            ("DOB", "12/03/2002"), ("Sex", "Female"), ("Village", "Rampur"), ("Household", "H-051"),
            ("Phone", "90000 00151"), ("Aadhaar", "0000 1111 2222"),
            ("LMP", d(TODAY - timedelta(weeks=13))), ("ANC", "1st ANC done " + d(TODAY - timedelta(days=10))),
            ("Status", "Pregnant (first pregnancy)")]
    y = 205
    for k, v in rows:
        dr.text((70, y), k + ":", font=font("arialbd.ttf", 34), fill=(30, 30, 30))
        dr.line([330, y + 44, w - 70, y + 44], fill=(150, 150, 150), width=1)
        dr.text((340, y + 2), v, font=hand, fill=(20, 40, 140))
        y += 88
    dr.text((70, h - 110), "Family consent taken: Yes", font=font("arial.ttf", 30), fill=(50, 50, 50))
    bg = cloth(1500, 1800)
    place(bg, sh, (190, 210), 1.5)
    save(camera(bg), "01_new_family_slip.jpg")


# 2. Household register page with 10 people ─────────────────────────────────────────
def register_10():
    w, h = 2000, 1300
    sh = paper(w, h, (250, 249, 238))
    dr = ImageDraw.Draw(sh)
    dr.text((50, 40), "ASHA HOUSEHOLD REGISTER - VILLAGE RAMPUR", font=font("arialbd.ttf", 46), fill=(20, 20, 20))
    dr.text((50, 100), "Page 12   |   Sub-centre Rampur   |   ASHA: Sunita Devi", font=font("arial.ttf", 30), fill=(70, 70, 70))
    cols = [("S.No", 90), ("Name", 330), ("Relative", 330), ("Age", 100), ("Sex", 90), ("Household", 190),
            ("Phone", 250), ("Category", 330)]
    people = [
        ("Kavita Yadav", "W/o Ramesh Yadav", "24", "F", "H-051", "90000 00151", "Pregnant"),
        ("Ramesh Yadav", "S/o Mohan Yadav", "28", "M", "H-051", "90000 00152", "Eligible couple"),
        ("Pooja Kumari", "W/o Sanjay Kumar", "22", "F", "H-052", "90000 00153", "Eligible couple"),
        ("Aarav Kumar", "S/o Pooja Kumari", "2", "M", "H-052", "-", "Child 0-5 years"),
        ("Meena Devi", "W/o Raju Singh", "31", "F", "H-053", "90000 00154", "Mother (PNC)"),
        ("Rekha Singh", "D/o Raju Singh", "0", "F", "H-053", "-", "Newborn"),
        ("Anjali Verma", "W/o Deepak Verma", "26", "F", "H-054", "90000 00155", "Pregnant"),
        ("Sushila Devi", "W/o Ram Prasad", "58", "F", "H-055", "90000 00156", "Elderly (NCD)"),
        ("Geeta Rani", "D/o Ram Prasad", "16", "F", "H-055", "-", "Adolescent"),
        ("Lata Kumari", "W/o Vijay Pal", "29", "F", "H-056", "90000 00157", "Eligible couple"),
    ]
    x0, y0, rh = 40, 160, 100
    xs = [x0]
    for _, cw in cols:
        xs.append(xs[-1] + cw)
    dr.rectangle([x0, y0, xs[-1], y0 + rh * 11], outline=(40, 40, 40), width=3)
    for x in xs[1:-1]:
        dr.line([x, y0, x, y0 + rh * 11], fill=(90, 90, 90), width=2)
    for r in range(1, 11):
        dr.line([x0, y0 + rh * r, xs[-1], y0 + rh * r], fill=(120, 120, 120), width=2)
    for i, (name, _) in enumerate(cols):
        dr.text((xs[i] + 12, y0 + 30), name, font=font("arialbd.ttf", 30), fill=(20, 20, 20))
    for r, row in enumerate(people, start=1):
        vals = [str(r)] + list(row)
        for i, v in enumerate(vals):
            dr.text((xs[i] + 12, y0 + rh * r + 28), v, font=font("segoeui.ttf", 30), fill=(20, 40, 140))
    bg = cloth(2300, 1650)
    place(bg, sh, (130, 150), -1.0)
    save(camera(bg), "02_register_10_people.jpg")


# 3. MCP card immunization page for Baby of Neha (matches the demo hero) ─────────────
def mcp_card():
    dob = TODAY - timedelta(days=6)
    given = dob + timedelta(days=1)
    w, h = 1300, 1750
    sh = paper(w, h, (253, 247, 252))
    dr = ImageDraw.Draw(sh)
    dr.rectangle([0, 0, w, 120], fill=(154, 108, 180))
    dr.text((40, 30), "MOTHER AND CHILD PROTECTION CARD", font=font("arialbd.ttf", 46), fill=(255, 255, 255))
    dr.text((40, 150), "Immunization (MCP Card p.36)", font=font("arialbd.ttf", 38), fill=(90, 40, 120))
    info = [("Child name", "Baby of Neha"), ("Mother name", "Neha"), ("Date of birth", d(dob)),
            ("Birth weight", "2.8 kg"), ("Place of birth", "Home")]
    y = 220
    for k, v in info:
        dr.text((50, y), k + ":", font=font("arialbd.ttf", 32), fill=(40, 40, 40))
        dr.text((360, y), v, font=hand, fill=(20, 40, 140))
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
        dr.text((cx[i] + 14, ty + 14), t, font=font("arialbd.ttf", 32), fill=(60, 20, 90))
    rh = 66
    for r, (age, vac, dt) in enumerate(rows):
        yy = ty + 62 + r * rh
        dr.line([cx[0], yy + rh, cx[-1], yy + rh], fill=(170, 150, 180), width=2)
        dr.text((cx[0] + 14, yy + 16), age, font=font("arial.ttf", 30), fill=(40, 40, 40))
        dr.text((cx[1] + 14, yy + 16), vac, font=font("arialbd.ttf", 30), fill=(30, 30, 30))
        if dt:
            dr.text((cx[2] + 14, yy + 12), dt, font=hand, fill=(20, 40, 140))
    for x in cx:
        dr.line([x, ty, x, ty + 62 + len(rows) * rh], fill=(150, 120, 170), width=2)
    wy = ty + 62 + len(rows) * rh + 30
    dr.text((50, wy), "Weight record:  " + d(dob) + "  -  2.8 kg", font=font("arialbd.ttf", 32), fill=(40, 40, 40))
    bg = cloth(1700, 2150)
    place(bg, sh, (180, 170), 1.2)
    save(camera(bg), "03_mcp_card_baby_of_neha.jpg")


# 4/5. Medicine strips on white cloth ──────────────────────────────────────────────
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
    tx = 830
    dr.rectangle([780, 20, w - 20, h - 20], fill=(250, 250, 250, 255))
    y = 40
    for i, (t, size, bold) in enumerate(lines):
        dr.text((800, y), t, font=font("arialbd.ttf" if bold else "arial.ttf", size), fill=color)
        y += size + 14
    return s


def medicine(name, lines, color, fname):
    bg = cloth(1600, 1150)
    s = strip(lines, color)
    place(bg, s, (200, 300), -4)
    save(camera(bg), fname)


# 6. Drug-kit photo: 6 ORS sachets spread on white cloth (for the SAM 3.1 count) ──────
def ors_kit():
    bg = cloth(1800, 1300)
    spots = [(140, 150), (700, 120), (1240, 170), (180, 700), (740, 690), (1280, 720)]
    for i, (x, y) in enumerate(spots):
        sw, shh = 400, 470
        s = Image.new("RGBA", (sw, shh), (0, 0, 0, 0))
        dr = ImageDraw.Draw(s)
        dr.rounded_rectangle([0, 0, sw - 1, shh - 1], radius=18, fill=(250, 250, 250, 255), outline=(30, 110, 60, 255), width=5)
        dr.rectangle([0, 0, sw, 110], fill=(30, 130, 70, 255))
        dr.text((120, 18), "ORS", font=font("arialbd.ttf", 72), fill=(255, 255, 255))
        dr.text((30, 140), "Oral Rehydration", font=font("arialbd.ttf", 36), fill=(20, 80, 40))
        dr.text((30, 185), "Salts IP", font=font("arialbd.ttf", 36), fill=(20, 80, 40))
        dr.text((30, 250), "WHO formula", font=font("arial.ttf", 32), fill=(40, 40, 40))
        dr.text((30, 300), "20.5 g sachet", font=font("arial.ttf", 32), fill=(40, 40, 40))
        dr.text((30, 360), "Mix in 1 litre", font=font("arial.ttf", 30), fill=(40, 40, 40))
        dr.text((30, 400), "of clean water", font=font("arial.ttf", 30), fill=(40, 40, 40))
        place(bg, s, (x, y), random.uniform(-8, 8))
    save(camera(bg), "06_drug_kit_6_ORS_sachets.jpg")


new_family_slip()
register_10()
mcp_card()
medicine("Paracetamol", [("PARACETAMOL", 40, True), ("TABLETS IP 500 mg", 30, True), ("Batch No. PCM2611B", 26, False),
                         ("Mfg. 06/2026", 26, False), ("Exp. 05/2028", 28, True), ("10 Tablets", 26, False),
                         ("Govt. supply", 24, False)], (20, 50, 140), "04_medicine_paracetamol_on_cloth.jpg")
medicine("IFA", [("IRON & FOLIC ACID", 34, True), ("TABLETS IP (IFA)", 30, True), ("Batch No. IFA2610C", 26, False),
                 ("Mfg. 04/2026", 26, False), ("Exp. 03/2028", 28, True), ("10 Tablets", 26, False),
                 ("Govt. supply", 24, False)], (150, 20, 30), "05_medicine_IFA_on_cloth.jpg")
ors_kit()
