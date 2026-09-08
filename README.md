# RPGCore

Paper 서버용 RPG 시스템. **게임 로직은 전부 플러그인**에 있고, 데이터팩은 아이템/블록 분류 태그만 담는 **데이터 레이어**입니다.

- **`plugin/`** — 스탯/레벨, 무게, 연쇄 벌목, HUD, 근접 채팅, 크로스플레이 UI. 전부 이벤트 기반 Java.
- **`datapack/`** — `#rpgcore:weight_*`, `#rpgcore:tree_log`, `#rpgcore:treefell_tool` 태그만. **함수 0개, 매 틱 실행 0**.

## 왜 플러그인으로 옮겼나 (v1 데이터팩 → v2 플러그인)

데이터팩만으로 구현했을 때의 실측 가능한 비용과, 지금 구조의 차이입니다 (20인 기준).

| 기능 | v1 (데이터팩) | v2 (플러그인) |
|---|---|---|
| 무게 계산 | `execute if items` **164줄 × 20명 × 2회/초 ≈ 6,600 명령/초**, 인벤토리 변화와 무관하게 상시 | 인벤토리가 **바뀐 플레이어만** 41칸 배열 순회 + EnumMap 조회. 가만히 서 있으면 **비용 0** |
| 벌목 감지 | loot table 22개 오버라이드 + 마커 아이템 + **매 틱 전체 아이템 엔티티 NBT 스캔** | `BlockBreakEvent` 한 번. 스캔·마커·loot table 오버라이드 **전부 제거** |
| 벌목 처리 | 재귀 flood-fill이 **단일 틱에 1,000~2,000 명령** (대형 나무에서 틱 스파이크) | 큐를 **틱당 12블록씩** 소비. 대형 나무도 틱 스파이크 없음 |
| 스탯 배분 | trigger 오브젝티브 **폴링 11줄 × 20명 × 20틱 = 4,400 명령/초**, 반영까지 1틱 지연 | GUI 클릭 → 메서드 직접 호출. 폴링 없음, 지연 없음 |
| 근접 감지 | `@a[distance=..]` 셀렉터를 **1초마다 전원** 실행 (O(n²)) | 채팅이 **실제로 발생할 때만** 거리 비교 (제곱거리, sqrt 없음) |
| HUD | 매 틱(→5틱) tellraw JSON 조립 | 1초 간격 Adventure 컴포넌트 |
| 상시 반복 작업 | `#minecraft:tick` 함수 체인 | **반복 태스크 2개** (1틱 큐 펌프, 1초 HUD) |

부수적으로 얻은 것:
- **스택 수량 반영 무게** — 데이터팩 판은 NBT 버전 차이 때문에 슬롯 단위로만 계산했지만, 이제 `개수 × 무게`로 정확히 계산합니다.
- **보호 플러그인 호환** — 연쇄로 부술 블록마다 `BlockBreakEvent`를 발생시켜 WorldGuard/GriefPrevention이 거부할 수 있습니다 (`respect-protection-plugins`).
- **도구 내구도/인챈트 반영** — `breakNaturally(tool)`로 실크터치 등이 정상 적용되고, 도구 내구도도 닳으며(언브레이킹 확률 반영) 부러지기 직전에 벌목이 멈춥니다.
- **loot table 충돌 제거** — 원목 loot table을 덮어쓰지 않으므로 다른 데이터팩과 부딪히지 않습니다.
- **실제 XP 획득 경로** — 몹 처치/벌목 XP가 실제로 들어옵니다 (v1은 디버그 함수뿐이었습니다).

### 데이터는 여전히 바닐라 스코어보드에 기록됩니다
플러그인은 값이 바뀔 때만 `rpgcore.*` 스코어보드에 **미러링**합니다. 핫패스는 메모리 캐시(`PlayerData`)만 읽습니다. 미러를 유지하는 이유는 두 가지입니다.
1. **저장 비용 0** — 스코어보드는 월드와 함께 저장되므로 별도 데이터 파일이 없습니다.
2. **상호 운용** — 운영자가 `/scoreboard players get`으로 확인하거나, 다른 데이터팩/커맨드 블록이 RPG 값을 읽을 수 있습니다.

