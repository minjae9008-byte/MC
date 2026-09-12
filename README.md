# RPGCore

Paper 서버용 RPG 플러그인. 스탯/레벨, 직업, 순위표, 파티, 플레이어 간 거래,
업적과 칭호, 수집 도감, 1:1 대결, 소지 무게, 장비 내구도 페널티, 연쇄 벌목,
모루 커스텀 강화, 근접 채팅을 한 덩어리로 제공합니다.
이름표와 플레이어 목록, 아이템 툴팁에도 RPG 정보가 함께 표시됩니다.

- **jar 하나가 전부입니다.** 데이터팩도, 다른 플러그인도, 외부 라이브러리도 필요 없습니다.
- **전부 서버 사이드 로직**이라 자바와 베드락(Geyser) 플레이어가 **완전히 동일하게** 동작합니다.
- 설정은 `config.yml` 한 파일. 대부분 `/rpgcore reload` 로 재시작 없이 반영됩니다.

## 설치

**요구사항: Paper 26.2 이상, Java 25** (Paper 26.2 자체가 Java 25를 요구합니다.)

1. `plugin/` 에서 빌드 → `plugin/target/rpgcore-plugin-2.1.0.jar`
   ```
   cd plugin && JAVA_HOME=/path/to/jdk-25 mvn package
   ```
2. jar 를 서버 `plugins/` 에 넣고 재시작.
3. 끝. `plugins/RPGCorePlugin/` 에 설정 파일 3개가 자동 생성됩니다.
   - `settings.yml` — 최대 레벨, 경험치 곡선, 인챈트 한계, 기능 on/off (**주로 여기만**)
   - `jobs.yml` — 직업 정의
   - `config.yml` — 아이템 분류, 모루 조합법 등 세부 설정

기동 로그에 아래처럼 뜨면 정상입니다.

```
[RPGCorePlugin] Weight table loaded: 242 materials; anything unlisted weighs 1.
[RPGCorePlugin] Tree felling: 22 log types, 7 tools.
[RPGCorePlugin] Anvil recipes loaded: 7.
[RPGCorePlugin] Jobs loaded: 5.
[RPGCorePlugin] Parties loaded: 0.
```

**(선택) 근접 음성**: [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) 을 함께 깔면 실제 음성이 됩니다.
그쪽 `voice_chat_distance` 와 이쪽 `proximity-chat.range` 가 어긋나면 기동 로그가 경고합니다.
(음성은 자바 클라이언트 모드가 필요합니다. 베드락은 근접 **텍스트** 채팅으로 동작합니다.)

## 기능

### 스탯 / 레벨
STR · DEX · VIT · AGI · LUCK. 레벨업마다 포인트를 받아 `/stats` 창에서 찍습니다.
찍은 값은 바닐라 어트리뷰트에 그대로 반영됩니다.

| 스탯 | 효과 |
|---|---|
| STR | 공격력, 최대 소지무게 |
| DEX | 공격 속도 |
| VIT | 최대 체력 |
| AGI | 이동속도, 점프력 |
| LUCK | 행운 |

XP는 몹 처치, 벌목한 원목 수, `/rpgcore givexp` 로 들어옵니다.
**최대 레벨**과 **경험치 곡선(배수)** 은 `settings.yml` 에서 정합니다.

### 직업 (클래스)
`/job` 으로 고릅니다. 직업은 **스탯 보너스 + 어트리뷰트 + 경험치 배율 + 소지무게**를 주며,
전부 `config.yml` 에서 정의하므로 코드 수정 없이 추가·수정할 수 있습니다.

| 직업 | 필요 레벨 | 주는 것 |
|---|---|---|
| 전사 | 1 | STR+3 VIT+2, 최대체력 +4, 소지무게 +40, 넉백저항 +15% |
| 궁수 | 1 | DEX+3 AGI+2, 이동속도 +8% |
| 광부 | 1 | STR+2 VIT+1, 소지무게 +80, 채굴속도 +25%, 경험치 x1.1 |
| 도적 | 5 | AGI+3 LUCK+3, 이동속도 +12%, 공격속도 +10% |
| 수호자 | 10 | VIT+4, 방어력 +4, 방어구 강도 +2, 최대체력 +6, 소지무게 +60 |

직업이 주는 스탯은 직접 찍은 포인트 **위에 더해집니다**. `/stats` 창에서 `VIT: 5 (2 +3 직업)`
처럼 분리해서 보여줍니다. 직업을 바꾸면 이전 직업의 보너스는 전부 해제됩니다.

### 순위표 (리더보드)
`/leaderboard [항목]` — 스코어보드에 기록되는 값은 전부 순위로 볼 수 있습니다.

| 묶음 | 항목 |
|---|---|
| 성장 | `level`, `gold` |
| 스탯 | `str` `dex` `vit` `agi` `luck` |
| 진행도 | `mob-kills` `blocks-mined` `ores-mined` `logs-chopped` `fish-caught` `duels-won` `gold-earned` `collected` |

자기 순위가 표시 범위 밖이면 맨 아래에 따로 붙여줍니다.

데이터는 이미 쓰고 있는 **스코어보드 미러**에서 읽습니다. 별도 저장소가 없고,
**접속 중이 아닌 플레이어도 집계**됩니다. 결과는 몇 초간 캐시되므로 명령어를 연타해도
서버에 부담이 없습니다.

### 파티
`/party create [이름]` 으로 만들고 초대합니다. **파티는 서버를 재시작해도 유지됩니다.**

- **이름** — 파티마다 고유한 이름을 붙입니다. 이름은 파티 채팅 접두사, 초대 문구, `/party list`
  에 그대로 쓰입니다. `/party name <새이름>` 으로 바꿉니다(파티장만). 색코드는 제거되고,
  이미 쓰이는 이름이나 길이 초과(기본 16자)는 거부됩니다.
