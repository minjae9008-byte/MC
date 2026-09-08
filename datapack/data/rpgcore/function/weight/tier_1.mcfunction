# 70-99% capacity: light encumbrance.
scoreboard players set @s rpgcore.weight_tier 1
attribute @s minecraft:movement_speed modifier remove rpgcore:weight_speed
attribute @s minecraft:movement_speed modifier add rpgcore:weight_speed -0.10 multiply_total
attribute @s minecraft:jump_strength modifier remove rpgcore:weight_jump
attribute @s minecraft:jump_strength modifier add rpgcore:weight_jump -0.15 multiply_total
