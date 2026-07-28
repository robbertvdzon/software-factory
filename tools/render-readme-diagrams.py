#!/usr/bin/env python3
"""Rendert de twee README-diagrammen als PNG (licht + donker).

Waarom geen mermaid meer: GitHub rendert mermaid met een vaste lavendel-stijl en plakt er
zoom-/rotatieknoppen overheen. Een PNG geeft controle over de vormgeving en staat stil.

Twee varianten per diagram, gekoppeld via <picture> + prefers-color-scheme in de README, zodat
het in GitHub's dark mode niet als wit blok oplicht. De achtergrond is transparant en de kleuren
zijn GitHub Primer-tokens, zodat het in beide thema's native oogt.

Alle maten zijn afgeleid van de gemeten tekstbreedte — nooit andersom, anders loopt een label uit
z'n doos zodra iemand de tekst aanpast.

    python3 tools/render-readme-diagrams.py

Schrijft naar docs/images/: flow-light.png, flow-dark.png, night-light.png, night-dark.png.
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parent.parent / "docs" / "images"

TITLE_SIZE, SUB_SIZE = 34, 28
PAD_X, PAD_Y, LINE_GAP = 34, 26, 10
GAP, MARGIN = 34, 26          # tussen dozen, en rond het geheel
ARROW_HEAD = 13

THEMES = {
    "light": {
        "box": "#F6F8FA", "border": "#D0D7DE", "title": "#1F2328", "sub": "#656D76",
        "arrow": "#8C959F", "accent_box": "#FFF8C5", "accent_border": "#D4A72C",
        "accent_title": "#4D2D00", "accent_sub": "#7D4E00",
    },
    "dark": {
        "box": "#161B22", "border": "#30363D", "title": "#E6EDF3", "sub": "#8B949E",
        "arrow": "#6E7681", "accent_box": "#272115", "accent_border": "#9E6A03",
        "accent_title": "#F0D8A8", "accent_sub": "#D3A24C",
    },
}


def _font(size, bold):
    try:
        f = ImageFont.truetype("/System/Library/Fonts/SFNS.ttf", size)
        f.set_variation_by_name("Bold" if bold else "Regular")
        return f
    except Exception:
        name = "Arial Bold.ttf" if bold else "Arial.ttf"
        return ImageFont.truetype(f"/System/Library/Fonts/Supplemental/{name}", size)


TITLE_FONT, SUB_FONT = _font(TITLE_SIZE, True), _font(SUB_SIZE, False)
_MEASURE = ImageDraw.Draw(Image.new("RGBA", (1, 1)))


def text_size(text, font):
    left, top, right, bottom = _MEASURE.textbbox((0, 0), text, font=font)
    return right - left, bottom - top


def lines_of(node):
    """(tekst, font)-paren van een node: vette titel plus optionele gedempte subregels."""
    title, subs = node
    return [(title, TITLE_FONT)] + [(s, SUB_FONT) for s in subs]


def node_size(node):
    sizes = [text_size(t, f) for t, f in lines_of(node)]
    width = max(w for w, _ in sizes) + 2 * PAD_X
    height = sum(h for _, h in sizes) + LINE_GAP * (len(sizes) - 1) + 2 * PAD_Y
    return width, height


def draw_node(draw, rect, node, t, accent=False):
    draw.rounded_rectangle(rect, radius=12, fill=t["accent_box" if accent else "box"],
                           outline=t["accent_border" if accent else "border"], width=2)
    x0, y0, x1, y1 = rect
    cx = (x0 + x1) / 2
    entries = lines_of(node)
    sizes = [text_size(s, f) for s, f in entries]
    total = sum(h for _, h in sizes) + LINE_GAP * (len(entries) - 1)
    y = (y0 + y1) / 2 - total / 2
    for i, ((s, f), (w, h)) in enumerate(zip(entries, sizes)):
        key = ("accent_title" if accent else "title") if i == 0 else ("accent_sub" if accent else "sub")
        top = _MEASURE.textbbox((0, 0), s, font=f)[1]
        draw.text((cx - w / 2, y - top), s, font=f, fill=t[key])
        y += h + LINE_GAP


def draw_arrow(draw, start, end, t, dotted=False):
    (x0, y0), (x1, y1) = start, end
    dx, dy = x1 - x0, y1 - y0
    length = max((dx * dx + dy * dy) ** 0.5, 1)
    ux, uy = dx / length, dy / length
    ex, ey = x1 - ux * ARROW_HEAD, y1 - uy * ARROW_HEAD
    if dotted:
        travelled, step, on = 0.0, 11, 6
        while travelled < length - ARROW_HEAD:
            a = travelled
            b = min(travelled + on, length - ARROW_HEAD)
            draw.line([(x0 + ux * a, y0 + uy * a), (x0 + ux * b, y0 + uy * b)], fill=t["arrow"], width=2)
            travelled += step
    else:
        draw.line([(x0, y0), (ex, ey)], fill=t["arrow"], width=2)
    px, py = -uy, ux
    draw.polygon([(x1, y1), (ex + px * ARROW_HEAD * 0.46, ey + py * ARROW_HEAD * 0.46),
                  (ex - px * ARROW_HEAD * 0.46, ey - py * ARROW_HEAD * 0.46)], fill=t["arrow"])


def layout_row(nodes, top):
    """Rechthoeken voor een rij nodes: elke doos zo breed als z'n eigen tekst, gelijke hoogte."""
    sizes = [node_size(n) for n in nodes]
    height = max(h for _, h in sizes)
    rects, x = [], MARGIN
    for width, _ in sizes:
        rects.append((x, top, x + width, top + height))
        x += width + GAP
    return rects, x - GAP + MARGIN, height


