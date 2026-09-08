# CONFIG / 확장 가이드

이 문서는 RPGCore를 코드 구조를 깊이 몰라도 확장할 수 있도록 정리한 레시피 모음입니다.

## 1. 밸런스 상수 바꾸기

모든 튜닝 가능한 값은 `datapack/data/rpgcore/function/load.mcfunction` 하단 "tunable constants" 구역에 `rpgcore.const` 목표를 가진 가짜 플레이어(fake player)로 모여 있습니다. 예:

```mcfunction
scoreboard players set $xp_base rpgcore.const 100
scoreboard players set $xp_growth rpgcore.const 50
scoreboard players set $per_str_dmg_milli rpgcore.const 500
```

숫자만 바꾸고 `/reload`(또는 서버 재시작)만 하면 전체 시스템에 반영됩니다. milli/centi가 붙은 상수는 소수점 계산을 정수 스코어보드로 흉내내기 위한 고정소수점 값입니다 (예: `per_str_dmg_milli 500` = STR 1당 공격력 +0.500).

## 2. 새 스탯 추가하기 (예: `INT`)

1. `load.mcfunction`에 목표 추가: `scoreboard objectives add rpgcore.int dummy "INT"`, 트리거도 추가: `scoreboard objectives add rpgcore.alloc_int trigger`.
2. `player/first_join.mcfunction`에 초기값 `scoreboard players set @s rpgcore.int 0` 추가.
3. `stats/alloc_str.mcfunction` + `stats/_alloc_str_apply.mcfunction`을 복사해 `alloc_int.mcfunction` / `_alloc_int_apply.mcfunction`으로 만들고 오브젝티브 이름만 바꿉니다.
4. `player/tick.mcfunction`에 다음 두 줄 추가:
   ```mcfunction
   execute if score @s rpgcore.alloc_int matches 1.. run function rpgcore:stats/alloc_int
   execute if score @s rpgcore.alloc_int matches 1.. run scoreboard players set @s rpgcore.alloc_int 0
   ```
5. `stats/recalc.mcfunction`에 INT가 만들어낼 효과를 추가합니다 (예: 마법 관련 커스텀 값이면 그냥 스코어보드 연산만 하면 되고, 실제 바닐라 어트리뷰트에 반영하고 싶다면 기존 STR/AGI 블록을 복사해 `rpgcore:util/set_attribute_modifier`를 재사용하세요).
6. (선택) `plugin/.../gui/StatsMenu.java`의 `STAT_SLOTS` 리스트에 한 줄 추가하면 GUI에도 자동으로 나타납니다.

## 3. 어트리뷰트에 스탯 효과 연결하기

`datapack/data/rpgcore/function/util/`에 재사용 가능한 매크로 유틸이 있습니다.

- `set_attribute_base {attribute, value}` — 절대값 설정 (예: 최대 체력).
- `set_attribute_modifier {attribute, id, value, operation}` — 모디파이어 추가/교체. `operation`은 `add_value`, `add_multiplied_base`, `multiply_total` 중 하나.
- `remove_attribute_modifier {attribute, id}` — 모디파이어 제거.

사용 예 (recalc.mcfunction 참고):
```mcfunction
scoreboard players operation @s rpgcore.tmp = @s rpgcore.str
scoreboard players operation @s rpgcore.tmp *= $per_str_dmg_milli rpgcore.const
data modify storage rpgcore:calc attribute set value "minecraft:attack_damage"
data modify storage rpgcore:calc id set value "rpgcore:str_bonus"
data modify storage rpgcore:calc operation set value "add_value"
execute store result storage rpgcore:calc value double 0.001 run scoreboard players get @s rpgcore.tmp
function rpgcore:util/set_attribute_modifier with storage rpgcore:calc
```

## 4. 무게 시스템 확장

