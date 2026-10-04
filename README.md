# Gameoverse Dimension Gear

Rescales other mods' tools and armor from per-dimension tables, so each dimension past the End can have its own gear
tier on the Gameoverse server. Both sides (tooltips show the new numbers).

Tables live in `src/main/resources/gameoverse_dimension_gear/tables/*.json`. Each item's durability, base attack
damage, mining speed, and armor, toughness and knockback resistance are set through Fabric's
`DefaultItemComponentEvents`. Attack damage moves by `bonus - from_bonus` (the table's bonus minus the material's own),
so per-tool differences are kept.

The first table is Aerial Hell (`aerialhell.json`): Sky Wood around netherite and each material after it a big step
above, leaving its unique weapons as they are. Recheck the table whenever the dimension mod's materials change.

## Building

`JAVA_HOME=/usr/lib/jvm/java-25-openjdk sh ./gradlew build`

## License

MIT.
