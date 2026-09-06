#!/bin/bash
# Runs the platform-independent assertions (URL building, the server-matching
# content hash, the backup planner, gallery sectioning, JSON decoding) as a
# plain macOS binary.
#
# The XCTest suite in ChitraTests/ covers the same ground and should be the
# default way to run them:
#
#     xcodebuild test -project Chitra.xcodeproj -scheme Chitra \
#       -destination 'platform=iOS Simulator,name=iPhone 16'
#
# but xcodebuild refuses every simulator destination when no installed
# simulator runtime matches the SDK, which is the state of this machine
# (Xcode 26.6, only an iOS 18.6 runtime). This script needs no simulator.
set -euo pipefail
cd "$(dirname "$0")/.."
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
swiftc -O -o "$out/logic-check" \
  Chitra/API/Urls.swift \
  Chitra/API/Models.swift \
  Chitra/Data/ContentHash.swift \
  Chitra/Data/BackupPlanner.swift \
  Chitra/Data/DeviceMedia.swift \
  Chitra/UI/Theme.swift \
  Chitra/UI/GallerySections.swift \
  Chitra/UI/GalleryState.swift \
  Tools/LogicCheck/main.swift
"$out/logic-check"
