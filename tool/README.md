# Radio Tool for Light Phone III

A minimal, high-contrast radio streaming application built with the Light Phone SDK. This tool allows users to discover, play, and curate their favorite online radio stations globally.

## Initial Build Design
Initial build design compiled by **Rob Ashcroft**, August 2026.

## Features

- **Live Streaming**: Supports MP3, AAC, and HLS (.m3u8).
- **Background Playback**: Continues playing audio even when the tool is minimized or the screen is off.
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


### Persistence
User data is stored locally in JSON format:
- `stations.json`: Stores favorited stations.
- `recent_played.json`: Stores the last 15 successful streams.
- `search_history.json`: Stores the last 15 search queries.
- `last_played.json`: Remembers the active station between sessions.