- **경험치 공유** — 파티가 얻은 XP 는 사거리(기본 50블록) 안에 있고 **접속 중인** 파티원끼리만
  나눕니다. 오프라인 파티원은 몫도 받지 않고 인원 보너스에도 들어가지 않습니다.
  `총량 = 원래 XP × (1 + 0.05 × (접속 인원-1))`
  예) 3명 접속 중 10 XP → 11 XP → 각자 4 XP. 한 명이 나가면 → 10.5 XP → 각자 5 XP.
- **아군 공격 차단** — 기본적으로 파티원끼리는 근접·투사체 피해가 들어가지 않습니다.
- **파티 채팅** — `/p <메시지>` 는 거리와 무관하게 접속 중인 파티원 전체에게 갑니다.
- 오프라인 파티원도 `/party list` 에 보이고 추방할 수 있습니다. 파티장이 `/party leave` 하면
  다음 사람에게 파티장이 넘어가고, 파티를 없애려면 `/party disband` 를 씁니다.

### 플레이어 간 거래
`/trade <플레이어>` 로 요청하고, 상대가 `/trade accept` 하면 **양쪽이 같은 상자 UI 하나**를
함께 봅니다.

```
 내가 내놓는 것        상대가 내놓는 것
 ┌───────────┐ │ ┌───────────┐
 │ ■ ■ ■ ■   │ │ │   ■ ■ ■ ■ │
 │ ■ ■ ■ ■   │ │ │   ■ ■ ■ ■ │   왼쪽 4칸 = 내 물건
 │ ■ ■ ■ ■   │ │ │   ■ ■ ■ ■ │   오른쪽 4칸 = 상대 물건
 │ ■ ■ ■ ■   │ │ │   ■ ■ ■ ■ │
 └───────────┘ │ └───────────┘
   [내 확정]    │   [상대 확정]
```

- 화면을 **하나만** 쓰므로 한쪽에게만 다르게 보이는 일이 구조적으로 불가능합니다.
- 자기 칸에만 물건을 올릴 수 있습니다. 상대 칸 클릭, 드래그, 더블클릭 모으기는 전부 막힙니다.
- 물건이 바뀌면 **양쪽 확정이 자동으로 풀립니다.** 확정 직전에 몰래 바꿔치기할 수 없습니다.
- 양쪽이 확정하면 교환됩니다. 받을 공간이 모자라면 **아무것도 옮기지 않고** 알려줍니다.
- 창을 닫거나, 접속을 종료하거나, 죽거나, `/trade cancel` 하거나, 서버가 꺼지면
  **올려둔 물건은 전부 주인에게 돌아갑니다.**

### 업적과 칭호
목표를 달성하면 골드/경험치 보상과 **칭호**가 들어옵니다. 칭호는 `/titles` 에서 골라
이름 옆에 붙입니다 — 머리 위 이름표와 Tab 목록 양쪽에 나옵니다.

| 측정 기준 | 예시 목표 | 주는 칭호 |
|---|---|---|
| 몬스터 처치 | 1,000마리 | `[전설]` |
| 광석 채굴 | 500개 | `[광부]` |
| 블록 채굴 | 10,000개 | `[대지의 주인]` |
| 원목 벌목 | 1,000개 | `[나무꾼]` |
| 물고기 낚기 | 200마리 | `[낚시왕]` |
| 대결 승리 | 25승 | `[검투사]` |
| 누적 골드 | 10,000G | `[대부호]` |
| 레벨 | **서버 최초** 20 / 50 | `[선구자]` `[개척자]` |
| 도감 등록 | 50종 | `[수집가]` |

- `first-only: true` 를 붙이면 **서버에서 딱 한 명**만 가져가는 기록이 됩니다.
  먼저 달성한 사람의 이름이 `records.yml` 에 남고 `/achievements` 에 표시됩니다.
  (동시에 달성해도 기록을 먼저 쓴 쪽만 인정되므로 두 명이 같은 최초를 가져갈 수 없습니다.)
- 칭호가 걸린 업적과 서버 최초 업적은 서버 전체에 알립니다.
- 창작 모드로 부순 블록과 낚은 물고기는 세지 않습니다.
- `/achievements` 로 항목별 진행도를, `/titles` 로 칭호 목록(못 얻은 것은 회색)을 봅니다.

### 골드
업적 보상과 몬스터 처치·레벨업으로 들어오고, 대결에 걸 수 있는 재화입니다.
스코어보드(`rpgcore.gold`)에 저장되므로 **경제 플러그인이 필요 없고** `/scoreboard` 로도
읽고 쓸 수 있습니다. `/gold` 로 확인, `/pay <플레이어> <금액>` 으로 주고받고,
`/rpgcore givegold` 로 지급·회수합니다.

주고받은 골드는 **'누적 획득'에 들어가지 않습니다.** 둘이서 같은 돈을 주거니 받거니 해서
`gold-earned` 업적을 파밍할 수 없습니다. `/pay` 자체를 막으려면
`economy.allow-player-transfer: false`.

### 수집 도감
나무·광물·양털·물고기·꽃을 모으는 도감입니다. `/collection` 으로 엽니다.

- **인벤토리에 들어오기만 하면 등록**됩니다. 캐든, 만들든, 낚든, 상자에서 꺼내든 모두 인정.
- 한 장을 다 모으면 칭호와 보상이 들어오고 서버 전체에 알립니다.
- 모은 칸은 아이템 그대로, 못 모은 칸은 회색 유리로 보여 한눈에 진행도가 읽힙니다.
- 한 분류가 한 화면(45칸)을 넘으면 자동으로 쪽이 나뉩니다. `#minecraft:logs` 같은
  태그를 통째로 넣어도 뒤쪽이 잘리지 않습니다.
- 기존 서버에 처음 설치하면 **이미 들고 있던 물건은 조용히 등록**됩니다. 접속하자마자
  수십 줄이 쏟아지거나, 몇 달 전에 채운 칸으로 보상이 한꺼번에 나가지 않습니다.

### 1:1 대결
`/duel <플레이어> [건 것]` — 양쪽이 동의하고 같은 것을 걸어야 시작됩니다.

