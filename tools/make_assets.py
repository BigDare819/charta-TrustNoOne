"""Generates the MyAvalon deck assets.

Two kinds of output, both in Charta's own formats:

* ``.mccard`` / ``.mcsuit`` -- the gzipped, palette-indexed pixel blobs Charta loads out of
  ``data/<ns>/images/{card,suit,deck}/``. Written with the same byte layout Charta's ``CardImage`` reads:
  low six bits select one of 64 palette colours, the top two bits select one of four alpha levels. Our
  blobs carry a small ``MYAV`` + width + height header in front of the gzip stream (see
  ``ScaledImages``) so the real size is exact even for art that is not a multiple of 5:7.
* ordinary PNGs for the mod icon, the game-select button, and the ``fullcard`` textures.

Charta's reader is hard-coded to 25x35 cards and 13x13 suits, but the blob format itself is size-agnostic
(one byte per pixel, no header), so the ``CardImageScale``/``SuitImageScale`` mixins recover the real size
from the header. That lets us author at full resolution instead of throwing detail away.

The 64-colour / 4-alpha quantisation is the one lossy step left, and it is Charta's format talking, not a
choice -- the palette shader indexes it by pixel byte. The full-colour PNG that the client actually renders
from (see ``FullColorCards``) is copied verbatim whenever the source art already matches the target size.

    python tools/make_assets.py
"""

import gzip
import os
import shutil
import struct

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "src", "main", "resources")
SRC = "C:/Users/BigMer/Desktop/charta/阿瓦隆"
FULL_DIR = os.path.join(RES, "assets", "myavalon", "fullcard")
GUI_DIR = os.path.join(RES, "assets", "myavalon", "textures", "gui")

# The size header read back by ``ScaledImages``. Kept in front of the gzip stream on purpose: the reader
# checks for it before decompressing, and the Java ``compress()`` mixin writes it back the same way.
MAGIC = b"MYAV"

# Charta's logical card size is 25x35; we render procedural art at SCALE times that. 13 keeps the drawn
# line weights equal to the shipped art's.
SCALE = 13
UNIT_W, UNIT_H = 25, 35
CARD_W, CARD_H = UNIT_W * SCALE, UNIT_H * SCALE
SUIT_W, SUIT_H = 13 * SCALE, 13 * SCALE

# CardImage.COLOR_PALETTE, verbatim. Index 0 is black and doubles as the "empty pixel" colour, so a visible
# pixel may use it but must carry a non-zero alpha index -- see `quantise`.
PALETTE = [
    0x000000, 0x252525, 0x494949, 0x6e6e6e, 0x929292, 0xb7b7b7, 0xdcdcdc, 0xffffff,
    0x7f0000, 0xb20000, 0xe30000, 0xff0000, 0xff5353, 0xff7575, 0xff9898, 0xffbaba,
    0x7f3f00, 0xb25800, 0xe37000, 0xff7f00, 0xffa953, 0xffba75, 0xffcb98, 0xffdcba,
    0x7f7f00, 0xb2b200, 0xe3e300, 0xffff00, 0xffff53, 0xffff75, 0xffff98, 0xffffba,
    0x007f00, 0x00b200, 0x00e300, 0x00ff00, 0x53ff53, 0x75ff75, 0x98ff98, 0xbaffba,
    0x007f7f, 0x00b2b2, 0x00e3e3, 0x00ffff, 0x53ffff, 0x75ffff, 0x98ffff, 0xbaffff,
    0x00007f, 0x0000b2, 0x0000e3, 0x0000ff, 0x5353ff, 0x7575ff, 0x9898ff, 0xbabaff,
    0x7f007f, 0xb200b2, 0xe300e3, 0xff00ff, 0xff53ff, 0xff75ff, 0xff98ff, 0xffbaff,
]
# Copied from the same place; the two mid levels are what the source art actually uses for its anti-aliased
# edges.
ALPHA = [0, 85, 170, 255]


def nearest_colour(rgb):
    r, g, b = rgb
    best, best_d = 1, 1 << 30
    for i in range(1, len(PALETTE)):
        pr, pg, pb = (PALETTE[i] >> 16) & 0xFF, (PALETTE[i] >> 8) & 0xFF, PALETTE[i] & 0xFF
        d = (pr - r) ** 2 + (pg - g) ** 2 + (pb - b) ** 2
        if d < best_d:
            best, best_d = i, d
    return best