## 설치

1. **플러그인**: `plugin/`에서 `mvn package` → `plugin/target/rpgcore-plugin-2.0.0.jar`를 서버 `plugins/`에 넣고 재시작. 빌드에는 Java 21이 필요합니다.
2. **데이터팩(선택, 권장)**: `datapack/` 폴더를 `world/datapacks/rpgcore/`로 복사. 없으면 `config.yml`의 목록이 대신 쓰입니다. `pack.mcmeta`의 `pack_format`은 서버 버전에 맞게 조정하세요. 서버 기동 로그에 `Couldn't load tag rpgcore:...`가 없어야 정상입니다 (태그 하나라도 존재하지 않는 아이템을 참조하면 그 태그 전체가 통째로 무시되고 조용히 `config.yml` 폴백으로 넘어갑니다 — [`CONFIG.md`](CONFIG.md) 2절 참고).
3. **(선택) 음성 채팅**: [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat)을 `plugins/`에 추가하고, `voicechat-server.properties`의 `voice_chat_distance`를 `config.yml`의 `proximity-chat.range`(기본 24)와 맞추세요. 자바 클라이언트 모드가 필요합니다.

## 기능

### 1. RPG 스탯 / 레벨
- STR / DEX / VIT / AGI / LUCK, 레벨업마다 포인트 지급(초기 5개).
- 실제 바닐라 어트리뷰트에 반영: VIT→최대 체력, STR→공격력·최대 소지무게, DEX→공격 속도, AGI→이동속도·점프력, LUCK→행운.
- 어트리뷰트는 레지스트리 키로 조회하므로 1.21.2의 `generic.max_health` → `max_health` 개명 양쪽에서 동작합니다.
- XP: 몹 처치(`xp-sources.per-mob-kill`), 벌목한 원목 수(`per-tree-log`), `/rpgcore givexp`.

### 2. 소지 무게
- 인벤토리 + 방어구 + 보조손 41칸을 `개수 × 등급 무게`로 계산.
- 부하율에 따라 4단계: 70% 미만 무패널티 → 이동속도 -10/-25/-50%, 점프 -15/-40/-70%, 100% 초과부터 허기, 130% 초과부터 채굴 피로.
- 단계가 바뀔 때만 어트리뷰트를 건드리고, 바뀔 때 채팅으로 알려줍니다.

### 3. 연쇄 벌목
- 도끼로 원목을 캐면 위/옆으로 연결된 같은 원목이 큐 방식으로 순차 제거 (2x2 굵은 나무 지원, 옆 나무로는 번지지 않음).
- 웅크리면 비활성(`sneak-disables`), 최대 블록 수 제한, 크리에이티브 제외.

### 4. 내구도에 따른 성능 저하
- 낡은 무기는 약하게 때리고, 낡은 방어구는 덜 막고, 낡은 도구는 느리게 캡니다.
- 남은 내구도 `full-performance-above`(기본 50%) 이상이면 페널티 없음. 그 아래로는 0%까지 `minimum-performance`(기본 50%)로 직선 감소합니다.
- 적용 대상: 주 손 아이템 → `attack_damage` + `block_break_speed`, 착용 방어구 평균 → `armor` + `armor_toughness`. 각각 `affects.*`로 끌 수 있습니다.
- **아이템 자체는 절대 고쳐 쓰지 않습니다.** 플레이어 어트리뷰트에 `MULTIPLY_SCALAR_1` 모디파이어로만 걸리므로, 바닐라 수리·다른 플러그인과 충돌하지 않고 수리하면 그대로 성능이 돌아옵니다.
- 장비가 바뀌거나 내구도가 닳은 플레이어만 재계산합니다(이벤트 → dirty 플래그 → 틱 펌프). 가만히 있으면 비용 0.

