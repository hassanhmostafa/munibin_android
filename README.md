# Munibin Android

Native Android project, snapshot v440, including the earlier v439 changes.

## Open and build

Open this repository in Android Studio with Android SDK 36 installed. Use the included Gradle wrapper. Release signing is configured locally; signing keys are not included.

The packaged web assets and native prayer widgets, live notification, and notch integration are included.

## v440 changes

- Display the countdown target's prayer day across the app, widgets and live displays, including the transition after Isha iqama and iqama crossing midnight.
- Retain enabled live-display settings across app updates.
- Improve widget spacing, sunrise placement, Hijri date space, and active prayer underlines.
- Show iqama minute offsets in a lighter version of their prayer clock's color; retain red iqama countdowns.

Source and behavior checks passed. Native build and device validation remain required.
