# Upward-only propagation used for every log broken after the first ring,
# so the chop never eats blocks below where it started.
$execute positioned ~-1 ~1 ~-1 run function rpgcore:treefell/expand {id:"$(id)"}
$execute positioned ~-1 ~1 ~0 run function rpgcore:treefell/expand {id:"$(id)"}
$execute positioned ~-1 ~1 ~1 run function rpgcore:treefell/expand {id:"$(id)"}
$execute positioned ~0 ~1 ~-1 run function rpgcore:treefell/expand {id:"$(id)"}
$execute positioned ~0 ~1 ~0 run function rpgcore:treefell/expand {id:"$(id)"}
$execute positioned ~0 ~1 ~1 run function rpgcore:treefell/expand {id:"$(id)"}
$execute positioned ~1 ~1 ~-1 run function rpgcore:treefell/expand {id:"$(id)"}
$execute positioned ~1 ~1 ~0 run function rpgcore:treefell/expand {id:"$(id)"}
$execute positioned ~1 ~1 ~1 run function rpgcore:treefell/expand {id:"$(id)"}