def quantise(image):
    """RGBA image -> the indexed bytes Charta's CardImage expects, at the image's own size."""
    image = image.convert("RGBA")
    out = bytearray()
    for y in range(image.height):
        for x in range(image.width):
            r, g, b, a = image.getpixel((x, y))
            if a < 8:
                out.append(0)
                continue
            a_index = 1 if a < 128 else (2 if a < 214 else 3)
            out.append((a_index << 6) | nearest_colour((r, g, b)))
    return bytes(out)


def write_blob(path, data, width, height):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as handle:
        handle.write(MAGIC)
        handle.write(struct.pack(">II", width, height))
        handle.write(gzip.compress(data, mtime=0))


def write_png(path, image):
    """The same art as an ordinary full-colour PNG, with no palette step at all."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    image.convert("RGBA").save(path, optimize=True)


def copy_file(source, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    shutil.copyfile(source, path)


def u(*values):
    """Logical (25x35-grid) coordinates -> canvas pixels."""
    return [v * SCALE for v in values]


def P(*xy):
    """A flat x,y,... list on the logical grid -> canvas point pairs."""
    it = iter(xy)
    return [(x * SCALE, y * SCALE) for x, y in zip(it, it)]


def load_raw(name):
    """Loads ``SRC/name`` at its own size -- no resampling. This is the "ship it as drawn" path."""
    with Image.open(os.path.join(SRC, name)) as handle:
        return handle.convert("RGBA")


def new_card():
    return Image.new("RGBA", (CARD_W, CARD_H), (0, 0, 0, 0))


# ----------------------------------------------------------------------------- drawing helpers ---

GOOD_BG = (38, 74, 138, 255)
EVIL_BG = (140, 38, 44, 255)
VOTE_BG = (58, 58, 72, 255)


def frame(draw, colour, bg):
    """A 1-unit border and a 1-unit inset line, the shape every role card in the pack shares."""
    draw.rectangle([0, 0, CARD_W - 1, CARD_H - 1], fill=bg, outline=colour, width=SCALE)
    draw.rectangle(u(2, 2, UNIT_W - 3, UNIT_H - 3), outline=colour, width=SCALE)


def blend(bg, colour, alpha):
    return tuple(int(bg[i] * (1 - alpha) + colour[i] * alpha) for i in range(3)) + (255,)


def placeholder(bg, accent):
    """A faction-coloured role card with a coloured banner, a diamond and a role's initial."""
    card = new_card()
    draw = ImageDraw.Draw(card)
    frame(draw, blend(bg, (255, 255, 255), 0.55), bg)
    draw.rectangle(u(3, 3, UNIT_W - 4, 9), fill=accent)
    draw.rectangle(u(3, 26, UNIT_W - 4, 31), fill=blend(bg, (0, 0, 0), 0.35))
    draw.polygon(P(12, 12, 19, 18, 12, 24, 5, 18), fill=blend(bg, (255, 255, 255), 0.20))
    draw.line(P(12, 14, 17, 18, 12, 22, 7, 18, 12, 14), fill=accent, width=SCALE)
    draw.rectangle(u(11, 17, 13, 19), fill=accent)
    return card


# Role cards: name -> (suit, chinese art, english art). Both language files are shipped for every role:
# the pack has drawn all eight now, so no entry falls back to a placeholder or to the other language.
#
# The two plain roles share their faction's everyday mob on purpose -- one villager is any other villager,
# and there is nothing about a plain minion for the art to tell apart. The old Avalon art at the source
# root (``kind.png``, ``bad.png``) is unreferenced.
ROLE_CARDS = {
    "merlin": ("good", "zh_cn/allay.png", "en_us/allay.png"),
    "percival": ("good", "zh_cn/librarian.png", "en_us/librarian.png"),
    "loyalist": ("good", "zh_cn/villager.png", "en_us/villager.png"),
    "assassin": ("evil", "zh_cn/creeper.png", "en_us/creeper.png"),
    "morgana": ("evil", "zh_cn/illusioner.png", "en_us/illusioner.png"),
    "mordred": ("evil", "zh_cn/ender_dragon.png", "en_us/ender_dragon.png"),
    "oberon": ("evil", "zh_cn/phantom.png", "en_us/phantom.png"),
    "minion": ("evil", "zh_cn/zombie.png", "en_us/zombie.png"),
}

# Vote cards: the pack's own 赞成/反对 card faces, shipped verbatim (325x455, exactly the 25x35 card
# canvas at SCALE=13, so the blob and the full-colour copy are both 1:1).
VOTE_CARDS = {
    "vote_yes": "common/vote_yes.png",
    "vote_no": "common/vote_no.png",
}