### 5. 모루 커스텀 강화
- 왼쪽 칸에 장비, 오른쪽 칸에 재료를 넣으면 인챈트가 붙거나 내구도가 찹니다. **조합법은 전부 `config.yml`** — 코드 수정 없이 추가/삭제할 수 있습니다.
- 기본 제공 7종:

  | 조합 | 재료 | 레벨 | 효과 |
  |---|---|---|---|
  | 연마 | 부싯돌 4 | 5 | 날카로움 +1 (최대 5) |
  | 성수 | 뼈 블록 2 | 5 | 강타 +1 (최대 5) |
  | 해충 퇴치 | 발효된 거미눈 2 | 5 | 벌레 죽이기 +1 (최대 5) |
  | 연마석 | 자수정 조각 4 | 5 | 효율 +1 (최대 5) |
  | 보호 각인 | 메아리 조각 1 | 6 | 보호 +1 (최대 4) |
  | 강화 | 흑요석 4 | 8 | 내구성 +1 (최대 3) |
  | 수리 키트 | 구리 주괴 2 | 2 | 내구도 +25% |

- 대상은 아이템 태그로 지정합니다(기본값은 바닐라 `#minecraft:enchantable/*`). `#rpgcore:*` 데이터팩 태그나 개별 아이템 ID도 그대로 씁니다.
- **조합법이 걸리지 않는 조합은 바닐라 모루 그대로입니다** — 같은 재료 수리, 인챈트북 합치기, 이름 변경 전부 정상 동작하고, 커스텀 결과물에도 이름 변경이 함께 적용됩니다.
- 이미 최대 레벨이거나 내구도가 가득 찬 장비는 결과가 비어서 레벨을 헛되이 쓰지 않습니다. 레벨이 모자라면 바닐라와 똑같이 결과를 꺼낼 수 없습니다.
- 4번 기능과 맞물려서, **수리 키트는 곧 성능 회복**입니다.

### 6. 근접 채팅 / 음성
- 근접 텍스트 채팅은 플러그인이 자체 처리 (베드락 포함 전 플랫폼 동작).
- 실제 음성은 Simple Voice Chat 연동 (자바 클라이언트 전용).

### 7. UI
- `/stats` (별칭 `/rpg`, `/rpgstats`) — 자바는 상자 GUI, 베드락은 Floodgate 네이티브 폼. 장비 상태(내구도 → 현재 성능)와 모루 조합법 목록도 함께 보여줍니다.
- 상시 액션바 HUD: 레벨 / HP / XP / 무게(부하 단계별 색상). 장비가 닳았을 때만 `GEAR 74%` 구간이 추가로 붙습니다.
- 관리자: `/rpgcore reload | recipes | givexp <player> <amount> | reset <player>`.

## Geyser / Floodgate (베드락 크로스플레이) 호환

### 자동으로 처리되는 것
| 기능 | 베드락에서 |
|---|---|
| 스탯/레벨, 무게, 연쇄 벌목, 근접 채팅, 내구도 페널티, 모루 강화 | 서버 사이드 로직이라 **완전 동일 동작** (모루는 베드락 네이티브 화면으로 Geyser가 번역) |
| 스탯 UI | `/stats` → **베드락 네이티브 폼**. Floodgate가 없거나 API가 안 맞으면 상자 GUI로 자동 폴백 |
| HUD | 액션바 1초 간격이라 Geyser 번역 부하도 낮음 |
| 스코어보드 이름 | Floodgate 접두사(기본 `.`)가 붙은 이름 그대로 사용해 값이 어긋나지 않음 |

### 베드락 제약 (우회 처리함)
| 제약 | 원인 | 대응 |
|---|---|---|
| 채팅 글자 클릭 불가 | 베드락 프로토콜에 클릭 컴포넌트 없음 | UI를 폼/GUI로 제공 (클릭형 채팅 메뉴 자체를 없앰) |
| 점프력 페널티 미적용 | 베드락에 플레이어 jump_strength 어트리뷰트 없음 | 이동속도 페널티·허기는 그대로 적용 |
| 이모지 깨짐 | 베드락 폰트에 자바 이모지 글리프 없음 | 메시지를 ASCII + 한글로만 구성 |
| 플레이어 머리 / barrier 아이콘 | 스킨 조회 실패, 렌더 불안정 | 베드락은 일반 아이콘 사용, 닫기 버튼은 유리판 |
| **Simple Voice Chat 음성** | 자바 클라이언트 모드 필수 | 베드락은 근접 **텍스트** 채팅으로 대체 (서버 로그에도 안내) |