- 건 것을 비우면 그냥 대결, 숫자면 **골드**, `hand` 면 **손에 든 아이템**입니다.
  수락하는 쪽은 같은 것(아이템이면 같은 종류·같은 개수)을 내야 합니다.
- 시작할 때 양쪽에서 **미리 빼둡니다.** 걸어 둔 것은 대결 중에 쓰거나 버리거나
  거래로 넘길 수 없고, 서버가 갑자기 내려가도 그대로 돌려받습니다.
- **죽지 않습니다.** 치명타가 들어오는 순간 피해를 취소하고 승패로 처리하므로
  진 쪽도 아이템을 떨어뜨리지 않습니다.
- 시작 전 **3초 카운트다운**이 있고 그동안은 서로 때릴 수 없습니다. 수락하자마자
  한 대 맞고 시작하는 일이 없습니다.
- 시작할 때 양쪽 **체력을 채웁니다.** 한쪽이 반 칸이라 지는 대결은 대결이 아니니까요.
  (`duel.countdown-seconds`, `duel.heal-before-start` 로 끕니다)
- 다만 그 회복은 **대결 동안만**입니다. 끝나면 양쪽 다 대결을 시작할 때 들고 온
  체력으로 돌아갑니다 — 대결이 무료 회복 수단이 되지 않도록.
- 도중에 접속을 끊으면 기권패입니다. 제한 시간(기본 5분)이 지나면 무승부로 끝나고
  건 것은 그대로 돌아갑니다.
- 파티원끼리도 대결할 수 있습니다(아군 공격 차단보다 대결이 우선).
- 월드의 PvP 가 꺼져 있으면 **신청 단계에서** 막힙니다.

### 소지 무게
인벤토리 + 방어구 + 보조손 41칸을 `개수 × 등급 무게` 로 계산합니다.
부하율에 따라 4단계이며, 단계가 바뀔 때만 어트리뷰트를 건드립니다.

| 부하율 | 이동속도 | 점프 | 추가 |
|---|---|---|---|
| ~70% | – | – | – |
| 70~100% | -10% | -15% | – |
| 100~130% | -25% | -40% | 허기 |
| 130%~ | -50% | -70% | 허기 + 채굴 피로 |

### 장비 내구도 → 성능
낡은 무기는 약하게 때리고, 낡은 방어구는 덜 막고, 낡은 도구는 느리게 캡니다.
남은 내구도가 `full-performance-above`(기본 80%) 이상이면 페널티 없음, 그 아래로는
0%까지 `minimum-performance`(기본 50%)로 직선 감소합니다. 예) 내구도 60% → 성능 87%.

| 대상 | 기준 | 적용 |
|---|---|---|
| 근접 공격 / 채굴 속도 | 주 손 아이템 | `attack_damage`, `block_break_speed` |
| 방어력 | 착용 방어구 **평균** | `armor`, `armor_toughness` |
| 원거리 피해 | 발사한 활·석궁·삼지창 | 투사체 기본 피해량 |

아이템 자체는 건드리지 않고 플레이어 어트리뷰트에만 걸리므로, 수리하면 성능이 그대로 돌아옵니다.
원거리는 **발사 시점** 무기 상태를 투사체에 새깁니다.

### 모루 커스텀 강화
왼쪽 칸에 장비, 오른쪽 칸에 재료. **경험치 레벨을 쓰지 않고 반복 횟수 제한도 없습니다.**
바닐라의 "수리 비용이 너무 비쌉니다" 누적 제한도 걸리지 않습니다.

| 조합 | 재료 | 효과 |
|---|---|---|
| 날 세우기 | 부싯돌 2 | 날카로움 +1 |
| 성화 각인 | 발광석 가루 3 | 강타 +1 |
| 독 바르기 | 거미 눈 3 | 벌레 죽이기 +1 |
| 기계 정비 | 레드스톤 4 | 효율 +1 |
| 철판 덧대기 | 철 블록 1 | 보호 +1 |
| 흑요석 담금질 | 흑요석 2 | 내구성 +1 |
| 숫돌 정비 | 숫돌 1 | 내구도 +25% |

조합법에 걸리지 않는 조합은 **바닐라 모루 그대로**입니다 — 같은 재료 수리, 인챈트북 합치기,
이름 변경 모두 정상 동작합니다. 내구도 페널티와 맞물려 **수리 = 성능 회복**입니다.

### 연쇄 벌목
도끼로 원목을 캐면 이어진 같은 원목이 전부 순차 제거됩니다. **가지와 대각선 줄기까지 따라갑니다**
— 참나무·정글나무 가지, 아카시아의 비스듬한 줄기, 2x2 굵은 나무 모두 한 번에 잘립니다.

번지는 범위는 두 가지로 묶여 있습니다.
- **캔 블록보다 아래로는 내려가지 않습니다** — 바닥에 깔린 원목이나 옆 나무 밑동을 파먹지 않습니다.
- **가로 반경 제한**(`max-horizontal-radius`, 기본 5) — 원목이 줄지어 있어도 옆 나무로 옮겨붙지 않습니다.

큐를 틱당 몇 블록씩만 소비하므로 대형 나무에서도 렉이 없습니다.
웅크리면 비활성, 크리에이티브 제외, `max-blocks` 상한 적용.
6면 원목(`oak_wood` 등)은 기본 목록에 없어 원목 건축물은 안전합니다.

### 근접 채팅
설정한 거리 안의 플레이어에게만 채팅이 전달됩니다. 플레이어가 입력한 `&` 색코드는
그대로 텍스트로 나가므로 채팅을 꾸미거나 위장할 수 없습니다.

### 이름표 · 플레이어 목록
머리 위 이름표와 Tab 플레이어 목록에 파티·칭호·레벨·직업이 함께 나옵니다.

```
머리 위   [푸른사슴] [강태공] Alpha Lv.6 전사
Tab 목록  [푸른사슴] [강태공] Alpha Lv.6
```

