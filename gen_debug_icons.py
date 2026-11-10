"""Generate distinct launcher icons for the debug source set of PiscesPlayer.

Run from anywhere:
    python gen_debug_icons.py [--colorway amber|violet|coral] [--subject-hue 325]

The debug variant already uses a separate application id (`.debug`) and app name
(`PiscesPlayer [D]`). This script adds the visual half of that separation by
recoloring both the icon background and the navy subject, without touching the
main sources.

Why the foreground is rewritten
-------------------------------
`ic_launcher_foreground` is not a transparent cut-out: it already contains the
full cyan-to-navy gradient as an opaque layer (99.7% of its pixels are opaque,
~71% match `ic_launcher_background`). Recoloring only the background layer is
therefore invisible. We rebuild both layers:

* the background layer becomes the chosen gradient, sampled at the same vertical
  stops as the release gradient so its light-to-dark curve is preserved;
* the foreground keeps every subject pixel (white fish, navy play disc, eye,
  outline) and swaps only pixels close to the release background colour. Pixels
  are blended by their distance from that reference, so anti-aliased edges and
  the fish's cyan shading transition smoothly into the new colourway. The dark
  navy belly and tail fin are close to the dark end of the background gradient,
  so a morphological mask protects the connected subject region from being
  recoloured by distance alone.

Why the subject is recoloured too
---------------------------------
Launchers crop adaptive icons to a circle (72 dp of the 108 dp canvas). Inside
that circle the subject fills 83.7% of the visible pixels and the background only
16.3%; recoloring the background alone leaves an average colour delta of just
27.6 against the release icon, with the subject region at 5.0. Rotating the hue
of the navy fish/belly/disc/tail fin moves the difference into the centre where
it survives every mask shape. Only the HSV hue channel is replaced, so S/V and
therefore every gradient, vein and highlight are preserved; the white fish stays
pure white.

Legacy square/round icons are recomposited from the two new layers and reuse the
release masks, so debug and release keep the same silhouette.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter
from scipy import ndimage

ROOT = Path(__file__).resolve().parent
MAIN_RES = ROOT / "app" / "src" / "main" / "res"
DEBUG_RES = ROOT / "app" / "src" / "debug" / "res"

# Legacy launcher icon edge length per density bucket.
ICON_SIZES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

# Five vertical stops (top -> bottom). Change this set to re-theme the debug icon.
COLORWAYS = {
    "amber": (
        (255, 215, 122),
        (245, 168, 60),
        (224, 122, 31),
        (154, 66, 18),
        (74, 31, 9),
    ),
    "violet": (
        (229, 219, 255),
        (183, 156, 240),
        (138, 91, 214),
        (76, 42, 156),
        (36, 17, 85),
    ),
    "coral": (
        (255, 179, 160),
        (244, 122, 99),
        (216, 69, 58),
        (138, 33, 30),
        (63, 13, 18),
    ),
}

# Hue (degrees) for the navy subject. 325 (magenta) is ~90 average colour delta
# against the release icon inside the launcher's visible circle.
DEFAULT_SUBJECT_HUE = 325

# Colour distance to the release background. Below LOW = pure background,
# above HIGH = pure subject. Measured on the xxhdpi assets: background 4-45,
# subject (white fish / navy disc) > 90.
DIST_LOW = 18.0
DIST_HIGH = 95.0

# Seed for the subject mask: anything clearly off the background gradient.
SUBJECT_SEED_DIST = 55.0

# The adaptive icon visible circle is 72 dp of the 108 dp canvas.
VISIBLE_CIRCLE_RATIO = 72.0 / 108.0
MIN_CIRCLE_DELTA = 80.0


def gradient(stops: tuple[tuple[int, int, int], ...], width: int, height: int) -> np.ndarray:
    """Build a vertical gradient with the same stop positions as the release one."""
    palette = np.asarray(stops, dtype=np.float32)
    t = np.linspace(0.0, len(palette) - 1, height, dtype=np.float32)
    lo = np.clip(np.floor(t).astype(int), 0, len(palette) - 2)
    frac = (t - lo)[:, None]
    rows = palette[lo] * (1.0 - frac) + palette[lo + 1] * frac
    return np.broadcast_to(rows[:, None, :], (height, width, 3)).copy()


def protected_subject(distance: np.ndarray) -> np.ndarray:
    """Find the connected subject region (incl. dark belly and tail fin).

    Distance alone cannot separate the navy belly from the navy bottom of the
    background gradient. Closing + hole filling recovers the fish as one solid
    region; only the connected component containing the icon centre is kept.
    """
    height, width = distance.shape
    seed = distance > SUBJECT_SEED_DIST
    closed = ndimage.binary_closing(seed, structure=np.ones((5, 5)))
    filled = ndimage.binary_fill_holes(closed)
    labels, _ = ndimage.label(filled)
    centre = labels[height // 2, width // 2]
    if centre == 0:
        return np.zeros_like(seed)
    core = ndimage.binary_dilation(labels == centre, iterations=2)
    # The bright upper background recolours cleanly; keep protection off it.
    core[: int(height * 0.35), :] = False
    return core


def subject_masks(
    foreground: Image.Image, background_ref: Image.Image
) -> tuple[np.ndarray, np.ndarray]:
    """Split the icon into navy-subject and white-subject pixel masks."""
    fg = np.asarray(foreground.convert("RGBA"), dtype=np.float32)
    ref = np.asarray(background_ref.convert("RGB"), dtype=np.float32)
    distance = np.sqrt(((fg[..., :3] - ref) ** 2).sum(axis=2))
    subject = (protected_subject(distance) | (distance > DIST_HIGH)) & (fg[..., 3] > 128)
    white = subject & (fg[..., :3].min(axis=2) > 200)
    return subject & ~white, white


def shift_subject_hue(image: Image.Image, mask: np.ndarray, hue_deg: int) -> Image.Image:
    """Rotate only the hue of masked pixels, keeping S/V (and thus all texture)."""
    original_rgb = np.asarray(image.convert("RGB"))
    hsv = np.asarray(Image.fromarray(original_rgb, "RGB").convert("HSV")).copy()
    hsv[..., 0] = np.where(mask, int(round(hue_deg / 360.0 * 255)), hsv[..., 0])
    rotated_rgb = np.asarray(Image.fromarray(hsv, "HSV").convert("RGB"))
    # HSV round-trips quantise to 8 bits; keep every unmasked byte untouched.
    rgb = np.where(mask[..., None], rotated_rgb, original_rgb)
    return Image.fromarray(
        np.dstack([rgb, np.asarray(image.getchannel("A"))]), "RGBA"
    )


def circle_delta(release_rgb: np.ndarray, debug_rgb: np.ndarray) -> float:
    """Average RGB distance inside the launcher's visible circle."""
    height, width = release_rgb.shape[:2]
    yy, xx = np.mgrid[:height, :width]
    circle = (xx - width / 2) ** 2 + (yy - height / 2) ** 2 <= (
        width * VISIBLE_CIRCLE_RATIO / 2
    ) ** 2
    return float(
        np.sqrt(((release_rgb[circle] - debug_rgb[circle]) ** 2).sum(axis=1)).mean()
    )


