# CamDroid

Pi-based replacement for CamRanger. USB tethers a Canon or Nikon camera to a Raspberry Pi 5; the Pi serves JPG previews over Wi-Fi to a native Android tablet/phone for review.

## Layout

- `pi/` — Python daemon (libgphoto2 + FastAPI), hostapd config, systemd unit
- `android/` — Native Android review app (Kotlin)
- `docs/` — Design notes, API contract, state-machine diagrams
