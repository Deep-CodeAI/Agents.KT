## Transcripts

Each audio recording gets a transcript in the `Транскрипты/` folder. The tree inside repeats the recording's place in the project. The file name matches the recording name.

Transcription uses whisper.cpp, model `~/.whisper-models/ggml-large-v3-turbo.bin`, language `ru`.

The transcript is cleaned of recognition artifacts and split into sections by topic, keeping the order of the lines. Tasks from the recording go into a separate file with statuses.
