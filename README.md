# 🗺️ Better Worlds
**#BringBackAdventureMaps**

Greatly overhauls the world menu with many insights, new features & an online world browser to quick download + play!
The mod is split into 3 parts, so even if you only want one, the others are not forced.

## 🔻 Download
- [**Better Worlds**](https://modrinth.com/project/world) [(R)](./worlds/README.md) - Improved world menu, ingame map browser & faster world loading
- [**Chunk Editor (MCA-Selector)**](https://modrinth.com/project/mca-selector) [(R)](./worlds-editor/README.md) - Adds a chunk editor like MCASelector directly ingame! Select chunks by hand or rules
- [**Show My World**](https://modrinth.com/project/show-my-world) [(R)](./worlds-preview/README.md) - See your world in the world & server list before joining

---

![Preview](https://i.postimg.cc/13FGcFDt/untitled.webp)

---

## 🛠️ Development

Task shortcuts for easier testing.


### Run Clients
- `./gradlew :worlds:worlds-fabric:runClient`
- `./gradlew :worlds:worlds-neoforge:runClient`

### Building
```shell
./gradlew :worlds:worlds-fabric:build :worlds:worlds-neoforge:build
./gradlew :worlds-editor:worlds-editor-fabric:build :worlds-editor:worlds-editor-neoforge:build
./gradlew :worlds-viewer:worlds-viewer-fabric:build :worlds-viewer:worlds-viewer-neoforge:build
```

### Publishing
```shell
./gradlew :worlds:worlds-fabric:publishMods :worlds:worlds-neoforge:publishMods
./gradlew :worlds-editor:worlds-editor-fabric:publishMods :worlds-editor:worlds-editor-neoforge:publishMods
./gradlew :worlds-preview:worlds-preview-fabric:publishMods :worlds-preview:worlds-preview-neoforge:publishMods
```
