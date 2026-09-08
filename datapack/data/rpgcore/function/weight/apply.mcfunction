# Converts weight/weight_max into a load percentage and applies the matching tier.
# Tune thresholds/penalties in weight/tier_0..3.mcfunction.
scoreboard players operation @s rpgcore.tmp = @s rpgcore.weight
scoreboard players operation @s rpgcore.tmp *= $hundred rpgcore.const
scoreboard players operation @s rpgcore.tmp /= @s rpgcore.weight_max

execute if score @s rpgcore.tmp matches ..69 run function rpgcore:weight/tier_0
execute if score @s rpgcore.tmp matches 70..99 run function rpgcore:weight/tier_1
execute if score @s rpgcore.tmp matches 100..129 run function rpgcore:weight/tier_2
execute if score @s rpgcore.tmp matches 130.. run function rpgcore:weight/tier_3
