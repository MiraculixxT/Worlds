# 🗺️ Worlds - Map Finder
**#BringBackAdventureMaps**

*Worlds* adds a world map browser to the SinglePlayer menu that allows browsing through all available Maps on Modrinth
and outside*! 
Also improves the normal world list by showing useful information and short-cut to actions.<br>
This is an attempt to use Modrinth to bring back the good times of adventure/custom Maps!

---

![Preview](https://i.postimg.cc/13FGcFDt/untitled.webp)

---

# 🌟 Features
- World screen overhaul (inspired by [ModMenu](https://modrinth.com/mod/modmenu))
- Ingame one-click world install
  - Extracts worlds & resource-packs (multiple supported)
  - Scans for other dependencies like mods
  - Displays description, previews & trailer/source links
- Loads all resourcepacks inside a worlds `resourcepacks` folder (vanilla: only `resources.zip`)
  - Bind packs to worlds to autoload/unload them while playing
- Improve initial custom world gen loading (when installed via browser) 
- Displays useful information about installed worlds
  - Bundled resourcepacks, datapacks & required mods
  - Playtime, last played
  - Total disk size
- Advanced world editing screen
  - Simple metadata, backups & optimizing worlds
  - Data-/resource-pack management
  - GUI driven gamerule, player & scoreboard management
  - Full MCASelector like Chunk-Manager (overview + trimming)

I'm glad over any feedback how to improve the flow for adventure maps even more!<br>
What do you miss?


# Creator Notice
To add your World to the browse menu, there are multiple options:
### Modrinth Dependency (recommended)
Upload your map to Modrinth as `ModPack` and add this mod as dependency (`world`).
The newest version with this dependency will automatically show up in the browser!
All properties are auto-detected, adding a youtube trailer to your project readme also creates a Trailer button.
<br>Users are prompted to open the modrinth page on install, so you still receive revenue!

### Curseforge Map
Upload your map to Curseforge as `World`. 
The newest version will automatically show up in the browser, but some properties like dependencies are not detected.

### *Manual List
You can edit `./src/main/resources/assets/worlds/maps.json` in this repo to include your world.
See below for a guide to all fields:

<details><summary>Json field description</summary>

Unlike the Modrinth way, all fields must be manually populated.
See the example below for a broad overview which you can copy:
```json
[
  {
    "source": "manual",
    "id": "goemetry-dash-in-mc",
    "name": "Geometry Dash in Minecraft",
    "description": "Play Geometry Dash in Minecraft",
    "readme": "GEOMETRY DASH!!! Heck yeah this was a fun project! Hope you all enjoy this shorter dev like kinda content! It was kinda fun to make and heart destroying towards the end :'D",
    "icon": "https://imgs.crazygames.com/games/geometry-dash-online/cover_1x1-1732744370684.png",
    "download": "https://dl.minecraftmaps.com/geometry-dash-in-minecraft-52577.zip",
    "website": "https://www.minecraftmaps.com/52577-geometry-dash-in-minecraft",
    "trailer": "https://www.youtube.com/watch?v=1175Fi9DCLY",
    "mc": ["26.2"],
    "categories": ["parkour"],
    "requiredMods": [
      { "name": "Fabric API", "id": "fabric-api", "link": "https://modrinth.com/mod/fabric-api" }
    ],
    "requiredPacks": [
      { "name": "Geometry Dash Textures", "included": true }
    ]
  }
]
```
Most fields can be omitted if not available.
The following fields only take certain values:
- `categories` - Can be: `adventure`,`parkour`,`survival`,`puzzle`,`horror`,`minigames`,`build`
- `requiredMods` - List of mods that must be installed. `id` must be the mods internal ID
  - `{ "name": "Mod Name", "id": "internal-id", "link": "https://link.that.user/is/prompted" }`
- `requiredPacks` - List of resource-packs that must be installed. Internal packs can also be marked to show in preview & pack list
  - Included: `{ "name": "Pack Name", "included": true }`
  - External: `{ "name": "Pack Name", "id": "file-name.zip", "link": "https://direct-link.to/the/pack.zip" }`


</details>
