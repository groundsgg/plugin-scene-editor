# plugin-scene-editor

Paper scene authoring for Grounds build servers.

The repository contains a Bukkit-free `common` API and a runnable `paper` plugin artifact.

New scenes use the current lobby action catalog. Existing scenes retain their serialized action
catalog pin and are validated against that exact supported lobby catalog revision.