## 확장하는 방법

자세한 내용은 [`CONFIG.md`](CONFIG.md). 요약:

| 하고 싶은 것 | 수정할 곳 |
|---|---|
| 밸런스(경험치 곡선, 스탯 배율, 무게 용량) | `plugin/src/main/resources/config.yml` — 재시작 없이 `/rpgcore reload` |
| 아이템 무게 분류 | 데이터팩 `#rpgcore:weight_*` 태그 (없으면 `config.yml`의 `weight.tiers.*.items`) |
| 벌목 가능한 나무 / 도구 | 데이터팩 `#rpgcore:tree_log`, `#rpgcore:treefell_tool` 태그 (또는 `tree-felling.logs/tools`) |
| 새 스탯 추가 | `StatType`에 한 줄 추가 → GUI·베드락 폼·스코어보드에 자동 반영. 효과만 `StatsService.recalculate`에 작성 |
| 내구도 페널티 곡선 | `durability-scaling.*` (임계값/하한/적용 대상) |
| 모루 조합법 추가·수정 | `anvil.recipes.*` — 재료·수량·레벨 비용·인챈트·수리량 전부 설정 |
| 벌목 성능 조정 | `tree-felling.blocks-per-tick`, `max-blocks` |
| 무게 갱신 부하 | `weight.scans-per-tick`, `safety-rescan-ticks` |
| GUI 제목/크기, 베드락 폼 사용 여부 | `gui.*`, `bedrock.use-native-forms` |

## 검증 상태

**Paper 1.21.4 (빌드 232) 실서버에서 데이터팩 + 플러그인을 함께 구동해 확인했습니다.** 봇 클라이언트로 실제 접속해 아래를 직접 검증했고, 전 구동 로그에 예외 0건입니다.

| 검증 항목 | 결과 |
|---|---|
| 데이터팩 태그 로딩 | 5개 태그 전부 정상 로드, 236개 아이템 분류 (`config.yml` 폴백 0건) |
| 최초 접속 / 재접속 | 초기 지급 5포인트, 재접속 시 스코어보드에서 복구 |
| `/stats` GUI | 열림 → STR 클릭 → 포인트 차감 + 즉시 재출력 → 닫기 버튼 |
| 스탯 → 어트리뷰트 | `str_damage` 0.5 / `dex_attack_speed` 0.05 / `agi_speed` 0.002 / `agi_jump` 0.01 / `luck_bonus` 0.5, 최대 체력 base 반영 |
| XP / 레벨업 | 250 XP → Lv.3, 벌목 10블록 → XP +10 |
| 무게 단계 | 96/100 → 1단계(이동속도 -10%), 240/100 → 3단계(실측 speed 0.051 = -50%), 허기 + 채굴 피로 부여 확인 |
| 무게 해제 | 아이템 투척 즉시(2.5초 내) 페널티 해제, 모디파이어 제거 확인 |
| 연쇄 벌목 | 2x2 원목 두 줄 동시 제거, 다이아 도끼 내구도 22 소모, `max-blocks: 256` 상한 준수 |
| 웅크리기 / 크리에이티브 | 둘 다 연쇄 비활성 확인 |
| 근접 채팅 | 3블록 수신 O / 400블록 수신 X, 플레이어가 입력한 `&c` 색코드는 그대로 텍스트로 출력 |
| 관리자 명령 | `reload` / `givexp` / `reset` + 잘못된 인자 6종 모두 안전 처리 |
| 내구도 → 공격력/채굴 | 내구도 24% 다이아 검: `gear_attack`/`gear_mining` -0.26, 공격력 7.0 → **5.18**, 채굴속도 1.0 → **0.74**. 새 검으로 바꾸면 즉시 해제 |
| 내구도 → 방어력 | 다이아 상의(5%) + 투구(17%) 착용: `gear_armor` -0.39, 방어력 11.0 → **6.71**. 새 방어구로 교체 시 모디파이어 제거 |
| HUD / 경고 | `GEAR 74%` 구간 표시 + "장비가 낡았습니다" 1회 안내, 온전한 장비에서는 구간 없음 |
| 모루 — 인챈트 | 다이아 검 + 부싯돌 16 → 날카로움 I, 부싯돌 4개·레벨 5 소모 |
| 모루 — 수리 | 다이아 곡괭이 damage 1000 + 구리 2 → **damage 610** (정확히 최대치의 25%), 레벨 2 소모 |
| 모루 — 방어구 강화 | 다이아 상의 + 흑요석 4 → 내구성 I, 레벨 8 소모 |
| 모루 — 상한/비용 | 날카로움 V + 부싯돌 → 결과 비어 있음(헛돈 방지), 레벨 부족 시 꺼내기 차단 + 재료·레벨 그대로 유지 |
| 모루 — 바닐라 보존 | 다이아 곡괭이 + 다이아 4 = 바닐라 수리 정상, 이름 변경 정상 |

