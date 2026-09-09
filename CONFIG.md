# CONFIG / 확장 가이드

게임 로직은 전부 플러그인에 있습니다. 대부분의 조정은 `plugin/src/main/resources/config.yml`(배포 후에는 `plugins/RPGCorePlugin/config.yml`)에서 끝나고, `/rpgcore reload`로 재시작 없이 반영됩니다.

## 1. 밸런스 조정 (설정만)

```yaml
leveling:
  xp-base: 100        # 레벨 2까지 필요한 XP
  xp-growth: 50       # 레벨당 추가 요구량
stats:
  hp-per-vit: 1
  attack-damage-per-str: 0.5
weight:
  base-capacity: 100
  capacity-per-str: 10
```

`/rpgcore reload` → 접속 중인 모든 플레이어의 파생 능력치가 즉시 재계산됩니다.

## 2. 아이템 무게 분류

우선순위: **데이터팩 태그 > config.yml 목록**.

- 데이터팩: `datapack/data/rpgcore/tags/item/weight_light.json` 등에 아이템 ID나 바닐라 태그(`#minecraft:planks`)를 추가. 서버가 태그를 해석해주므로 바닐라 태그를 그대로 쓸 수 있는 게 장점입니다.
- 데이터팩 미설치: `config.yml`의 `weight.tiers.<등급>.items` 목록 사용.
- 등급별 무게 값은 `weight.tiers.<등급>.weight`. 무게는 **개수 × 등급 무게**로 계산됩니다.
- 어느 목록에도 없는 아이템은 `weight.default-item-weight`(기본 1).

새 등급을 추가하려면 `weight.tiers` 아래에 키를 하나 더 만들고(예: `extreme`), 원하면 같은 이름의 데이터팩 태그 `#rpgcore:weight_extreme`을 만들면 됩니다. 코드 수정은 필요 없습니다.

> **주의 — 태그는 전부 아니면 전무입니다.** 태그 안에 그 버전에 존재하지 않는 아이템/태그가 **하나라도** 있으면 마인크래프트는 그 태그를 통째로 버립니다. 플러그인은 태그가 없는 것으로 보고 조용히 `config.yml` 목록으로 폴백하므로, 증상은 "무게가 이상하다" 뿐입니다. 기동 로그에서
> ```
> Couldn't load tag rpgcore:weight_light as it is missing following references: ...
> ```
> 를 확인하고, 버전에 따라 있을 수도 없을 수도 있는 항목은 필수가 아니라고 표시하세요.
> ```json
> { "id": "minecraft:pale_oak_log", "required": false }
> ```
> 26.2부터는 `pack.mcmeta` 스키마도 바뀌었습니다. 포맷 81보다 위를 지원한다고 선언하려면 `min_format`/`max_format`이 **필수**이고, 없으면 `Error reading pack metadata` 경고와 함께 구형 스키마로 폴백합니다. 구·신버전을 모두 지원하려면 이 저장소의 `datapack/pack.mcmeta`처럼 `pack_format` + `supported_formats` + `min_format` + `max_format`을 함께 적으세요.
>
> 바닐라 태그를 참조할 때는 실제로 존재하는지 확인이 필요합니다. 예를 들어 `#minecraft:dyes`, `#minecraft:ingots`, `#minecraft:bows`, `#minecraft:flowers`는 **바닐라에 없습니다** (`#minecraft:small_flowers`는 있습니다). 플러그인이 실제로 몇 개를 태그에서 읽었는지는 기동 로그의 `Weight table loaded: N materials (X from datapack tags, Y from config.yml)`로 확인하세요 — `X`가 0이면 태그를 못 읽은 것입니다.

## 3. 무게 페널티 단계 수정

`WeightService.tierFor()`(임계값)와 `applyTier()`(단계별 수치)를 수정합니다. 단계가 바뀔 때만 어트리뷰트를 건드리도록 되어 있으니, 값만 바꾸면 나머지는 그대로 동작합니다.