- 파티가 없거나 칭호·직업이 없으면 **그 조각만 통째로 빠집니다** — 빈 괄호가 남지 않습니다.
- 무엇을 보여줄지는 두 곳에서 따로 정합니다(기본값: 목록에는 직업 미표시).
- 스코어보드 팀 기능으로 붙이므로 **베드락(Geyser)에서도 똑같이 보이고**, 별도 패킷을
  매 틱 쏘지 않습니다. 레벨·파티·직업이 실제로 바뀔 때만 갱신됩니다.
- 접속을 끊으면 붙였던 팀도 같이 지워집니다(월드 스코어보드에 쌓이지 않음).
  다른 플러그인이 이름표를 관리한다면 `features.nameplate` 를 끄면 됩니다.

### 아이템 무게 툴팁
아이템 설명 맨 아래에 작은 회색 글씨로 `무게 8` 한 줄이 붙습니다.

- **1개당 무게**입니다. 개수와 무관한 값이라야 같은 아이템끼리 계속 겹쳐집니다.
- 바닥에 떨어진 아이템, 상자에서 꺼낸 아이템에도 같은 줄이 붙으므로 **묶음이 갈라지지 않습니다.**
- 다른 플러그인이 넣은 설명이나 플레이어가 붙인 이름은 건드리지 않고, 이 한 줄만 관리합니다.
- 무게 설정을 바꾸고 `/rpgcore reload` 하면 줄도 따라서 갱신되고, 기능을 끄면 다시 지워집니다.

### UI
- `/stats` (별칭 `/rpg`, `/rpgstats`) — 상자 GUI. 스탯 배분, 직업, 장비 상태, 모루 조합법 목록.
- `/job` — 직업 선택 GUI. 잠긴 직업은 회색으로 표시됩니다.
- 액션바 HUD — 레벨 / HP / XP / 무게. 장비가 닳았을 때만 `GEAR 87%` 구간이 추가됩니다.

## 명령어

| 명령어 | 권한 | 설명 |
|---|---|---|
| `/stats` | 모두 | 스탯 창 열기 (별칭 `/rpg`) |
| `/job [직업]` | 모두 | 직업 선택 창 / 바로 선택 (별칭 `/class`) |
| `/leaderboard [항목]` | 모두 | 순위표 (별칭 `/top`, `/lb`) |
| `/party <하위명령>` | 모두 | 파티 생성·이름변경·초대·수락·추방·해체·목록 |
| `/p <메시지>` | 모두 | 파티 채팅 |
| `/trade <플레이어>` | 모두 | 거래 요청 (`accept`/`deny`/`cancel`) |
| `/titles [칭호]` | 모두 | 칭호 선택 창 / 바로 착용 (별칭 `/칭호`, `off` 로 해제) |
| `/achievements` | 모두 | 업적 진행도 (별칭 `/ach`, `/업적`) |
| `/collection` | 모두 | 수집 도감 (별칭 `/dex`, `/도감`) |
| `/duel <플레이어> [건 것]` | 모두 | 대결 신청 (`accept`/`deny`/`forfeit`, 별칭 `/대결`) |
| `/gold [플레이어]` | 모두 | 골드 잔액 (별칭 `/money`, `/골드`) |
| `/pay <플레이어> <금액>` | 모두 | 골드 보내기 (별칭 `/송금`) |
| `/rpgcore reload` | `rpgcore.admin` | 설정 파일 다시 읽기 |
| `/rpgcore check` | `rpgcore.admin` | 각 기능이 실제로 뭘 읽었는지 점검 |
| `/rpgcore recipes` | `rpgcore.admin` | 모루 조합법 목록 |
| `/rpgcore givexp <player> <amount>` | `rpgcore.admin` | XP 지급 |
| `/rpgcore givegold <player> <amount>` | `rpgcore.admin` | 골드 지급 (음수면 회수) |
| `/rpgcore reset <player>` | `rpgcore.admin` | 스탯·업적·칭호·도감 초기화 (골드는 유지) |

## 설정

설정은 `plugins/RPGCorePlugin/` 안의 **파일 3개**로 나뉩니다. 목적이 달라서 나눠 뒀으니,
바꾸고 싶은 게 어디 있는지만 알면 됩니다.

| 파일 | 무엇이 들어 있나 | 언제 여나 |
|---|---|---|
| **`settings.yml`** | 최대 레벨, 경험치 곡선, 인챈트 한계, 기능 on/off | **대부분 여기만 보면 됩니다** |
| **`jobs.yml`** | 직업 정의 | 직업을 만들거나 고칠 때 |
| **`achievements.yml`** | 업적과 그 보상·칭호 | 목표를 만들거나 고칠 때 |
| **`collection.yml`** | 도감 분류와 수집 대상 | 도감에 항목을 넣을 때 |
| `config.yml` | 아이템 무게 분류, 모루 조합법, 벌목 대상, 골드, 대결, GUI, 채팅 | 세부 조정이 필요할 때 |

> `records.yml` 은 플러그인이 직접 씁니다. '서버 최초' 업적을 누가 가져갔는지 담겨
> 있으니 손으로 고치지 마세요. 지우면 그 기록이 다시 열립니다.

고친 뒤 **`/rpgcore reload`** 하면 재시작 없이 반영됩니다.
지금 설정이 실제로 어떻게 읽혔는지는 **`/rpgcore check`** 로 확인하세요.

```
===== RPGCore 점검 =====
 O 직업 - 5종 (jobs.yml)
 O 연쇄 벌목 - 22종 원목 / 7종 도구, 반경 5, 최대 256블록
 O 모루 강화 - 7종 조합법
 O 소지 무게 - 242종 분류
 O 머리 위 이름표 - 파티 + 레벨 + 직업
 O 플레이어 목록 - 파티 + 레벨
 O 무게 툴팁 - &8무게 n
 O 업적 - 13종, 칭호 16종
 O 도감 - 5개 분류 / 60종
 O 대결 - 최대 1000골드, 제한 300초, 진행 중 0건
 ...
레벨: 최대 무제한, 곡선 x1.0 (Lv2 100 / Lv10 500 / Lv20 1000 XP)
인챈트 한계: 최대 255
```

