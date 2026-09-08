$particle minecraft:crit ~0.5 ~0.5 ~0.5 0.25 0.25 0.25 0 8
$playsound minecraft:block.wood.break block @a ~ ~ ~ 0.6 1
$summon item ~0.5 ~0.3 ~0.5 {Item:{id:"$(id)",Count:1b},Motion:[0.0,0.2,0.0],PickupDelay:10}
$setblock ~ ~ ~ air

$function rpgcore:treefell/expand_neighbors {id:"$(id)"}
