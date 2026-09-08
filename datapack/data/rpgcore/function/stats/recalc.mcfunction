# Recomputes every derived value (max HP, weight capacity, real vanilla attribute
# bonuses) from the base stats. Call this any time a base stat or level changes.
# Extend this file to wire up new stats -> new attributes.

# --- Max HP: base + level*per_level + vit*per_vit, applied to the real max_health attribute ---
scoreboard players operation @s rpgcore.hp_max = @s rpgcore.level
scoreboard players operation @s rpgcore.hp_max *= $per_level_hp rpgcore.const
scoreboard players operation @s rpgcore.tmp = @s rpgcore.vit
scoreboard players operation @s rpgcore.tmp *= $per_vit_hp rpgcore.const
scoreboard players operation @s rpgcore.hp_max += @s rpgcore.tmp
scoreboard players operation @s rpgcore.hp_max += $base_hp rpgcore.const

data modify storage rpgcore:calc attribute set value "minecraft:max_health"
execute store result storage rpgcore:calc value int 1 run scoreboard players get @s rpgcore.hp_max
function rpgcore:util/set_attribute_base with storage rpgcore:calc

# --- STR -> bonus attack damage ---
scoreboard players operation @s rpgcore.tmp = @s rpgcore.str
scoreboard players operation @s rpgcore.tmp *= $per_str_dmg_milli rpgcore.const
data modify storage rpgcore:calc attribute set value "minecraft:attack_damage"
data modify storage rpgcore:calc id set value "rpgcore:str_bonus"
data modify storage rpgcore:calc operation set value "add_value"
execute store result storage rpgcore:calc value double 0.001 run scoreboard players get @s rpgcore.tmp
function rpgcore:util/set_attribute_modifier with storage rpgcore:calc

# --- AGI -> bonus movement speed ---
scoreboard players operation @s rpgcore.tmp = @s rpgcore.agi
scoreboard players operation @s rpgcore.tmp *= $per_agi_speed_milli rpgcore.const
data modify storage rpgcore:calc attribute set value "minecraft:movement_speed"
data modify storage rpgcore:calc id set value "rpgcore:agi_speed_bonus"
data modify storage rpgcore:calc operation set value "add_value"
execute store result storage rpgcore:calc value double 0.001 run scoreboard players get @s rpgcore.tmp
function rpgcore:util/set_attribute_modifier with storage rpgcore:calc

# --- AGI -> bonus jump strength ---
scoreboard players operation @s rpgcore.tmp = @s rpgcore.agi
scoreboard players operation @s rpgcore.tmp *= $per_agi_jump_milli rpgcore.const
data modify storage rpgcore:calc attribute set value "minecraft:jump_strength"
data modify storage rpgcore:calc id set value "rpgcore:agi_jump_bonus"
data modify storage rpgcore:calc operation set value "add_value"
execute store result storage rpgcore:calc value double 0.001 run scoreboard players get @s rpgcore.tmp
function rpgcore:util/set_attribute_modifier with storage rpgcore:calc

# --- LUCK -> vanilla luck attribute ---
scoreboard players operation @s rpgcore.tmp = @s rpgcore.luck
scoreboard players operation @s rpgcore.tmp *= $per_luck_luck_centi rpgcore.const
data modify storage rpgcore:calc attribute set value "minecraft:luck"
data modify storage rpgcore:calc id set value "rpgcore:luck_bonus"
data modify storage rpgcore:calc operation set value "add_value"
execute store result storage rpgcore:calc value double 0.01 run scoreboard players get @s rpgcore.tmp
function rpgcore:util/set_attribute_modifier with storage rpgcore:calc

# --- STR -> carry weight capacity (our own custom stat, not a vanilla attribute) ---
scoreboard players operation @s rpgcore.weight_max = @s rpgcore.str
scoreboard players operation @s rpgcore.weight_max *= $per_str_weight_max rpgcore.const
scoreboard players operation @s rpgcore.weight_max += $base_weight_max rpgcore.const

# weight effects depend on the ratio to the new max, so re-apply immediately
function rpgcore:weight/apply