> 예전 버전에서 올라온 서버라면 `config.yml` 이 옛 구조일 수 있습니다. jar 안의 기본값이
> 항상 뒤에 깔리므로 **빠진 항목은 기본값으로 동작하고 파일은 그대로 둡니다.**
> `settings.yml` 이 처음 만들어질 때 `config.yml` 에 직접 적어 둔 값(경험치, 기능 on/off 등)은
> 자동으로 옮겨오고 로그에 알려줍니다.

### settings.yml — 자주 바꾸는 것

```yaml
level:
  max: 0                # 최대 레벨. 0 이면 제한 없음
  xp-base: 100          # 레벨 2 가 되는 데 필요한 XP
  xp-growth: 50         # 레벨이 오를 때마다 추가로 붙는 XP
  xp-multiplier: 1.0    # 레벨당 곱해지는 배수
  points-per-level: 1
  starting-points: 5

xp-sources:
  per-mob-kill: 10
  per-tree-log: 1

enchant:
  respect-vanilla-limits: false   # true 면 바닐라 최대 레벨까지만
  max-level: 255                  # 위가 false 일 때의 상한

features:               # 끄면 관련 명령어도 막힙니다
  jobs: true
  parties: true
  trading: true
  leaderboard: true
  tree-felling: true
  durability-scaling: true
  anvil-recipes: true
  proximity-chat: true
  hud: true
  nameplate: true         # 머리 위 이름표
  tablist: true           # 플레이어 목록(Tab)
  item-weight-lore: true  # 아이템 설명의 무게 한 줄
  achievements: true      # 업적과 칭호
  collection: true        # 수집 도감
  duels: true             # 1:1 대결
```

**최대 레벨** — `level.max` 에 닿으면 더 이상 XP 가 쌓이지 않습니다. 남은 XP 를 쌓아두지 않으므로,
나중에 상한을 올려도 전원이 한꺼번에 레벨업하는 일은 없습니다.

**레벨 배수** — 다음 레벨까지 필요한 XP 는 이렇게 계산됩니다.

```
(xp-base + (현재레벨-1) × xp-growth) × (xp-multiplier ^ (현재레벨-1))
```

| xp-multiplier | Lv2 | Lv10 | Lv20 |
|---|---|---|---|
| `1.0` (직선) | 100 | 500 | 1,000 |
| `1.05` (완만) | 100 | 769 | 2,117 |
| `1.5` (아주 가파름) | 100 | 12,814 | 1,477,892 |

**인챈트 한계** — 모루 커스텀 강화로 올릴 수 있는 상한입니다.
`respect-vanilla-limits: true` 면 날카로움 5, 보호 4 처럼 바닐라 최대치에서 멈춥니다.
`false` 면 `max-level`(최대 255)까지 계속 올라갑니다. 조합법마다 `max-level` 을 따로 적으면
**둘 중 낮은 쪽**이 적용됩니다.

### jobs.yml — 직업 만들기

`list:` 아래에 블록을 하나 더 붙이면 끝입니다. 코드 수정은 필요 없습니다.

```yaml
allow-change: true        # false 면 한 번 고르면 못 바꿈
change-cost-levels: 0     # 변경 시 소모할 바닐라 경험치 레벨
menu-title: "&8직업 선택"

list:
  mage:                          # 이게 /job <id> 의 id 입니다
    name: "&5마법사"
    icon: minecraft:blaze_rod
    description:
      - "&7설명 줄."
    min-level: 15                # 이 RPG 레벨부터 선택 가능
    stat-bonus:                  # 직접 찍은 포인트 위에 더해짐
      luck: 5
      dex: 2
    weight-bonus: 0              # 최대 소지무게에 더하기
    xp-multiplier: 1.2           # 획득 경험치 배율
    attributes:
      add:                       # 고정값 더하기
        max_health: 2
      multiply:                  # 합계에 비율 곱하기 (0.1 = +10%)
        movement_speed: 0.05
```

`attributes` 에는 **바닐라 어트리뷰트 ID 를 그대로** 씁니다:
`max_health` `attack_damage` `attack_speed` `armor` `armor_toughness`
`movement_speed` `knockback_resistance` `block_break_speed` `luck` `jump_strength` 등.
이 서버에 없는 ID 는 경고만 남기고 무시하므로 설정이 통째로 깨지지 않습니다.

### config.yml — 세부 설정

| 섹션 | 주요 값 |
|---|---|
| `stats` | 스탯 1당 배율 — `attack-damage-per-str`, `hp-per-vit`, `movement-speed-per-agi` 등 |
| `weight` | `base-capacity`, `capacity-per-str`, `default-item-weight`, `tiers.*` |
| `tree-felling` | `max-blocks`, `blocks-per-tick`, **`max-horizontal-radius`**, `sneak-disables`, `damage-tool`, `logs`, `tools` |
| `durability-scaling` | `full-performance-above`, `minimum-performance`, `affects.*` |
| `anvil` | `recipes.*` — 대상·재료·수량·인챈트·수리량 |
| `party` | `max-size`, `name-max-length`, `friendly-fire`, `chat-prefix`(`%party%`), `xp.*` |
| `trade` | `request-timeout-seconds`, `max-distance`, `title` |
| `leaderboard` | `size`, `cache-seconds` |
| `proximity-chat` | `range`, `format`, `hide-out-of-range` |
| `gui` | `title`, `size`(27~54의 9의 배수) |
| `display` | 이름표·플레이어 목록 서식 (아래) |

#### 아이템 목록 쓰는 법

`weight.tiers.*.items`, `tree-felling.logs` / `tools`, `anvil` 의 `target` 은 모두 같은 문법입니다.

