# Debug Guide

## Android

Useful commands:

adb logcat | grep -i p2p
adb logcat | grep -i webrtc

Check:
- ICE state
- DataChannel state
- Bytes received from peer
- HTTP fallback trigger

## Server

Check:
- signaling logs
- TURN logs
- firewall ports
- relay status

## Testing Matrix

1. WiFi to WiFi
2. WiFi to Mobile Data
3. Mobile Data to Mobile Data
4. Force TURN test
5. Force P2P test

Goal:
Confirm stable P2P media source.
