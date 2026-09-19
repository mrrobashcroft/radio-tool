# Radio Tool for Light Phone III

A minimal, high-contrast radio streaming application built with the Light Phone SDK. This tool allows users to discover, play, and curate their favorite online radio stations globally.

## Initial Build Design
Initial build design compiled by **Rob Ashcroft**, August 2026.

## Features

- **Live Streaming**: High-quality audio playback using Media3 ExoPlayer. Supports MP3, AAC, and HLS (.m3u8).
- **Background Playback**: Continues playing audio even when the tool is minimized or the screen is off.
- **Home Screen Integration**: Control playback (Play/Pause/Stop) directly from the LightOS Home screen (Clock).
- **Intelligent Search**: 
    - Discover thousands of stations via the Radio Browser community API.
    - **Search History**: Remembers your last 15 search terms for quick re-searching.
    - **Query Persistence**: Remembers your current typing state even if you navigate away.
- **Library Management**:
    - **Favourites**: Curate a limitless list of your most-loved stations.
    - **Recently Played**: Automatically tracks your last 15 successful streams.
    - **Smart Verification**: Stations are only added to history once they successfully start playing (Proof of Play).
- **Manual Entry**: Add custom stream URLs manually with horizontal auto-scrolling for long addresses.
- **In-place Renaming**: Tap any station name on the player screen to rename it instantly across all your lists.
- **Hardware Support**: Physical volume buttons are fully enabled for media control.
- **Theme Support**: Pure monochrome aesthetic following the Light Phone III design language.

## How it Works

### Audio Engine
The tool uses a high-performance audio engine built on **AndroidX Media3**. It includes selective URL sanitization to handle both legacy SHOUTcast IP addresses and modern secure domain-based streams.

### Persistence
User data is stored locally in JSON format:
- `stations.json`: Stores favorited stations.
- `recent_played.json`: Stores the last 15 successful streams.
- `search_history.json`: Stores the last 15 search queries.
- `last_played.json`: Remembers the active station between sessions.

### Networking
Station discovery is powered by the **Radio Browser API** using the reliable `de1` mirror. The tool uses **Ktor** for safe, asynchronous requests with a 15-second connection timeout for slow community servers.

## Implementation Notes

- **Background Service**: Background audio is enabled via a registered `MediaSession` and high-priority foreground service logic, including explicit Wi-Fi and CPU WakeLocks for sleep-mode stability.
- **Cleartext Traffic**: Configured to allow `http` connections to support community radio servers.
- **User Agent**: Identifies as `LightPhoneRadioTool/1.0` for maximum server compatibility.
- **Navigation**: Persistent back-navigation logic minimizes the tool instead of closing it, ensuring audio continuity.

## How to Test

To test the Radio tool:
1.  **Run the `sdk.emulator` module**: Starts the LightOS system app emulator.
2.  **Run the `radio` module**: Installs the Radio tool onto the emulator.
3.  **To test on hardware**: Use the latest `radio-tool.apk` from the `release/` folder.

## Technical Details

- **Module Name**: `:radio`
- **Package**: `com.thelightphone.radio`
- **SDK Compatibility**: Official Light SDK v0.1.1+
- **Permissions**: `INTERNET`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`.