```yaml
items:
  - minecraft:stone            # 낱개 ID
  - '#minecraft:planks'        # 바닐라 태그 (그 태그에 속한 전부)
  - '#myserver:custom_gear'    # 서버에 깔린 다른 데이터팩 태그
```

이 서버 버전에 없는 항목은 **경고만 남기고 건너뜁니다.** 목록 전체가 죽지 않으므로
설정 하나로 여러 버전을 커버할 수 있습니다.

#### 모루 조합법 추가하기

```yaml
anvil:
  recipes:
    my-recipe:                                        # 아무 키나 가능
      name: "&b내 조합"                                # /rpgcore recipes 표시용
      target: ['#minecraft:enchantable/sharp_weapon']  # 왼쪽 칸에 올 수 있는 것
      ingredient: minecraft:flint                      # 오른쪽 칸 재료
      ingredient-amount: 2
      repair-percent: 0                                # 최대 내구도의 N% 회복
      level-cost: 0                                    # 소모할 경험치 레벨
      enchantments:
        sharpness: 1                                   # 1회당 올릴 레벨
```

- 자주 쓰는 대상 태그: `#minecraft:enchantable/sharp_weapon`(검·도끼),
  `#minecraft:enchantable/mining`(채굴 도구), `#minecraft:enchantable/armor`(방어구),
  `#minecraft:enchantable/durability`(내구도 있는 장비 전부).
- **재료 선택 주의**: 그 장비의 바닐라 수리 재료(철 갑옷 + 철 주괴 등)를 쓰면 바닐라 수리를
  덮어씁니다. 기본 조합법이 철 '주괴' 대신 철 '블록', 숫돌을 쓰는 이유입니다.

#### 이름표 / 플레이어 목록 꾸미기

```yaml
weight:
  lore-format: "&8무게 %weight%"   # 아이템 툴팁 한 줄. ""(빈 값)이면 표시 안 함

display:
  # 조각별 서식. %party% %title% %level% %job% 이 바뀝니다.
  party-format: "&8[&d%party%&8]"
  title-format: "%title%"          # 칭호 문구는 achievements/collection.yml 에서 정합니다
  level-format: "&7Lv.&e%level%"
  job-format: "&b%job%"

  nameplate:        # 머리 위
    show-party: true
    show-title: true
    show-level: true
    show-job: true
  tablist:          # Tab 목록
    show-party: true
    show-title: true
    show-level: true
    show-job: false
```

- 파티와 칭호는 **이름 앞**, 레벨과 직업은 **이름 뒤**에 붙습니다. 사이 공백은 자동이라
  서식에 넣지 않아도 됩니다.
- 해당 정보가 없으면(파티 없음·직업 없음) 그 조각은 아예 빠집니다.
- 서식을 `""` 로 비우면 양쪽 모두에서 그 조각이 사라집니다.
- 이름표 자체를 끄려면 `settings.yml` 의 `features.nameplate`, 목록은 `features.tablist`,
  무게 툴팁은 `features.item-weight-lore` 를 `false` 로 두세요.

#### 업적 만들기 (achievements.yml)

```yaml
announce-to-server: true      # 칭호가 걸린 업적/서버 최초 업적을 전체 공지

achievements:
  my-goal:                    # 아무 키나 가능. 칭호 id 로도 쓰입니다
    display: "&e초보 사냥꾼"    # 달성 시 보여줄 이름
    description: "몬스터 100마리 처치"
    icon: minecraft:wooden_sword
    type: mob-kills           # 아래 측정 기준 중 하나
    goal: 100
    first-only: false         # true 면 서버에서 딱 한 명만
    title: "&e[사냥꾼]"        # 비우면 보상만 주고 칭호는 없음
    reward-gold: 100
    reward-xp: 50
```

측정 기준(`type`)은 `mob-kills`, `blocks-mined`, `ores-mined`, `logs-chopped`,
`fish-caught`, `duels-won`, `gold-earned`, `collected`, `level` 입니다.
없는 기준을 적으면 그 업적만 건너뛰고 **쓸 수 있는 목록을 로그에 적어 줍니다.**

무엇을 '광석'과 '원목'으로 셀지는 `config.yml` 에서 바꿀 수 있습니다. 비워 두면
바닐라 기본값(광석 19종 / 원목 44종)이고, 모드나 데이터팩 블록을 세고 싶을 때만 채웁니다.

```yaml
progress:
  ore-blocks: []    # 예: ['#minecraft:iron_ores', 'mypack:mithril_ore']
  log-blocks: []
```

기준값은 전부 스코어보드(`rpgcore.kills` 등)에 저장되므로 `/scoreboard players get`
으로 직접 확인할 수 있고, 오프라인 플레이어의 값도 남아 있습니다.

#### 도감 항목 추가하기 (collection.yml)

```yaml
categories:
  my-page:
    display: "&6원목"
    icon: minecraft:oak_log
    items:                      # config.yml 과 같은 문법(#태그 / 낱개 ID)
      - '#minecraft:logs'
      - minecraft:bamboo_block
    title: "&2[벌목왕]"          # 다 모았을 때 주는 칭호 (없어도 됨)
    reward-gold: 400
    reward-xp: 200
```

- **한 아이템은 한 분류에만** 들어갑니다. 두 곳에 적으면 먼저 나온 쪽만 인정되고
  로그에 경고가 남습니다.
- 한 장에 45종까지 화면에 들어갑니다. 태그를 썼다면 `/rpgcore check` 로 실제 종 수를
  확인하세요.

#### 골드와 대결 (config.yml)

```yaml
economy:
  starting-gold: 0
  gold-per-mob-kill: 2
  gold-per-level-up: 50
  symbol: "G"
  allow-player-transfer: true   # /pay 로 주고받기

duel:
  request-timeout-seconds: 60
  max-distance: 32          # 신청·수락 시 최대 거리. 0 이면 같은 월드면 됨
  max-gold-wager: 1000      # 0 이면 제한 없음
  max-duration-seconds: 300 # 지나면 무승부, 건 것은 그대로 반환
  countdown-seconds: 3      # 그동안은 서로 때릴 수 없음. 0 이면 즉시 시작
  heal-before-start: true   # 시작 시 양쪽 체력을 채움. 끝나면 시작 전 체력으로 복귀
  cooldown-seconds: 30      # 대결 후 다음 대결까지의 대기 시간. 0 이면 제한 없음
  announce-result: true
```

