# AIO VD Editor

A free, open-source video editor for Android — gold/black edition by **AIO HUB PSM**.

CapCut-style editing on your phone: trim, split, merge, speed, filters, text,
stickers, audio, subtitles, transitions — with unlimited blur boxes, blurred
subtitle backgrounds, Myanmar font support, and export in 720p / 1080p / 2K / 4K.

## Features

- **Trim / Split / Merge** — cut clips on the timeline, rearrange segments
- **Blur Box** — add as many draggable, resizable blurred rectangles as you want (blur 2–20)
- **Background Blur** — blurred canvas background when the video aspect doesn't match the project frame
- **Subtitles** — import `.srt` files, with an optional blurred background behind the text
- **Text overlays** — unlimited text, custom `.ttf` / `.otf` fonts, keyframes, layers
- **Myanmar font** — bundled Noto Sans Myanmar (OFL); Myanmar text is rendered with correct shaping in preview and export
- **Ratio / Crop** — change the video aspect ratio
- **Effects & Adjust** — brightness, contrast, saturation, colour filters
- **Audio** — music, voice-over, ducking, fades, volume up to 200%
- **Export resolution** — Original, 720p, 1080p, 2K, 4K (applied to the video's long edge, so portrait 1080p exports as 1080×1920)
- **Projects & history** — Home screen shows recent projects/drafts, tap to resume; full undo/redo in the editor
- **Privacy** — everything is processed locally on the device, no watermark

## Build

1. Clone the repository.
2. Open the project in Android Studio.
3. Build and run on an Android device or emulator.

Requires Android Studio and the Android SDK.

## Credits

Built on [LibreCuts](https://github.com/tharunbirla/LibreCuts) by Tharun Birla
(MIT License, see `LICENSE`). AIO VD Editor adds blur boxes, subtitle blur,
Myanmar shaping-safe export, export resolutions, drafts history, and the
gold/black AIO HUB PSM branding.

Myanmar font: Noto Sans Myanmar, SIL Open Font License 1.1
(see `app/src/main/assets/fonts/NotoSansMyanmar-OFL.txt`).

## License

MIT License — see `LICENSE`.
