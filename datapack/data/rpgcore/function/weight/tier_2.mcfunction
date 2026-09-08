# 100-129% capacity: overloaded - noticeable slowdown and hunger drain.
scoreboard players set @s rpgcore.weight_tier 2
attribute @s minecraft:movement_speed modifier remove rpgcore:weight_speed
attribute @s minecraft:movement_speed modifier add rpgcore:weight_speed -0.25 multiply_total
attribute @s minecraft:jump_strength modifier remove rpgcore:weight_jump
attribute @s minecraft:jump_strength modifier add rpgcore:weight_jump -0.40 multiply_total
effect give @s minecraft:hunger 6 0 true
