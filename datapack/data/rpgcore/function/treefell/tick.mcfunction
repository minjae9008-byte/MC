# Scans for marker items dropped by the loot table overrides in
# data/minecraft/loot_table/blocks/*.json (only spawned when a log is broken
# with an item in #rpgcore:treefell_tool). Each marker carries which log id
# it came from in its custom_data, so the break-chain logic below is fully
# generic and needs no per-wood-type code.
execute as @e[type=item,nbt={Item:{components:{"minecraft:custom_data":{RpgTreefell:1b}}}}] at @s run function rpgcore:treefell/trigger with entity @s Item.components."minecraft:custom_data"
