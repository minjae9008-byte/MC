# Called once from the freshly-chopped stump. Checks same-height neighbours
# (to catch 2x2 trunks) plus everything one block up, then hands off to
# expand_neighbours (upward-only) from there so we never cross into an
# unrelated tree standing next to this one at ground level.
$execute positioned ~-1 ~0 ~-1 run function rpgcore:treefell/expand {id:"$(id)"}
$execute positioned ~-1 ~0 ~0 run function rpgcore:treefell/expand {id:"$(id)"}
$execute positioned ~-1 ~0 ~1 run function rpgcore:treefell/expand {id:"$(id)"}
$execute positioned ~0 ~0 ~-1 run function rpgcore:treefell/expand {id:"$(id)"}
$execute positioned ~0 ~0 ~1 run function rpgcore:treefell/expand {id:"$(id)"}
$execute positioned ~1 ~0 ~-1 run function rpgcore:treefell/expand {id:"$(id)"}
$execute positioned ~1 ~0 ~0 run function rpgcore:treefell/expand {id:"$(id)"}
$execute positioned ~1 ~0 ~1 run function rpgcore:treefell/expand {id:"$(id)"}
$execute positioned ~-1 ~1 ~-1 run function rpgcore:treefell/expand {id:"$(id)"}
$execute positioned ~-1 ~1 ~0 run function rpgcore:treefell/expand {id:"$(id)"}
$execute positioned ~-1 ~1 ~1 run function rpgcore:treefell/expand {id:"$(id)"}
$execute positioned ~0 ~1 ~-1 run function rpgcore:treefell/expand {id:"$(id)"}
$execute positioned ~0 ~1 ~0 run function rpgcore:treefell/expand {id:"$(id)"}
$execute positioned ~0 ~1 ~1 run function rpgcore:treefell/expand {id:"$(id)"}
$execute positioned ~1 ~1 ~-1 run function rpgcore:treefell/expand {id:"$(id)"}
$execute positioned ~1 ~1 ~0 run function rpgcore:treefell/expand {id:"$(id)"}
$execute positioned ~1 ~1 ~1 run function rpgcore:treefell/expand {id:"$(id)"}