남은 제약:

- 플러그인은 Paper API 1.21+ 기준입니다. 어트리뷰트는 개명 양쪽을 지원하지만, `AttributeModifier(NamespacedKey, ...)` 생성자는 1.21+ 전용입니다.
- **Geyser/Floodgate와 Simple Voice Chat 연동은 이 환경에 해당 서버가 없어 실행 검증하지 못했습니다.** 소스는 각 공식 API(`floodgate-api 2.2.2-SNAPSHOT`, `cumulus 1.1.2`, `voicechat-api 2.5.36`)로 컴파일됩니다. 두 연동 모두 실패해도 다른 기능에 영향이 없도록 격리·폴백되어 있으며, 로그에 경고만 남깁니다.
- `Bukkit.getTag`로 데이터팩 커스텀 태그를 읽습니다. 서버 빌드가 이를 지원하지 않으면 자동으로 `config.yml` 목록으로 폴백하고, 시작 로그에 어느 쪽을 썼는지 출력합니다.
- 어트리뷰트 모디파이어 키의 네임스페이스는 `plugin.yml`의 플러그인 이름에서 나오므로 `rpgcoreplugin:*`입니다 (예: `/attribute <player> minecraft:movement_speed modifier value get rpgcoreplugin:weight_speed`). 스코어보드 오브젝티브(`rpgcore.*`)·데이터팩 태그(`rpgcore:*`)와 네임스페이스가 다르니 주의하세요.

## 디렉터리 구조

```
datapack/
  pack.mcmeta
  data/rpgcore/tags/item/    weight_light|medium|heavy|very_heavy, treefell_tool
  data/rpgcore/tags/block/   tree_log
plugin/
  pom.xml
  src/main/resources/        plugin.yml, config.yml
  src/main/java/com/rpgcore/plugin/
    RpgCorePlugin.java       진입점, 커맨드, 태스크 2개 등록
    config/RpgConfig.java    config.yml 타입 뷰 (핫패스에서 YAML 파싱 없음)
    data/                    PlayerData 캐시 + 스코어보드 미러 write-through
    stats/                   StatType, StatsService(레벨/배분/어트리뷰트), 세션·XP 리스너
    weight/                  ItemWeightTable(태그/설정 로딩), WeightService, WeightListener
    tree/                    TreeFellService(큐 기반 연쇄 벌목), TreeFellListener
    gear/                    GearService(내구도 → 성능), GearListener
    anvil/                   AnvilRecipe, AnvilService(설정 로딩/결과 생성), AnvilListener
    hud/HudTask.java         액션바 HUD
    gui/                     상자 GUI
    chat/                    근접 텍스트 채팅
    platform/                Geyser/Floodgate 감지 + 베드락 네이티브 폼
    voice/                   Simple Voice Chat 소프트 연동
    util/                    스코어보드 미러, 버전 안전 어트리뷰트/인챈트 헬퍼, 태그 해석(MaterialSets)
```
