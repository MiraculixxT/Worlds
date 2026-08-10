# 🗺️ ️Chunk Editor

An MCA-Selector like chunk map, in game.

Adds a **Chunk Editor** button to Minecraft's world edit screen.
View the whole map & select chunks by hand or by criteria (playtime, staleness, distance from spawn), and delete them so
the game regenerates them on the next visit.

![preview](https://cdn.modrinth.com/data/xO4qs4xy/images/23f3356b074d5a95666b457e456133cf612a2751.webp)

* Terrain rendering straight off the region files
  * Respects data driven properties (biome tint, blocks, ...)
  * 3 LoD layers based on zoom
* Every dimension the save has, including custom dimensions
* Deletes `region/`, `entities/` and `poi/` together (after backup)

![fun with zoom](https://i.postimg.cc/4yyfMz5x/MCA.webp)

Directly integrated by [**Worlds**](https://modrinth.com/mod/world) with more useful world configurations & faster loading!