대결은 월드의 PvP 가 켜져 있어야 합니다(`server.properties` 의 `pvp=true`).
꺼져 있으면 신청 단계에서 이유와 함께 막힙니다.

#### 벌목 범위 조정

```yaml
tree-felling:
  max-blocks: 256              # 한 그루당 상한
  blocks-per-tick: 12          # 틱당 제거 수
  max-horizontal-radius: 5     # 원점에서 가로로 이만큼까지만 번짐
```

`max-horizontal-radius` 가 가지를 따라 어디까지 번질지를 정합니다. 기본 5 면 아카시아처럼
가지가 긴 나무도 다 잘리면서 옆 나무로는 옮겨붙지 않습니다. 나무가 빽빽한 서버라면 3~4 로
줄이고, 거대 정글나무를 통째로 자르고 싶으면 7~8 로 올리세요.

### 새 스탯 추가하기

`stats/StatType.java` 에 한 줄 추가하면 GUI 슬롯과 스코어보드 오브젝티브가 자동 생성됩니다.
효과만 `StatsService.recalculate()` 에 적으면 됩니다.

단, **스탯 GUI 가 잡아 둔 칸은 6개(슬롯 10~15)** 입니다. 슬롯 16 부터는 HP·무게·포인트·
장비 칸이라 스탯이 넘어가면 겹칩니다. 7번째 스탯을 넣으면 화면에 그리지 않고 기동 로그에
경고를 남기므로, 그 전에 `gui/StatsMenu.java` 의 배치를 먼저 넓혀 주세요.

## 베드락(Geyser/Floodgate) 호환

모든 로직이 서버 사이드라 **베드락에서 별도 설정 없이 그대로 동작합니다.**
UI도 일반 상자 GUI 하나만 쓰므로 Geyser가 알아서 베드락 화면으로 번역합니다.
아이콘도 양쪽에서 동일하게 렌더되는 것만 씁니다.

유일한 차이는 **점프력 페널티**입니다. 베드락에는 플레이어 jump_strength 어트리뷰트가
없어 적용되지 않습니다. 이동속도 페널티와 허기는 그대로 걸립니다.

## 데이터 저장

플레이어 상태는 `rpgcore.*` **바닐라 스코어보드 오브젝티브**에 미러링되어 월드와 함께
저장됩니다. 별도 데이터 파일이 없고, 다른 데이터팩이나 커맨드 블록에서 읽을 수도 있습니다.

```
/scoreboard players get <player> rpgcore.level
```

접속 중인 플레이어의 값을 손으로 고치는 것은 소용이 없습니다. 플러그인은 값이 실제로
바뀔 때만 기록하므로 잠시 남아 있는 것처럼 보여도, 그 값이 다음에 바뀌는 순간과 접속을
종료할 때 덮어써집니다. 오프라인일 때 고치거나 `/rpgcore givexp` · `/rpgcore givegold` ·
`/rpgcore reset` 을 쓰세요.

**직업**만은 문자열이라 스코어보드에 담을 수 없어, 플레이어의 바닐라 playerdata 안에
저장됩니다(PersistentDataContainer). 역시 별도 파일이 생기지 않습니다.

```
/data get entity <player> data
```

**업적·칭호·도감**도 문자열이라 같은 곳(PersistentDataContainer)에 들어갑니다.
진행도 카운터(`rpgcore.kills`, `rpgcore.ores` …)와 **골드**(`rpgcore.gold`)는 정수라
스코어보드에 그대로 미러링됩니다. '서버 최초' 업적의 주인만 `records.yml` 에 남습니다.

**파티**는 이름·구성원을 가진 그룹이라 스코어보드에도 개인 데이터에도 맞지 않아,
`plugins/RPGCorePlugin/parties.yml` 에 저장됩니다. 파티가 바뀔 때마다 즉시 기록되므로
서버가 갑자기 내려가도 남습니다. 진행 중인 거래는 저장되지 않고, 서버 종료 시 물건을
주인에게 돌려준 뒤 정리됩니다.

어트리뷰트 모디파이어 네임스페이스는 플러그인 이름에서 나오므로 `rpgcoreplugin:*` 입니다.

```
/attribute <player> minecraft:movement_speed modifier value get rpgcoreplugin:weight_speed
```

## 검증

Paper 26.2 (빌드 121, Java 25) 실서버에 봇 3명을 접속시켜 위 기능을 직접 확인했습니다.
구동 로그 예외 0건.

- 직업 보너스 적용과 직업 변경 시 이전 보너스 해제, 재접속 후 직업 유지
- 순위표 정렬과 본인 순위 표시
- 파티: 이름 중복·길이 검증, **서버 재시작 후 파티 유지**, 오프라인 멤버 표시·추방,
  파티장 승계, 아군 공격·화살 차단
- 파티 XP: 3명 접속 시 각 +4, 한 명이 나가면 각 +5 이고 **나간 사람은 그대로**
- 거래: 다이아 5 ↔ 에메랄드 7 정확히 교환, 상대 칸 탈취 차단, 공간 부족 시 미진행,
  취소·창 닫기·접속 종료 시 물건 전량 반환
- 연쇄 벌목: 곧은 기둥 / 잎 달린 나무 / 2x2 정글 / **가지 달린 나무** / **아카시아식 대각선 줄기** /
  자작·껍질벗긴·네더 줄기 / 나무 도끼 / 중간 캐기 / `max-blocks` 상한 — 전부 확인.
  옆 나무는 번지지 않고(반경 5), 캔 블록 아래로도 내려가지 않음