def recolor(
    foreground: Image.Image,
    background_ref: Image.Image,
    new_background_rgb: np.ndarray,
) -> tuple[Image.Image, dict[str, float]]:
    """Replace background-coloured pixels while preserving the icon subject."""
    fg = np.asarray(foreground.convert("RGBA"), dtype=np.float32)
    ref = np.asarray(background_ref.convert("RGB"), dtype=np.float32)

    distance = np.sqrt(((fg[..., :3] - ref) ** 2).sum(axis=2))
    subject = np.clip((distance - DIST_LOW) / (DIST_HIGH - DIST_LOW), 0.0, 1.0)
    soft = Image.fromarray((subject * 255).astype(np.uint8)).filter(
        ImageFilter.GaussianBlur(0.6)
    )
    weight = np.asarray(soft, dtype=np.float32)[..., None] / 255.0
    # Feathering must never bleed into the subject itself.
    weight[distance > DIST_HIGH] = 1.0
    weight[protected_subject(distance)] = 1.0

    rgb = new_background_rgb * (1.0 - weight) + fg[..., :3] * weight
    out = np.concatenate(
        [np.clip(rgb, 0, 255), fg[..., 3:4]], axis=2
    ).astype(np.uint8)

    opaque = fg[..., 3] >= 200
    subject_pixels = opaque & (distance > DIST_HIGH)
    subject_delta = float(
        np.abs(out[..., :3][subject_pixels] - fg[..., :3][subject_pixels]).mean()
    ) if subject_pixels.any() else 0.0
    stats = {
        "recolor_ratio": float((1.0 - weight[..., 0][opaque]).mean()),
        "subject_delta": subject_delta,
    }
    return Image.fromarray(out, "RGBA"), stats


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--colorway", choices=sorted(COLORWAYS), default="amber")
    parser.add_argument("--subject-hue", type=int, default=DEFAULT_SUBJECT_HUE)
    args = parser.parse_args()
    if not 0 <= args.subject_hue < 360:
        parser.error("--subject-hue must be in [0, 360)")
    stops = COLORWAYS[args.colorway]

    for density, legacy_size in ICON_SIZES.items():
        main_dir = MAIN_RES / f"mipmap-{density}"
        out_dir = DEBUG_RES / f"mipmap-{density}"
        out_dir.mkdir(parents=True, exist_ok=True)

        background_ref = Image.open(main_dir / "ic_launcher_background.webp").convert("RGBA")
        foreground = Image.open(main_dir / "ic_launcher_foreground.webp").convert("RGBA")
        width, height = background_ref.size
        new_rgb = gradient(stops, width, height)

        new_background = Image.fromarray(
            np.concatenate(
                [np.clip(new_rgb, 0, 255).astype(np.uint8), np.asarray(background_ref)[..., 3:4]],
                axis=2,
            ),
            "RGBA",
        )
        blue, white = subject_masks(foreground, background_ref)
        new_foreground, stats = recolor(foreground, background_ref, new_rgb)
        new_foreground = shift_subject_hue(new_foreground, blue, args.subject_hue)

        release_rgb = np.asarray(foreground.convert("RGB"), dtype=np.float32)
        result_rgb = np.asarray(new_foreground.convert("RGB"), dtype=np.float32)
        white_delta = float(
            np.abs(result_rgb[white] - release_rgb[white]).mean()
        ) if white.any() else 0.0
        delta = circle_delta(release_rgb, result_rgb)

        assert stats["subject_delta"] < 2.0, f"{density}: subject pixels were recoloured"
        assert stats["recolor_ratio"] > 0.5, f"{density}: background was not recoloured"
        assert white_delta == 0.0, f"{density}: white fish changed colour"
        assert delta >= MIN_CIRCLE_DELTA, f"{density}: icons too similar ({delta:.1f})"

        new_background.save(out_dir / "ic_launcher_background.webp", "WEBP", quality=90)
        new_foreground.save(out_dir / "ic_launcher_foreground.webp", "WEBP", quality=90)

        # Legacy icons show the complete adaptive icon at the launcher's fixed size.
        full = Image.alpha_composite(new_background, new_foreground).resize(
            (legacy_size, legacy_size), Image.Resampling.LANCZOS
        )
        for name in ("ic_launcher", "ic_launcher_round"):
            mask = (
                Image.open(main_dir / f"{name}.webp")
                .convert("RGBA")
                .getchannel("A")
                .resize((legacy_size, legacy_size), Image.Resampling.LANCZOS)
            )
            icon = full.copy()
            icon.putalpha(mask)
            icon.save(out_dir / f"{name}.webp", "WEBP", quality=90)

        print(
            f"{density}: {legacy_size}x{legacy_size}  "
            f"recolor={stats['recolor_ratio']:.1%}  "
            f"white_delta={white_delta:.2f}  circle_delta={delta:.1f}"
        )

    print(
        f"Debug icons ({args.colorway}, subject hue {args.subject_hue}) written to {DEBUG_RES}"
    )


if __name__ == "__main__":
    main()
