# User Guide

> **Status:** Placeholder. Full guide will be written when the app is
> functional.

## What ZeroVPN does

ZeroVPN routes your phone traffic through a non-UK exit path using
infrastructure you or a trusted person controls. It is not a VPN
service — there are no ZeroVPN servers.

## Install

1. Get the APK from the [releases page](../../releases) (when available)
2. Enable "Install unknown apps" in Android settings
3. Install the APK

## Connect

1. Open ZeroVPN
2. If you have a QR invite from a trusted operator, scan it
3. If you have a WireGuard config file, import it
4. Tap the connect button
5. Check the Diagnostics screen to verify your exit IP

## What ZeroVPN does not do

- It does not hide your SIM country, phone number, or GPS location
- It does not provide anonymity
- It does not bypass all forms of age verification
- It does not make you immune to browser fingerprinting

See `docs/THREAT_MODEL.md` for the full list of what ZeroVPN does and
does not protect against.