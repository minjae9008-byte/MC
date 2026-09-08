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

## 7. 베드락(Geyser/Floodgate) 관련

- `StatType`에 스탯을 추가하면 베드락 폼 버튼도 자동 생성됩니다.
- 네이티브 폼을 끄고 전부 상자 GUI로 통일: `bedrock.use-native-forms: false`.
- 새로 추가하는 문구는 **이모지 없이 ASCII + 한글**로 작성하세요 (베드락 폰트에 자바 이모지 글리프가 없어 □로 깨집니다).
- 새 GUI 아이템은 베드락에도 존재하는 블록/아이템인지 확인하세요 (`BARRIER` 등은 렌더가 불안정합니다).
- Floodgate/Cumulus는 절대 jar에 shade하지 마세요 (`pom.xml`에서 `provided` 유지). 번들링하면 Floodgate의 알려진 `LinkageError`가 발생합니다.

## 8. GUI 크기

`gui.size`는 상자 GUI의 칸 수입니다. 고정 슬롯(레벨/HP/무게/포인트/닫기)이 3줄을 쓰므로 **27~54 범위의 9의 배수**로 정규화됩니다. 9처럼 더 작은 값을 넣어도 27로 올려서 열리고, 닫기 버튼은 항상 **마지막 칸**입니다.

## 9. 근접 채팅 / 음성 범위

```yaml
proximity-chat:
  range: 24
  hide-out-of-range: true   # false면 전원에게 전달되고 포맷만 적용
  format: "&7[근접] &f%player%&7: &f%message%"
```
Simple Voice Chat을 함께 쓴다면 `plugins/voicechat/voicechat-server.properties`의 `voice_chat_distance`도 같은 값으로 맞추세요. `voicechat.sync-range-with-proximity-chat: true`면 플러그인이 SVC 서버가 뜰 때 두 값을 비교해 어긋나면 경고 로그를 남깁니다 (자동으로 고치지는 않습니다 — 실제 음성 거리는 SVC가 소유합니다).

## 10. 데이터 저장 위치

플레이어 상태는 `rpgcore.*` **바닐라 스코어보드 오브젝티브**에 미러링되어 월드와 함께 저장됩니다.
- 운영자가 직접 확인/수정: `/scoreboard players get <player> rpgcore.level`
- **접속 중인 플레이어의 오브젝티브를 손으로 바꾸는 것은 소용이 없습니다.** 스코어보드는 메모리 캐시(`PlayerData`)의 미러일 뿐이고, 다음 write-through 때 캐시 값으로 덮어써집니다. `/rpgcore reload`도 캐시에서 재계산할 뿐 스코어보드를 다시 읽지 않습니다. 값을 직접 고치려면 **해당 플레이어가 접속하지 않은 상태에서** 바꾸세요 (접속 시 스코어보드에서 읽어옵니다). 접속 중이라면 `/rpgcore givexp` / `/rpgcore reset`을 쓰세요.
- 초기화: `/rpgcore reset <player>`
- 다른 데이터팩이나 커맨드 블록에서 RPG 값을 읽고 싶을 때도 이 오브젝티브를 그대로 쓰면 됩니다.