성능 관련 설정:
```yaml
weight:
  scans-per-tick: 8        # 한 틱에 재계산할 최대 인원
  safety-rescan-ticks: 200 # 이벤트를 놓쳤을 때를 대비한 전원 재검사 주기
```
평소에는 인벤토리가 바뀐 플레이어만 재계산하므로, 이 두 값은 최악의 경우를 제한하는 용도입니다.

## 4. 새 스탯 추가하기 (예: `INT`)

1. `stats/StatType.java`에 한 줄 추가:
   ```java
   INT("INT", "rpgcore.int", Material.BOOK, "마법 위력"),
   ```
   이것만으로 상자 GUI 슬롯, 베드락 폼 버튼, 스코어보드 오브젝티브가 자동 생성됩니다.
2. 효과를 `StatsService.recalculate()`에 추가 (기존 STR/AGI 블록 복사):
   ```java
   Attributes.setModifier(player, Attributes.attackDamage(), intPowerKey,
           data.stat(StatType.INT) * config.somethingPerInt(),
           AttributeModifier.Operation.ADD_NUMBER);
   ```
3. 배율 상수는 `RpgConfig`에 필드 + getter를 추가하고 `config.yml`에 키를 넣습니다.

## 5. 어트리뷰트를 다룰 때

`util/Attributes.java`를 쓰세요.
- `Attributes.maxHealth()` 등은 레지스트리 키로 조회하며 `max_health` / `generic.max_health` 양쪽 표기를 모두 시도합니다 (1.21.2 개명 대응).
- `setModifier(player, attribute, key, amount, operation)`는 값이 실제로 달라졌을 때만 모디파이어를 교체하므로 매 틱 호출해도 안전합니다. `amount`가 0이면 모디파이어를 제거합니다.
- `setBase(player, attribute, value)`는 최대 체력처럼 절대값을 설정할 때 사용합니다.

## 6. 벌목 확장

```yaml
tree-felling:
  max-blocks: 256               # 나무 한 그루당 상한
  blocks-per-tick: 12           # 틱당 제거 수 (성능/연출 조절)
  respect-protection-plugins: true
  damage-tool: true
  sneak-disables: true
```

- 나무 종류/도구는 데이터팩 태그 `#rpgcore:tree_log`, `#rpgcore:treefell_tool`에 추가하면 끝입니다 (없으면 `tree-felling.logs`, `tree-felling.tools` 목록 사용). **코드 수정 불필요.**
- 전파 방향(현재: 첫 링만 옆으로 확장해 2x2 나무를 잡고, 이후는 위쪽 3x3)은 `TreeFellService.Job.enqueueNeighbours()`에서 오프셋 범위를 바꾸면 됩니다.
- `respect-protection-plugins`를 켜면 연쇄로 부술 블록마다 `BlockBreakEvent`를 발생시켜 보호 플러그인이 거부할 수 있습니다. 보호 플러그인이 전혀 없다면 꺼서 이벤트 비용을 줄일 수 있습니다.

## 7. 내구도에 따른 성능 저하

```yaml
durability-scaling:
  enabled: true
  full-performance-above: 80   # 남은 내구도가 이 % 이상이면 페널티 없음
  minimum-performance: 50      # 내구도 0% 직전일 때의 성능 %
  affects:
    attack-damage: true
    armor: true
    mining-speed: true
    ranged-damage: true
```

성능 = 내구도가 임계값 이상이면 100%, 아래면 `minimum-performance`에서 100%까지의 직선 보간입니다. 예) 임계 80 / 하한 50에서 내구도 60% → 성능 87%, 내구도 19% → 성능 61%.

임계값을 올릴수록 "조금만 써도 성능이 떨어지는" 빡빡한 서버가 됩니다. 기본 80은 내구도가 5분의 1만 닳아도 바로 체감되는 설정입니다.