def render(nodes, theme, accent=None, accent_from=()):
    """Eén rij nodes; `accent` hangt er als extra doos onder, met stippellijnen uit `accent_from`."""
    t = THEMES[theme]
    rects, width, height = layout_row(nodes, MARGIN)
    total_height = MARGIN + height + MARGIN
    accent_rect = None
    if accent:
        a_width, a_height = node_size(accent)
        drop = 74
        left = (width - a_width) / 2
        accent_rect = (left, MARGIN + height + drop, left + a_width, MARGIN + height + drop + a_height)
        total_height = accent_rect[3] + MARGIN

    im = Image.new("RGBA", (int(width), int(total_height)), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    for rect, node in zip(rects, nodes):
        draw_node(d, rect, node, t)
    for a, b in zip(rects, rects[1:]):
        draw_arrow(d, (a[2] + 7, MARGIN + height / 2), (b[0] - 5, MARGIN + height / 2), t)
    if accent_rect:
        draw_node(d, accent_rect, accent, t, accent=True)
        # De bronnen staan links en rechts van de accentdoos, dus laat de stippellijnen schuin
        # samenkomen op verdeelde punten van de bovenrand — een rechte lijn omlaag zou bij de
        # buitenste bron naast de doos eindigen.
        span = accent_rect[2] - accent_rect[0]
        for n, i in enumerate(accent_from):
            cx = (rects[i][0] + rects[i][2]) / 2
            target = accent_rect[0] + span * (n + 1) / (len(accent_from) + 1)
            draw_arrow(d, (cx, rects[i][3] + 7), (target, accent_rect[1] - 5), t, dotted=True)
    return im


FLOW = [
    ("Your idea", []),
    ("Refine", ["make it concrete"]),
    ("Plan", ["cut into subtasks"]),
    ("Build", ["review, test, document"]),
    ("Your approval", ["optional"]),
    ("Merge and deploy", []),
]
FLOW_ASK = ("Not sure? It asks you.",
            ["Any step can stop and ask a question.",
             "You answer; that same step carries on."])
NIGHT = [
    ("Every night", ["per project"]),
    ("An audit reads", ["and changes nothing"]),
    ("It writes a report", ["with a score"]),
    ("It proposes", ["at most 1 small story"]),
    ("That story enters", ["the normal pipeline"]),
]


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for theme in THEMES:
        for name, im in (
            ("flow", render(FLOW, theme, accent=FLOW_ASK, accent_from=(1, 2, 3))),
            ("night", render(NIGHT, theme)),
        ):
            path = OUT / f"{name}-{theme}.png"
            im.save(path)
            print(f"{path.relative_to(OUT.parent.parent)}  {im.size}")


if __name__ == "__main__":
    main()