# Quest cards: the pack's own "finished quest"/"unfinished quest" stamps, shipped as-is. Note the
# `quest` suffix -- `common/finish.png` and `common/unfinish.png` are the *previous* pass at these two
# (byte-for-byte what the table showed before), and only `finishquest`/`unfinishquest` are the new art.
QUEST_CARDS = {
    "quest_success": "common/finishquest.png",
    "quest_fail": "common/unfinishquest.png",
}

# The card back and the deck's box texture -- one image, the same one Charta shows on both.
DECK_BACK = "common/back.png"

# The pack's own icon: the mod icon, and the game-select button (the table screen's picker).
ICON = "common/icon.png"

# The pack's own leader crown, shipped verbatim. A square 325x325 card, framed like the team marker,
# so the screen draws the whole file into a square box -- see AvalonScreen's CROWN_SRC/CROWN_W.
CROWN = "common/crown.png"

# The table's two props are also cards in the deck, so the deck preview shows the whole piece: each is
# centred, at its own size, on a card-shaped canvas -- never resampled, exactly like every other copy in
# this script. They are the same files the table draws, so the crown in the deck preview is the crown on
# the leader's plate.
PROP_CARDS = {
    "token/crown": "common/crown.png",
    "token/iron_nugget": "common/iron_nugget.png",
}

# The quest card proper: a 16:9 board piece the screen draws centred (see AvalonScreen#renderTaskCard).
# Shipped verbatim at full size and blitted 1:1, so it is never resampled.
TASK_CARD = {
    "task_card.png": "zh_cn/任务卡.png",
    "task_card_en_us.png": "en_us/任务卡.png",
}

# The same board, drawn per table size. The printed board carries that table's five quest sizes -- a
# six-player game sends 2/3/4/3/4 where a five-player one sends 2/3/2/3/3 -- so a size whose board has
# been drawn gets a file of its own, named for the size it belongs to; the screen prefers it over the
# general board above, and keeps using the general one for any size with no art yet. Both languages.
TASK_CARD_SIZED = {
    "task_card_6.png": "zh_cn/任务卡6.png",
    "task_card_6_en_us.png": "en_us/任务卡6.png",
    "task_card_7.png": "zh_cn/任务卡7.png",
    "task_card_7_en_us.png": "en_us/任务卡7.png",
    "task_card_8.png": "zh_cn/任务卡8.png",
    "task_card_8_en_us.png": "en_us/任务卡8.png",
    "task_card_9.png": "zh_cn/任务卡9.png",
    "task_card_9_en_us.png": "en_us/任务卡9.png",
    "task_card_10.png": "zh_cn/任务卡10.png",
    "task_card_10_en_us.png": "en_us/任务卡10.png",
}
# A free-standing marker for "how many missions", shown by the tracker. The pack drew it as a square
# 325x325 card in this pass; the screen draws it square onto a notch of the board.
MISSION_MARKER = "common/iron_nugget.png"

# The outcome seals: square wax-stamp pieces pressed onto a board slot once that mission resolves
# (``finish`` on a success, ``unfinish`` on a sabotage). Note these are *not* the quest cards above --
# they are a separate, square pair, shipped verbatim into the GUI textures at their own size.
QUEST_SEALS = {
    "task_finish.png": "common/finish.png",
    "task_unfinish.png": "common/unfinish.png",
}