- 주 손 아이템 → `attack_damage`, `block_break_speed` / 착용 방어구의 **평균** 내구도 → `armor`, `armor_toughness`.
- 원거리(`ranged-damage`)는 어트리뷰트가 아니라 **투사체에 직접** 새깁니다. 활·석궁은 `EntityShootBowEvent`, 삼지창은 `PlayerLaunchProjectileEvent`에서 발사 시점 무기 상태를 읽어 `AbstractArrow#setDamage`에 곱합니다. 발사 뒤 무기를 바꿔도 이미 날아간 투사체는 변하지 않습니다. (근접 삼지창 찌르기는 주 손 아이템이므로 `attack_damage` 쪽에서 처리됩니다.)
- 방어구 평균은 **착용 중인 칸만** 셉니다. 빈 칸을 100%로 치면 낡은 갑옷 한 벌이 희석되기 때문입니다.
- 적용 방식은 플레이어 어트리뷰트의 `MULTIPLY_SCALAR_1` 모디파이어(`rpgcoreplugin:gear_attack` 등)입니다. **아이템은 건드리지 않습니다.**
- `MULTIPLY_SCALAR_1`은 합계에 곱해지므로 STR 보너스도 함께 깎입니다(무딘 무기는 힘으로도 못 살린다는 의도). 무기 성능만 따로 떼려면 `StatsService`의 STR 모디파이어를 `MULTIPLY_*`로 옮기세요.
- `block_break_speed`는 1.21.2에서 추가된 어트리뷰트입니다. 그보다 낮은 서버에서는 `Attributes.blockBreakSpeed()`가 null을 반환하고 채굴 페널티만 조용히 빠집니다.
- `/give`처럼 이벤트를 발생시키지 않는 인벤토리 변경은 `weight.safety-rescan-ticks`(기본 200틱 = 10초) 주기의 재검사가 잡습니다. 실제 플레이에서는 아이템을 줍거나 옮기거나 내구도가 닳는 순간 즉시 갱신됩니다.
- 곡선을 바꾸려면 `GearService.performancePercent()` 하나만 고치면 됩니다.

## 8. 모루 커스텀 조합법

```yaml
anvil:
  enabled: true
  recipes:
    sharpen:                                        # 아무 키나 가능 (id로 쓰임)
      name: "&b날 세우기"                             # /rpgcore recipes, GUI 표시용
      target: ['#minecraft:enchantable/sharp_weapon'] # 왼쪽 칸 대상
      ingredient: minecraft:flint                     # 오른쪽 칸 재료
      ingredient-amount: 2
      enchantments:
        sharpness: 1                                  # 1회당 올릴 레벨
    repair-kit:
      target: ['#minecraft:enchantable/durability']
      ingredient: minecraft:grindstone
      ingredient-amount: 1
      repair-percent: 25                              # 최대 내구도의 25% 회복
```

- `target`은 목록입니다. 아이템 ID, 바닐라 태그(`#minecraft:enchantable/mining`), 이 데이터팩 태그(`#rpgcore:...`)를 섞어 쓸 수 있습니다.
- **`level-cost`는 기본 0(무료)**, **인챈트 상한도 기본으로 없습니다.** 재료만 있으면 몇 번이든 반복해 바닐라 최대 레벨을 넘길 수 있습니다.
- 바닐라의 작업 횟수 누적 비용("수리 비용이 너무 비쌉니다")도 커스텀 조합에는 걸리지 않습니다. 결과물의 `repair_cost`를 올리지 않고 입력값 그대로 넘기기 때문입니다. 바닐라 수리·합치기는 평소처럼 비용이 올라갑니다.
- 제한을 되살리려면 명시하면 됩니다:
  ```yaml
  enchantments:
    sharpness:
      levels: 1
      max-level: 5
  level-cost: 5
  ```
