## ADDED Requirements

### Requirement: Focused share window
A translucent activity excluded from recents SHALL handle the existing text/file SEND and magnet/torrent/NZB VIEW filters using AddSheet/AddViewModel, without composing navigation or Downloads. MainActivity SHALL retain only MAIN/LAUNCHER routing. Success SHALL confirm and finish; cancel SHALL finish; failures SHALL remain editable. Existing ACTION_VIEW per-file playback and encrypted phone-entered credentials SHALL be preserved.

#### Scenario: Share from Chrome
- **WHEN** a user shares a web link or magnet
- **THEN** only the add sheet appears over the source app and returns there after adding or cancelling.