def pad_to_card(image):
    """A square piece of art centred on a card-shaped canvas, at its own size and never resampled.

    The props (and the pack's other square pieces) are drawn on a 1:1 canvas, while a card is 25x35 and
    every card texture is stretched over that box. Scaling the square to fill it would reshape the art's
    own frame -- the notched corners would come out rectangular -- so the art is left alone and the
    margins are made transparent instead: in the deck preview it reads as a token card with an inset
    picture, which is what it is.
    """
    canvas = new_card()
    canvas.paste(image, ((CARD_W - image.width) // 2, (CARD_H - image.height) // 2))
    return canvas


def face_card(bg, accent, kind):
    """A vote card: a big centred mark, so a face-down pile of them still reads."""
    card = new_card()
    draw = ImageDraw.Draw(card)
    frame(draw, blend(bg, (255, 255, 255), 0.5), bg)
    if kind == "yes":
        draw.line(P(7, 18, 11, 23), fill=accent, width=2 * SCALE)
        draw.line(P(11, 23, 18, 13), fill=accent, width=2 * SCALE)
    else:
        draw.line(P(8, 13, 17, 23), fill=accent, width=2 * SCALE)
        draw.line(P(17, 13, 8, 23), fill=accent, width=2 * SCALE)
    return card


# ----------------------------------------------------------------------------- suit glyphs ---

def suit_glyph(name):
    """A 13x13-unit faction emblem, drawn transparent so it takes the deck's suit colour."""
    img = Image.new("RGBA", (SUIT_W, SUIT_H), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    white = (255, 255, 255, 255)
    if name == "good":
        draw.polygon(P(3, 2, 9, 2, 11, 5, 6, 11, 1, 5), fill=white)
        draw.polygon(P(4, 4, 8, 4, 6, 8), fill=(0, 0, 0, 0))
    elif name == "evil":
        draw.polygon(P(6, 1, 8, 3, 6, 9, 4, 3), fill=white)
        draw.rectangle(u(2, 8, 10, 9), fill=white)
        draw.rectangle(u(5, 9, 7, 12), fill=white)
    elif name == "vote":
        draw.rectangle(u(1, 1, 11, 11), outline=white, width=SCALE)
        draw.line(P(3, 6, 5, 9), fill=white, width=2 * SCALE)
        draw.line(P(5, 9, 9, 4), fill=white, width=2 * SCALE)
    elif name == "quest":
        # A flag on a pole.
        draw.rectangle(u(3, 1, 4, 12), fill=white)
        draw.polygon(P(5, 2, 11, 4, 5, 6), fill=white)
    elif name == "token":
        # The two props' own mark: a three-point crown over a band, the leader and the marker's colour.
        draw.polygon(P(2, 9, 2, 5, 4, 7, 6, 3, 8, 7, 10, 5, 10, 9), fill=white)
        draw.rectangle(u(2, 10, 10, 11), fill=white)
    return img


def crown_icon():
    """The leader's crown: a gold three-point crown with gems, drawn on transparent so it floats."""
    img = Image.new("RGBA", (64, 56), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    gold = (236, 199, 92, 255)
    light = (255, 232, 150, 255)
    dark = (150, 112, 40, 255)
    gem = (206, 62, 62, 255)
    # Three peaks with two valleys, then a band across the base.
    draw.polygon([(6, 46), (6, 22), (17, 34), (32, 8), (47, 34), (58, 22), (58, 46)], fill=gold)
    draw.rectangle([6, 34, 58, 48], fill=gold)
    draw.rectangle([6, 44, 58, 48], fill=dark)
    draw.rectangle([6, 34, 58, 36], fill=light)
    # A pearl on each peak and a gem in the band.
    for cx in (6, 32, 58):
        draw.ellipse([cx - 5, 4 if cx == 32 else 16, cx + 5, 14 if cx == 32 else 26], fill=light)
    for cx in (16, 32, 48):
        draw.ellipse([cx - 4, 37, cx + 4, 44], fill=gem)
    return img


def flag_icon():
    """The team marker: a small pennant on a pole, dragged into a seat's zone to pick that seat."""
    img = Image.new("RGBA", (48, 56), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    pole = (78, 60, 44, 255)
    cloth = (214, 178, 88, 255)
    trim = (255, 236, 160, 255)
    draw.rectangle([8, 4, 12, 52], fill=pole)
    draw.ellipse([5, 0, 15, 8], fill=trim)
    draw.polygon([(12, 8), (44, 16), (12, 30)], fill=cloth)
    draw.polygon([(12, 8), (44, 16), (12, 16)], fill=trim)
    return img


def deck_back():
    """The card back: the same navy as the deck icon, with a centred crown."""
    card = new_card()
    draw = ImageDraw.Draw(card)
    navy = (22, 34, 66, 255)
    gold = (214, 178, 88, 255)
    draw.rectangle([0, 0, CARD_W - 1, CARD_H - 1], fill=navy, outline=gold, width=SCALE)
    draw.rectangle(u(2, 2, UNIT_W - 3, UNIT_H - 3), outline=blend(navy, gold, 0.5), width=SCALE)
    for y in range(4, UNIT_H - 4, 3):
        for x in range(4, UNIT_W - 4, 3):
            draw.rectangle([x * SCALE, y * SCALE, (x + 1) * SCALE - 1, (y + 1) * SCALE - 1],
                           fill=blend(navy, gold, 0.35))
    draw.polygon(P(8, 15, 10, 11, 12, 15, 14, 11, 16, 15, 16, 20, 8, 20), fill=gold)
    draw.rectangle(u(8, 21, 16, 22), fill=blend(navy, gold, 0.6))
    draw.line(P(10, 24, 14, 24, 12, 28, 10, 24), fill=gold, width=SCALE)
    return card


def main():
    card_dir = os.path.join(RES, "data", "myavalon", "images", "card")
    suit_dir = os.path.join(RES, "data", "myavalon", "images", "suit")
    deck_dir = os.path.join(RES, "data", "myavalon", "images", "deck")

    def emit(rel, image, blob_dir, ext, source=None, english=None):
        """Writes a blob plus its full-colour texture. ``source``/``english`` are the shipped art files: when
        present the PNG is copied verbatim (bit-for-bit the author's file) instead of being re-encoded."""
        write_blob(os.path.join(blob_dir, rel + "." + ext), quantise(image), image.width, image.height)
        if source is not None:
            copy_file(source, os.path.join(FULL_DIR, rel + ".png"))
        else:
            write_png(os.path.join(FULL_DIR, rel + ".png"), image)
        if english is not None:
            copy_file(english, os.path.join(FULL_DIR, "en_us", rel + ".png"))
        else:
            # A role that lost its english file must not keep last run's copy, or the english client
            # would render stale art while the chinese one shows the new one.
            stale = os.path.join(FULL_DIR, "en_us", rel + ".png")
            if os.path.exists(stale):
                os.remove(stale)
        print(rel)

    for name, (faction, art, english) in ROLE_CARDS.items():
        source = os.path.join(SRC, art) if isinstance(art, str) else None
        english_source = os.path.join(SRC, english) if english is not None else None
        image = load_raw(art) if isinstance(art, str) else art
        emit(os.path.join("role", name), image, card_dir, "mccard", source, english_source)

    for name, art in VOTE_CARDS.items():
        emit(name, load_raw(art), card_dir, "mccard", os.path.join(SRC, art))

    for name, art in QUEST_CARDS.items():
        source = os.path.join(SRC, art)
        emit(name, load_raw(art), card_dir, "mccard", source)

    # The table's props as cards: centred on a card canvas, so the art is copied at its own size (the
    # blob and the full-colour PNG both keep the source pixels exactly).
    for name, art in PROP_CARDS.items():
        emit(name, pad_to_card(load_raw(art)), card_dir, "mccard", None)

    # Suit marks are all small glyphs; the quest *card* is a separate 16:9 piece (see TASK_CARD).
    for name in ("good", "evil", "vote", "quest", "token"):
        emit(name, suit_glyph(name), suit_dir, "mcsuit", None)

    # The deck box and card back come straight from the pack now; ``deck_back()`` is only the stand-in
    # that was used before it shipped one.
    emit("avalon", load_raw(DECK_BACK), deck_dir, "mccard", os.path.join(SRC, DECK_BACK))

    # The centring quest card, straight into the GUI textures, verbatim -- the general one and the boards
    # drawn for individual table sizes.
    for out, src in {**TASK_CARD, **TASK_CARD_SIZED}.items():
        copy_file(os.path.join(SRC, src), os.path.join(GUI_DIR, out))

    # The mission-count marker, straight into the GUI textures.
    copy_file(os.path.join(SRC, MISSION_MARKER), os.path.join(GUI_DIR, "mission_marker.png"))

    # The mission outcome seals, straight into the GUI textures at their own 320x320.
    for out, src in QUEST_SEALS.items():
        copy_file(os.path.join(SRC, src), os.path.join(GUI_DIR, out))

    # The pack's own crown, straight into the GUI textures -- the whole square card, frame and all.
    # ``crown_icon()`` above is only the stand-in that was used before it shipped one.
    copy_file(os.path.join(SRC, CROWN), os.path.join(GUI_DIR, "crown.png"))

    # The draggable team marker, straight from the pack (325x325), verbatim.
    copy_file(os.path.join(SRC, "common/team.png"), os.path.join(GUI_DIR, "team_flag.png"))

    # The pack's icon, for the mod icon and the game button. Copied verbatim -- the pack draws it at the
    # size it wants, and nothing here is allowed to resample.
    os.makedirs(os.path.join(RES, "assets", "myavalon"), exist_ok=True)
    copy_file(os.path.join(SRC, ICON), os.path.join(RES, "assets", "myavalon", "icon.png"))
    os.makedirs(os.path.join(GUI_DIR, "game"), exist_ok=True)
    copy_file(os.path.join(SRC, ICON), os.path.join(GUI_DIR, "game", "avalon.png"))
    print("icon + game button + mission marker")


if __name__ == "__main__":
    main()
