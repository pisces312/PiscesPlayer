"""Generate red-tinted launcher icons for the debug source set of PiscesPlayer.

Run from anywhere:  python gen_debug_icons.py

The debug variant already uses a separate application id (`.debug`) and app name
(`PiscesPlayer [D]`). This script adds the visual half of that separation by
writing a red-tinted copy of the adaptive icon background into
`app/src/debug/res`, so debug and release builds are distinguishable on the
launcher without touching the main sources.

Only `ic_launcher_background` and the legacy square/round icons are written. The
foreground and the `mipmap-anydpi-v26` adaptive icon XML are inherited from the
main source set, so nothing is duplicated unnecessarily.
"""

from pathlib import Path

from PIL import Image

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


def tint_red(image: Image.Image) -> Image.Image:
    """Tint an RGBA image red while preserving its alpha channel."""
    r, g, b, a = image.split()
    r = r.point(lambda i: min(255, int(i * 1.2) + 40))
    g = g.point(lambda i: int(i * 0.3))
    b = b.point(lambda i: int(i * 0.3))
    return Image.merge("RGBA", (r, g, b, a))


def main() -> None:
    for density, legacy_size in ICON_SIZES.items():
        out_dir = DEBUG_RES / f"mipmap-{density}"
        out_dir.mkdir(parents=True, exist_ok=True)

        main_dir = MAIN_RES / f"mipmap-{density}"
        background = Image.open(main_dir / "ic_launcher_background.webp").convert("RGBA")
        foreground = Image.open(main_dir / "ic_launcher_foreground.webp").convert("RGBA")
        debug_background = tint_red(background)
        debug_background.save(out_dir / "ic_launcher_background.webp", "WEBP", quality=90)

        # Legacy icons show the complete adaptive icon at the launcher's fixed size.
        full = Image.alpha_composite(debug_background, foreground).resize(
            (legacy_size, legacy_size), Image.Resampling.LANCZOS
        )

        # Reuse the release icon masks so debug and release keep the same silhouette.
        square = full.copy()
        square.putalpha(
            Image.open(main_dir / "ic_launcher.webp")
            .convert("RGBA")
            .getchannel("A")
            .resize((legacy_size, legacy_size), Image.Resampling.LANCZOS)
        )
        round_icon = full.copy()
        round_icon.putalpha(
            Image.open(main_dir / "ic_launcher_round.webp")
            .convert("RGBA")
            .getchannel("A")
            .resize((legacy_size, legacy_size), Image.Resampling.LANCZOS)
        )

        square.save(out_dir / "ic_launcher.webp", "WEBP", quality=90)
        round_icon.save(out_dir / "ic_launcher_round.webp", "WEBP", quality=90)

        print(f"{density}: {legacy_size}x{legacy_size}")

    print(f"Debug icons written to {DEBUG_RES}")


if __name__ == "__main__":
    main()
