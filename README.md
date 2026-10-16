# plugin-scene-editor

Paper scene authoring for Grounds build servers.

The repository contains a Bukkit-free `common` API and a runnable `paper` plugin artifact.

New scenes use the current lobby action catalog. Existing scenes retain their serialized action
catalog pin and are validated against that exact supported lobby catalog revision.

NPC bounce distance is an approximate travel distance in blocks. Use
`/scene npc <id> collision distance <blocks>` with a value from 0 through 20; the resulting travel
can vary with the runtime collision environment.

Scene-editor feedback uses the general system-message category because it describes editor
operations rather than an in-game story. Info, success, warning and error remain independent
levels that control visual emphasis without replacing the category label.
