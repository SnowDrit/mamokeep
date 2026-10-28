#!/usr/bin/env python3
"""Turn generated vehicle line art into normalized transparent PNG and SVG assets."""

from __future__ import annotations

import argparse
from pathlib import Path
import tempfile

from PIL import Image, ImageChops, ImageFilter, ImageOps
import vtracer


ROOT = Path(__file__).resolve().parents[2]


def extracted_ink(source: Path, thicken: int) -> Image.Image:
    image = Image.open(source).convert("RGBA")
    gray = ImageOps.grayscale(image.convert("RGB"))
    darkness = gray.point(
        lambda value: 0 if value >= 218 else min(255, round((218 - value) * 255 / 178))
    )
    alpha = ImageChops.multiply(darkness, image.getchannel("A"))
    if thicken > 1:
        alpha = alpha.filter(ImageFilter.MaxFilter(thicken))
    result = Image.new("RGBA", image.size, (0, 0, 0, 0))
    result.putalpha(alpha)
    return result


def normalized_canvas(image: Image.Image, kind: str) -> Image.Image:
    alpha = image.getchannel("A")
    bbox = alpha.getbbox()
    if bbox is None:
        raise ValueError("No visible ink found")
    cropped = image.crop(bbox)

    if kind == "profile":
        # A fixed 16:9 canvas made short/tall body types (hatchback, SUV,
        # minivan) look much smaller than long sedans inside the same Compose
        # slot. Keep only a small equal padding around the actual contour so
        # every profile uses the available button area.
        scale = min(1480 / cropped.width, 800 / cropped.height)
        resized = cropped.resize(
            (round(cropped.width * scale), round(cropped.height * scale)),
            Image.Resampling.LANCZOS,
        )
        padding = 30
        canvas = Image.new(
            "RGBA",
            (resized.width + padding * 2, resized.height + padding * 2),
            (0, 0, 0, 0),
        )
        canvas.alpha_composite(resized, (padding, padding))
        return canvas

    canvas_size = (1536, 1024)
    target_size = (1420, 900)

    scale = min(target_size[0] / cropped.width, target_size[1] / cropped.height)
    resized = cropped.resize(
        (round(cropped.width * scale), round(cropped.height * scale)),
        Image.Resampling.LANCZOS,
    )
    canvas = Image.new("RGBA", canvas_size, (0, 0, 0, 0))
    x = (canvas.width - resized.width) // 2
    y = (canvas.height - resized.height) // 2
    canvas.alpha_composite(resized, (x, y))
    return canvas


def write_svg(png: Path, svg: Path) -> None:
    image = Image.open(png).convert("RGBA")
    alpha = image.getchannel("A")
    trace = Image.new("L", image.size, 255)
    trace.paste(ImageOps.invert(alpha.point(lambda value: 255 if value >= 80 else 0)))

    with tempfile.NamedTemporaryFile(suffix=".png") as temporary:
        trace.save(temporary.name)
        vtracer.convert_image_to_svg_py(temporary.name, str(svg))

    text = svg.read_text()
    svg_start = text.find("<svg")
    if svg_start < 0:
        raise ValueError(f"Tracer output has no <svg> root: {svg}")
    marker = text.find(">", svg_start)
    filter_def = (
        '<defs><filter id="ink" color-interpolation-filters="sRGB">'
        '<feColorMatrix type="matrix" values="0 0 0 0 0  0 0 0 0 0  '
        '0 0 0 0 0  -0.333333 -0.333333 -0.333333 0 1"/>'
        '</filter></defs><g filter="url(#ink)">'
    )
    text = text[: marker + 1] + filter_def + text[marker + 1 :]
    text = text.replace("</svg>", "</g></svg>")
    svg.write_text(text)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("key")
    parser.add_argument("kind", choices=("profile", "three-quarter"))
    parser.add_argument("--thicken", type=int, default=7, choices=(1, 3, 5, 7, 9, 11))
    args = parser.parse_args()

    suffix = "profile" if args.kind == "profile" else "three_quarter"
    png = ROOT / "app/src/main/res/drawable-nodpi" / f"car_{args.key}_detailed_{suffix}.png"
    svg_name = "profile" if args.kind == "profile" else "three-quarter"
    svg = ROOT / "design/vehicle-silhouettes" / f"{args.key}-{svg_name}.svg"

    result = normalized_canvas(extracted_ink(args.source, args.thicken), args.kind)
    result.save(png, optimize=True)
    write_svg(png, svg)
    print(png.relative_to(ROOT))
    print(svg.relative_to(ROOT))


if __name__ == "__main__":
    main()
