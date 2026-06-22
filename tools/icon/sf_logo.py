from PIL import Image, ImageDraw, ImageFont

SIZE = 1024
BG = (63, 61, 86, 255)      # #3f3d56 — zelfde als de dashboard-favicon
FG = (255, 255, 255, 255)
RADIUS = int(SIZE * 0.235)  # ~Apple squircle / favicon-afronding

img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
d.rounded_rectangle([0, 0, SIZE - 1, SIZE - 1], radius=RADIUS, fill=BG)

font = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial Bold.ttf", 470)
text = "SF"
# Centreer op basis van de echte glyph-bounding-box.
bbox = d.textbbox((0, 0), text, font=font)
tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
x = (SIZE - tw) / 2 - bbox[0]
y = (SIZE - th) / 2 - bbox[1]
d.text((x, y), text, font=font, fill=FG)

img.save("/tmp/sf-logo-1024.png")
print("logo geschreven:", img.size)
