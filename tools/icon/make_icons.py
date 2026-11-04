"""
Pramaan launcher icons from one square master (tools/icon/pramaan_icon_1024.png, the ASHA artwork, teal background).

Writes, for mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi:
  res/mipmap-*/ic_launcher.png             48 dp, rounded square (Android 7 and older)
  res/mipmap-*/ic_launcher_round.png       48 dp, circle (Android 7.1 round icons)
  res/mipmap-*/ic_launcher_foreground.png  108 dp adaptive foreground (Android 8+), artwork at 80 %
and res/drawable-nodpi/pramaan_avatar.png (512 px circle, used on the welcome and setup screens).
mipmap-anydpi-v26/ic_launcher*.xml put the foreground on @color/pramaan_icon_background (#159EBD).

Usage (needs Pillow):  python tools/icon/make_icons.py [master.png]
"""
import os, sys
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RES = os.path.join(ROOT, "app", "src", "main", "res")
MASTER = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "tools", "icon", "pramaan_icon_1024.png")
DENS = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}

def masked(img, size, shape):
    i = img.resize((size, size), Image.LANCZOS).convert("RGBA")
    m = Image.new("L", (size * 4, size * 4), 0); d = ImageDraw.Draw(m)
    if shape == "round": d.ellipse([0, 0, size * 4 - 1, size * 4 - 1], fill=255)
    else: d.rounded_rectangle([0, 0, size * 4 - 1, size * 4 - 1], radius=int(size * 4 * 0.18), fill=255)
    i.putalpha(m.resize((size, size), Image.LANCZOS)); return i

def main():
    art = Image.open(MASTER).convert("RGB")
    for d, k in DENS.items():
        folder = os.path.join(RES, "mipmap-" + d); os.makedirs(folder, exist_ok=True)
        for old in ("ic_launcher.webp", "ic_launcher_round.webp"):   # a .webp and a .png of the same name break the build
            p = os.path.join(folder, old)
            if os.path.exists(p): os.remove(p)
        n = int(round(48 * k))
        masked(art, n, "square").save(os.path.join(folder, "ic_launcher.png"), optimize=True)
        masked(art, n, "round").save(os.path.join(folder, "ic_launcher_round.png"), optimize=True)
        f = int(round(108 * k)); a = int(round(108 * k * 0.80))
        fg = Image.new("RGBA", (f, f), (0, 0, 0, 0)); fg.alpha_composite(art.resize((a, a), Image.LANCZOS).convert("RGBA"), ((f - a) // 2, (f - a) // 2))
        fg.save(os.path.join(folder, "ic_launcher_foreground.png"), optimize=True)
        print(f"{d}: {n}px icons, {f}px foreground")
    os.makedirs(os.path.join(RES, "drawable-nodpi"), exist_ok=True)
    masked(art, 512, "round").save(os.path.join(RES, "drawable-nodpi", "pramaan_avatar.png"), optimize=True)

if __name__ == "__main__":
    main()