- 아이템 분류 변경: `datapack/data/rpgcore/tags/item/weight_light.json` / `weight_medium.json` / `weight_heavy.json` / `weight_very_heavy.json`에 아이템 ID나 태그(`#minecraft:...`)를 추가/삭제하면 됩니다.
- 무게 값(점수) 자체를 바꾸려면 `datapack/data/rpgcore/function/weight/scan_player.mcfunction`에서 `scoreboard players add @s rpgcore.weight <값>` 부분의 숫자를 바꾸세요 (light=1, medium=3, heavy=8, very_heavy=20).
- 페널티 단계/수치는 `weight/tier_0.mcfunction` ~ `tier_3.mcfunction`, 임계값은 `weight/apply.mcfunction`의 `matches` 범위를 수정하세요.
- **스택 수량까지 반영하고 싶다면**: 현재는 "슬롯에 있으면 고정 무게"만 계산합니다(성능/버전 호환성을 위한 설계 선택). 수량까지 곱하려면 `data get entity @s Inventory[{Slot:<n>b}].count`(또는 설치된 버전의 정확한 NBT 필드명 - 1.20.5+ 컴포넌트 개편으로 `count`/`Count` 표기가 버전별로 다를 수 있으니 실제 서버에서 `/data get entity @s Inventory[{Slot:0b}]`로 먼저 확인하세요)를 매크로로 읽어 곱하는 `weight/add_slot.mcfunction` 같은 헬퍼를 추가하고, `scan_player.mcfunction`을 raw NBT Slot 인덱스 기반으로 다시 생성해야 합니다.
- 새 무게 등급(예: `weight_extreme`)을 추가하려면 태그 파일과 `scan_player.mcfunction`에 41개 슬롯 x 새 태그 체크 줄을 추가하고, `apply.mcfunction`/`tier_*.mcfunction` 로직을 확장하세요.

## 5. 벌목 가능한 나무 종류 추가하기

이 시스템은 나무 종류별 전용 코드가 전혀 없습니다 (완전히 데이터 기반). 새 원목(예: 모드 추가 블록이나 신버전에서 추가된 원목)을 지원하려면:

1. `datapack/data/minecraft/loot_table/blocks/` 안의 아무 파일(예: `oak_log.json`)을 복사해 새 블록 ID 이름으로 저장합니다 (예: `pale_oak_log.json`처럼 이미 있으면 그대로 두면 됩니다).
2. 파일 안의 `"minecraft:oak_log"` 문자열 3곳(첫 pool의 드랍 아이템, 두 번째 pool의 `RpgLogId` 값)을 새 블록 ID로 바꿉니다.
3. (선택, 문서화용) `datapack/data/rpgcore/tags/block/tree_log.json`에도 추가해 목록을 최신 상태로 유지하세요 — 이 태그는 엔진 동작에는 쓰이지 않고 참고용입니다.
4. 끝입니다. `treefell/expand.mcfunction` 등 엔진 파일은 전혀 손댈 필요가 없습니다 (매크로로 블록 ID를 그대로 전달받아 동작하기 때문).

벌목에 필요한 도구를 바꾸려면 `datapack/data/rpgcore/tags/item/treefell_tool.json`을 수정하세요 (기본값은 `#minecraft:axes`).

전파 범위(현재: 처음 한 번만 옆으로도 확장해 2x2 굵은 나무를 잡고, 그 다음부터는 위쪽 3x3만 전파)를 바꾸려면 `treefell/expand_neighbors_base.mcfunction`(처음 1회, 17방향)과 `treefell/expand_neighbors.mcfunction`(이후 반복, 위쪽 9방향)의 좌표 오프셋을 수정하세요.

## 6. UI 확장

- 텍스트 메뉴(`datapack/data/rpgcore/function/ui/menu.mcfunction`)는 순수 tellraw JSON입니다. 새 줄이나 버튼을 추가하려면 같은 패턴(`clickEvent.run_command` → `/trigger rpgcore.xxx add 1`)을 따르세요.
- 상자 GUI(`plugin/.../gui/StatsMenu.java`)는 `STAT_SLOTS` 리스트와 `open()` 메서드의 `inv.setItem(...)` 호출만 수정하면 됩니다. 클릭 처리는 `StatsMenuListener.java`가 슬롯 번호 → 트리거 오브젝티브 매핑을 자동으로 처리하므로 별도 로직 추가가 필요 없습니다.

## 7. 근접 채팅/음성 범위 조정

두 곳을 함께 수정해야 값이 일치합니다.
- `datapack/data/rpgcore/function/load.mcfunction`: `scoreboard players set $voice_range rpgcore.const 24`
- `plugin/src/main/resources/config.yml`: `proximity-chat.range: 24`
- (Simple Voice Chat 설치 시) `plugins/voicechat/voicechat-server.properties`: `voice_chat_distance`
