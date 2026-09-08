# RPGCore - loaded on datapack (re)load
# Creates all scoreboard objectives and tunable "constant" values.
# Safe to run multiple times (objectives 'add' is a no-op if it already exists).

scoreboard objectives add rpgcore.level dummy "Level"
scoreboard objectives add rpgcore.xp dummy "XP"
scoreboard objectives add rpgcore.xp_need dummy "XP to next level"
scoreboard objectives add rpgcore.points dummy "Unspent stat points"

scoreboard objectives add rpgcore.str dummy "STR"
scoreboard objectives add rpgcore.dex dummy "DEX"
scoreboard objectives add rpgcore.vit dummy "VIT"
scoreboard objectives add rpgcore.agi dummy "AGI"
scoreboard objectives add rpgcore.luck dummy "LUCK"

scoreboard objectives add rpgcore.hp health "HP"
scoreboard objectives add rpgcore.hp_max dummy "Max HP (points)"
scoreboard objectives add rpgcore.weight dummy "Weight"
scoreboard objectives add rpgcore.weight_max dummy "Max weight"
scoreboard objectives add rpgcore.weight_tier dummy "Weight tier (0-3)"

scoreboard objectives add rpgcore.io dummy
scoreboard objectives add rpgcore.tmp dummy
scoreboard objectives add rpgcore.const dummy
scoreboard objectives add rpgcore.init dummy

scoreboard objectives add rpgcore.voice_near dummy
scoreboard objectives add rpgcore.voice_was_near dummy

scoreboard objectives add rpgcore.menu trigger
scoreboard objectives add rpgcore.alloc_str trigger
scoreboard objectives add rpgcore.alloc_dex trigger
scoreboard objectives add rpgcore.alloc_vit trigger
scoreboard objectives add rpgcore.alloc_agi trigger
scoreboard objectives add rpgcore.alloc_luck trigger

# ---- tunable constants (edit these to rebalance the whole system) ----
scoreboard players set $xp_base rpgcore.const 100
scoreboard players set $xp_growth rpgcore.const 50

scoreboard players set $base_hp rpgcore.const 20
scoreboard players set $per_level_hp rpgcore.const 2
scoreboard players set $per_vit_hp rpgcore.const 1

scoreboard players set $per_str_dmg_milli rpgcore.const 500
scoreboard players set $per_agi_speed_milli rpgcore.const 2
scoreboard players set $per_agi_jump_milli rpgcore.const 10
scoreboard players set $per_luck_luck_centi rpgcore.const 50

scoreboard players set $base_weight_max rpgcore.const 100
scoreboard players set $per_str_weight_max rpgcore.const 10

scoreboard players set $ten rpgcore.const 10
scoreboard players set $twenty rpgcore.const 20
scoreboard players set $hundred rpgcore.const 100

# proximity chat/voice range in blocks - the Paper plugin reads this same value
scoreboard players set $voice_range rpgcore.const 24

tellraw @a {"text":"[RPGCore] datapack (re)loaded.","color":"green"}