- 설정: 최대 레벨 3 적용 시 Lv.3 에서 XP 정지, 배수 1.5 적용 시 Lv20 요구 XP 1,000 → 1,477,892,
  `respect-vanilla-limits: true` 에서 날카로움 5 추가 강화 거부
- 이름표/목록: 파티 가입·이름 변경·탈퇴, 직업 선택, 레벨업이 두 곳에 모두 반영.
  기능을 끄면 팀이 사라지고(`team list` → 없음) 목록 이름도 원래대로, 다시 켜면 복구.
  접속 종료 시 팀 제거, 단 다른 구성원이 들어 있는 팀은 건드리지 않음
- 무게 툴팁: 아이템·도구 모두에 기울임 없는 회색 한 줄, 바닥에 떨어진 아이템도 표기됨.
  표기된 5개 + 표기 안 된 7개를 주워 **한 칸 12개로 합쳐짐**, 상자에서 꺼낸 9개도 합쳐져 14개.
  기능을 끄면 줄과 표식이 모두 제거됨
- 업적: 레벨/골드/채굴 업적 달성과 보상 지급, 칭호가 이름표·목록에 즉시 반영.
  **서버 최초 업적을 먼저 가져간 뒤 두 번째 플레이어는 받지 못하고** `/achievements` 에
  "최초: Alpha" 로 표시됨. 철광석 4칸 채굴 → `rpgcore.mined`/`rpgcore.ores` 둘 다 +4
- 도감: 양털 2종 등록 후 진행도 2/16, 물고기 4종을 채우자 완성 보상과 `[강태공]` 칭호가
  들어오고 서버 전체에 공지. `/collection` 목록 → 분류 → 뒤로 가기 클릭 동작
- 대결: 골드 100을 걸고 시작하자 양쪽 잔액에서 빠지고, 치명타가 들어온 순간
  **피해가 취소되어 패자가 144hp로 살아남은 채** 승패가 갈리고 상금이 승자에게 이동.
  다이아 3개씩 건 대결에서는 패자 0개 / 승자 6개로 총량 유지(복제 없음).
  기권·접속 종료 모두 기권패, 같은 아이템을 들지 않은 수락은 거부,
  상한을 넘는 금액·숫자가 아닌 입력도 거부. 파티원끼리도 대결 성립(평소 아군 공격은 차단)
- `/rpgcore reset` 후 업적 0/13, 칭호 미착용, 카운터 0 으로 초기화 (골드는 유지)

Geyser/Floodgate 및 Simple Voice Chat 연동은 해당 서버가 없어 실행 검증하지 못했습니다.

### 2.1.0 검증

2.1.0 은 Paper 26.2 (빌드 123) + Temurin JDK 25 에서 **빌드와 기동을 실제로 확인**했습니다.
플레이어 클라이언트가 없는 환경이라 봇 접속은 하지 못했고, 대신 사람 손이 필요 없는 부분을
서버 안에서 돌아가는 검사 플러그인으로 확인했습니다 — 22개 항목 전부 통과, 기동·리로드·
종료 로그 예외 0건.

- 아이템 동일성: `isSimilar` 가 인챈트된 검 / 닳은 검 / 이름 붙인 검을 맨 검과 구분하고,
  같은 스택의 복사본은 동일하다고 판정 (대결 내기 복제 수정의 근거)
- 내기 설명: 인챈트·내구도·이름이 모두 문구에 나오고, 블록·골드·빈 내기에서도 예외 없음
- 이름 변경 이관: 레벨·골드가 새 이름으로 옮겨지고 옛 이름에는 아무것도 남지 않음
- 보류 내기 파일: 인챈트된 아이템과 골드가 YAML 왕복 후에도 그대로
- `/rpgcore reload` 3회 반복 후에도 RPGCore 예약 태스크 수 불변 (HUD 태스크 누수 없음)
- 설정 파일 5개 중첩 단계까지 중복 키 0건, 코드가 읽는 설정 경로 126개 전부 존재

여전히 사람이 직접 해 봐야 하는 것: 모루 결과물을 실제로 꺼내지는지(`level-cost`),
거래창에서 같은 패킷 배치로 [확정]+[회수] 를 보내는 공격, 그리고 Geyser·음성 연동.

## 구조

```
plugin/
  pom.xml                    의존성 1개 (paper-api, provided)
  src/main/resources/        plugin.yml, settings.yml, jobs.yml,
                             achievements.yml, collection.yml, config.yml
  src/main/java/com/rpgcore/plugin/
    RpgCorePlugin.java       진입점, 커맨드, 반복 태스크 2개
    config/                  settings/jobs/config.yml 타입 뷰 + 기본값 병합
    data/                    PlayerData 캐시 + 스코어보드 미러
    stats/                   StatType, 레벨/배분/어트리뷰트, 세션·XP 리스너
    weight/                  무게 테이블, 계산, 툴팁 표기, 리스너
    gear/                    내구도 → 성능, 투사체 처리
    anvil/                   조합법 로딩·결과 생성·모루 연동
    tree/                    큐 기반 연쇄 벌목
    job/                     직업 정의·선택·어트리뷰트 적용, 선택 GUI
    leaderboard/             스코어보드 미러 기반 순위 집계 (캐시 포함)
    party/                   파티 상태·이름·초대·XP 분배, parties.yml 저장
    progress/                업적·칭호·골드와 진행도 카운터
    collection/              수집 도감 정의·기록·화면
    duel/                    1:1 대결, 건 것의 보관과 정산
    trade/                   공유 상자 UI 기반 1:1 거래
    command/                 /job, /leaderboard, /party, /p, /trade,
                             /titles, /achievements, /collection, /duel, /gold
    display/                 이름표(스코어보드 팀) + 플레이어 목록
    hud/ gui/ chat/          액션바, 상자 GUI, 근접 채팅
    voice/                   Simple Voice Chat 거리 확인 (의존성 없음)
    util/                    스코어보드, 어트리뷰트·인챈트·태그 헬퍼
```