- 상한을 없애도 안전 하드 상한 **255**는 남습니다 (그 이상은 아이템 데이터/표시가 깨질 수 있습니다). 바닐라 모루의 레벨 제한 검사는 커스텀 결과물에 대해 `bypassEnchantmentLevelRestriction`으로 해제합니다.
- `repair-percent`와 `enchantments` 둘 다 없으면 그 조합법은 아무 일도 하지 않으므로 로드 시 경고와 함께 건너뜁니다.
- 매칭 순서는 config에 적힌 순서입니다. 대상이 겹치면 먼저 적힌 조합법이 이깁니다.
- **재료 선택 주의**: 그 장비의 바닐라 수리 재료(철 갑옷 + 철 주괴 등)를 재료로 쓰면 바닐라 수리를 덮어씁니다. 기본 조합법이 철 '주괴' 대신 철 '블록', 숫돌 같은 재료를 쓰는 이유입니다. 26.2에서 구리 장비가 추가되어 구리 주괴도 이제 바닐라 수리 재료입니다.
- 조합법이 걸리지 않는 조합은 `PrepareAnvilEvent`에서 손대지 않으므로 바닐라 동작 그대로입니다.
- 로드 결과 확인: 기동 로그의 `Anvil recipes loaded: N.` 과 `/rpgcore recipes`.

## 9. 베드락(Geyser/Floodgate) 관련

- `StatType`에 스탯을 추가하면 베드락 폼 버튼도 자동 생성됩니다.
- 네이티브 폼을 끄고 전부 상자 GUI로 통일: `bedrock.use-native-forms: false`.
- 새로 추가하는 문구는 **이모지 없이 ASCII + 한글**로 작성하세요 (베드락 폰트에 자바 이모지 글리프가 없어 □로 깨집니다).
- 새 GUI 아이템은 베드락에도 존재하는 블록/아이템인지 확인하세요 (`BARRIER` 등은 렌더가 불안정합니다).
- Floodgate/Cumulus는 절대 jar에 shade하지 마세요 (`pom.xml`에서 `provided` 유지). 번들링하면 Floodgate의 알려진 `LinkageError`가 발생합니다.

## 10. GUI 크기

`gui.size`는 상자 GUI의 칸 수입니다. 고정 슬롯(레벨/HP/무게/포인트/닫기)이 3줄을 쓰므로 **27~54 범위의 9의 배수**로 정규화됩니다. 9처럼 더 작은 값을 넣어도 27로 올려서 열리고, 닫기 버튼은 항상 **마지막 칸**입니다.

## 11. 근접 채팅 / 음성 범위

```yaml
proximity-chat:
  range: 24
  hide-out-of-range: true   # false면 전원에게 전달되고 포맷만 적용
  format: "&7[근접] &f%player%&7: &f%message%"
```
Simple Voice Chat을 함께 쓴다면 `plugins/voicechat/voicechat-server.properties`의 `voice_chat_distance`도 같은 값으로 맞추세요. `voicechat.sync-range-with-proximity-chat: true`면 플러그인이 SVC 서버가 뜰 때 두 값을 비교해 어긋나면 경고 로그를 남깁니다 (자동으로 고치지는 않습니다 — 실제 음성 거리는 SVC가 소유합니다).

## 12. 데이터 저장 위치

플레이어 상태는 `rpgcore.*` **바닐라 스코어보드 오브젝티브**에 미러링되어 월드와 함께 저장됩니다.
- 운영자가 직접 확인/수정: `/scoreboard players get <player> rpgcore.level`
- **접속 중인 플레이어의 오브젝티브를 손으로 바꾸는 것은 소용이 없습니다.** 스코어보드는 메모리 캐시(`PlayerData`)의 미러일 뿐이고, 다음 write-through 때 캐시 값으로 덮어써집니다. `/rpgcore reload`도 캐시에서 재계산할 뿐 스코어보드를 다시 읽지 않습니다. 값을 직접 고치려면 **해당 플레이어가 접속하지 않은 상태에서** 바꾸세요 (접속 시 스코어보드에서 읽어옵니다). 접속 중이라면 `/rpgcore givexp` / `/rpgcore reset`을 쓰세요.
- 초기화: `/rpgcore reset <player>`
- 다른 데이터팩이나 커맨드 블록에서 RPG 값을 읽고 싶을 때도 이 오브젝티브를 그대로 쓰면 됩니다.
