# 130%+ capacity: severely overloaded.
scoreboard players set @s rpgcore.weight_tier 3
attribute @s minecraft:movement_speed modifier remove rpgcore:weight_speed
attribute @s minecraft:movement_speed modifier add rpgcore:weight_speed -0.50 multiply_total
attribute @s minecraft:jump_strength modifier remove rpgcore:weight_jump
attribute @s minecraft:jump_strength modifier add rpgcore:weight_jump -0.70 multiply_total
effect give @s minecraft:hunger 6 1 true
effect give @s minecraft:mining_fatigue 6 0 true
