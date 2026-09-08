# RPGCore

Paper 서버용 RPG 데이터팩 + 보조 플러그인. 최신 마인크래프트 명령어 문법(데이터 컴포넌트, `execute if items`, 매크로 함수 등, 1.20.5+)을 사용합니다.

이 저장소는 두 부분으로 나뉩니다.

- **`datapack/`** — 스탯/레벨, 무게 시스템, 나무 연쇄 벌목, HUD, 벡스트 기반 메뉴까지 전부 **순수 바닐라 데이터팩**만으로 동작합니다. 플러그인 없이도 완전히 작동합니다.
- **`plugin/`** — 데이터팩이 할 수 없는 두 가지(진짜 클릭형 GUI, 채팅/음성 패킷 제어)를 보강하는 선택적 Paper 플러그인입니다.

## 왜 두 부분으로 나눴는가 (중요, 먼저 읽어주세요)

바닐라 데이터팩은 명령어(mcfunction)만으로 동작하며 다음을 할 수 없습니다.

1. **채팅 패킷을 가로채거나 재전송할 수 없음** → "근접하면 대화 가능"이라는 요구사항(텍스트든 음성이든)은 서버가 채팅/오디오 패킷 자체를 제어해야 하므로 **플러그인(또는 모드) 없이는 원천적으로 불가능**합니다.
2. **플레이어 조작 없이 인벤토리 GUI를 강제로 열 수 없음** → 데이터팩만으로는 클릭 가능한 상자 UI를 강제로 띄울 수 없어서, 데이터팩에는 `/trigger`로 여는 **텍스트 기반 클릭형 메뉴**(tellraw)를 만들었고, 진짜 상자 GUI는 플러그인이 제공합니다.

그래서:
- **실제 음성(오디오) 채팅**은 [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) 같은 검증된 음성 모드/플러그인을 설치하는 것이 정답입니다. 이 모드 자체가 "근접하면 들리고 멀어지면 안 들리는" 근접 음성 채팅을 기본 제공합니다. `plugin/`은 이 플러그인이 설치되어 있으면 자동으로 감지해 연동을 시도합니다(`voice/SimpleVoiceChatHook.java`).
- **근접 텍스트 채팅**은 `plugin/`이 자체적으로 구현합니다(`chat/ProximityChatListener.java`) — 별도 모드 없이 동작하며, 클라이언트 모드 설치가 필요 없습니다.
- **스탯/레벨 UI**는 데이터팩만으로도(`/trigger rpgcore.menu`) 동작하고, 플러그인을 넣으면 진짜 클릭형 상자 GUI(`/stats`)로 업그레이드됩니다.

## 설치

### 1) 데이터팩만 사용 (플러그인 없이)

1. `datapack/` 폴더를 서버의 `world/datapacks/rpgcore/`로 복사합니다.
2. `datapack/pack.mcmeta`의 `pack_format`을 서버의 실제 마인크래프트 버전에 맞는 값으로 수정하세요. (버전별 정확한 pack_format 번호는 서버 시작 로그 또는 [Minecraft Wiki - Pack format](https://minecraft.wiki/w/Pack_format)에서 확인) 이 저장소는 "1.26.2"라는 버전 정보를 확인할 수 없어 최신 명령어 문법(1.20.5+ 기준)으로 작성되었습니다.
3. 서버를 켜거나 `/reload`를 실행합니다.

### 2) 플러그인까지 사용 (권장)

1. 위 데이터팩 설치를 먼저 합니다.
2. `plugin/`에서 `mvn package`로 빌드합니다 (`plugin/target/rpgcore-plugin-1.0.0.jar` 생성). 이 샌드박스 환경은 외부 Maven 저장소 접근이 막혀있어 여기서 직접 빌드/검증하지 못했습니다 — 인터넷이 되는 환경에서 빌드해 주세요.
3. 생성된 jar를 서버 `plugins/` 폴더에 넣고 재시작합니다.
4. (선택) 진짜 음성 채팅을 원하면 [Simple Voice Chat (Bukkit/Paper 빌드)](https://modrinth.com/plugin/simple-voice-chat)도 `plugins/`에 함께 설치하세요. 클라이언트도 같은 모드를 설치해야 음성이 들립니다. 서버 쪽 근접 거리는 `plugins/voicechat/voicechat-server.properties`의 `voice_chat_distance` 값을 `plugin/src/main/resources/config.yml`의 `proximity-chat.range`(기본 24블록)와 맞춰주세요.

## 기능

### 1. RPG 스탯 시스템
- STR / DEX / VIT / AGI / LUCK 5개 기본 스탯, 레벨업마다 포인트 1개 지급 (초기 5개)
- 레벨/경험치, 각 스탯은 실제 바닐라 어트리뷰트에 반영됩니다.
  - VIT → `minecraft:max_health` (진짜 최대 체력 증가)
  - STR → `minecraft:attack_damage` 보너스, 무게 최대치(운반 가능량) 증가
  - AGI → `minecraft:movement_speed`, `minecraft:jump_strength` 보너스
  - LUCK → `minecraft:luck` (전리품/낚시 등에 영향)
- 관련 파일: `datapack/data/rpgcore/function/stats/`

### 2. 소지 무게(Weight) 시스템
- 인벤토리 + 방어구 + 보조 손 슬롯(총 41칸)을 0.5초마다 스캔해 무게를 계산합니다.
- 아이템은 `#rpgcore:weight_light/medium/heavy/very_heavy` 4개 태그로 분류되며(각각 1/3/8/20점), 서버 운영자가 얼마든지 아이템을 추가/삭제할 수 있습니다.
- 무게/최대무게 비율에 따라 4단계로 이동속도·점프력에 실제 어트리뷰트 페널티를 걸고, 과적재 시 허기(`minecraft:hunger`) 이펙트가 추가로 붙습니다.
- 관련 파일: `datapack/data/rpgcore/function/weight/`, 태그: `datapack/data/rpgcore/tags/item/weight_*.json`

### 3. 나무 연쇄 벌목
- 도끼(`#rpgcore:treefell_tool`, 기본값 `#minecraft:axes`)로 원목을 캐면, 그 블록을 기준으로 위쪽/옆으로 연결된 같은 종류의 원목이 전부 자동으로 부서집니다(2x2 굵은 나무의 첫 줄까지 인식, 그 위로는 위쪽으로만 전파해 옆에 있는 다른 나무를 잘못 건드리지 않습니다).
- 원목마다 로그 아이템 1개씩 정상적으로 드랍됩니다.
- 완전히 데이터 기반이라 나무 종류별 코드가 없습니다 — 새 원목 종류를 추가하려면 `data/minecraft/loot_table/blocks/`에 loot table 오버라이드 파일 하나만 추가하면 됩니다 (`CONFIG.md` 참고).
- 관련 파일: `datapack/data/rpgcore/function/treefell/`, `datapack/data/minecraft/loot_table/blocks/*.json`

### 4. 근접 대화 (텍스트 + 음성)
- 데이터팩: 반경 내 플레이어 존재 여부를 감지해 액션바/채팅 힌트를 보여줍니다 (`voice/tick.mcfunction`).
- 플러그인: 실제 채팅 메시지를 가까운 플레이어에게만 전송하는 근접 텍스트 채팅을 구현합니다 (`chat/ProximityChatListener.java`).
- 진짜 음성 채팅은 Simple Voice Chat 설치를 전제로 합니다 (위 설치 섹션 참고).

### 5. 스탯/레벨 UI
- **데이터팩만 있어도 동작**: `/trigger rpgcore.menu` 실행 시 채팅창에 클릭 가능한 스탯 시트가 뜨고, `[+]` 버튼을 눌러 포인트를 배분할 수 있습니다.
- **플러그인 설치 시**: `/stats` (별칭 `/rpg`, `/rpgstats`) 명령어로 진짜 상자(Chest) GUI가 열리고, 클릭으로 스탯을 배분할 수 있습니다. GUI는 항상 데이터팩의 `/trigger`만 호출하므로 규칙(스탯 계산식 등)은 데이터팩이 유일한 소스입니다.
- 상시 HUD(액션바)로 레벨/HP/XP/무게가 항상 표시됩니다.

## Geyser / Floodgate (베드락 크로스플레이) 호환

이 서버는 Geyser + Floodgate가 설치되어 있으므로, 베드락(모바일/콘솔/윈도우10) 플레이어를 고려해 다음과 같이 맞췄습니다.

### 자동으로 처리되는 것
| 기능 | 베드락에서 |
|---|---|
| 스탯/레벨/경험치, 무게 계산, 연쇄 벌목 | **완전 동일하게 동작** (전부 서버 사이드 명령어라 클라이언트 종류와 무관) |
| 스탯 UI | `/stats` 입력 시 **베드락 네이티브 폼(Form) UI**가 뜹니다 (Floodgate Cumulus 폼). Floodgate가 없거나 API가 안 맞으면 자동으로 상자 GUI로 폴백 |
| 상시 HUD (액션바) | 정상 표시. Geyser 부하를 줄이려고 매 틱 → **5틱(0.25초)마다 갱신**으로 조정 |
| 근접 텍스트 채팅 | 정상 동작 (플러그인이 서버 사이드에서 처리) |
| 무게 페널티(이동속도), 허기/채굴피로 이펙트 | 정상 적용 |
| 스코어보드 이름 매칭 | Floodgate 접두사(기본 `.`)가 붙은 이름도 그대로 사용하므로 데이터팩/플러그인 값이 어긋나지 않습니다 |

### 베드락에서 제약이 있는 것 (의도적으로 우회 처리함)
| 제약 | 원인 | 대응 |
|---|---|---|
| 채팅 글자 클릭(`[+]` 버튼)이 안 됨 | 베드락 프로토콜에 클릭 가능한 채팅 컴포넌트가 없음 (Geyser가 번역 불가) | `/trigger rpgcore.menu` 출력에 **타이핑용 명령어를 함께 표시**. 근본적으로는 `/stats` 네이티브 폼 사용 권장 |
| 점프력 페널티 미적용 | 베드락에는 플레이어 jump_strength 어트리뷰트가 없음 | 이동속도 페널티 + 허기 드레인은 그대로 적용되므로 과적재 페널티는 양쪽 모두 체감됩니다 |
| 이모지 깨짐 | 베드락 폰트에 자바 이모지 글리프 없음 | UI/메시지를 **ASCII + 한글**로만 구성 (🔊 → `[VOICE]`) |
| 플레이어 머리 아이콘 | Floodgate 스킨이 비어 보일 수 있음 | 베드락 플레이어에게는 상자 GUI에서 머리 대신 일반 아이콘 사용 |
| barrier 아이콘 | 베드락에서 표시가 불안정 | 닫기 버튼을 유리판(`RED_STAINED_GLASS_PANE`)으로 변경 |
| **Simple Voice Chat 음성** | SVC는 자바 클라이언트 모드가 필수 → **베드락 플레이어는 음성 채팅 사용 불가** | 베드락 플레이어는 근접 **텍스트** 채팅으로 대체됩니다. (서버 시작 로그에도 안내 출력) 베드락까지 음성을 원하면 Discord 연동 등 외부 수단이 필요합니다 |

### 설치 순서 (Geyser 환경)
1. Geyser-Spigot + Floodgate를 `plugins/`에 설치 (이미 하신 상태).
2. RPGCore 데이터팩을 `world/datapacks/`에 설치.
3. RPGCore 플러그인 jar를 `plugins/`에 설치 — Floodgate보다 나중에 로드되도록 `plugin.yml`에 `softdepend: [voicechat, floodgate, Geyser-Spigot]`이 이미 걸려 있습니다.
4. 서버 시작 로그에서 `Geyser/Floodgate detected - Bedrock players are supported with native form menus.` 를 확인하세요.

## 확장하는 방법

자세한 내용은 [`CONFIG.md`](CONFIG.md)를 참고하세요. 요약:

| 하고 싶은 것 | 수정할 파일 |
|---|---|
| 밸런스(경험치 곡선, 스탯 배율 등) 조정 | `datapack/data/rpgcore/function/load.mcfunction`의 상수들 |
| 새 스탯 추가 | `load.mcfunction`(목표 추가) + `stats/recalc.mcfunction`(효과 계산) + `stats/alloc_*.mcfunction` 패턴 복사 |
| 아이템 무게 분류 변경 | `datapack/data/rpgcore/tags/item/weight_*.json` |
| 벌목 가능한 나무 종류 추가 | `datapack/data/minecraft/loot_table/blocks/`에 파일 추가 (기존 파일 복사 후 id만 교체) |
| 벌목에 필요한 도구 변경 | `datapack/data/rpgcore/tags/item/treefell_tool.json` |
| 근접 채팅/음성 반경 조정 | 데이터팩 `$voice_range` (load.mcfunction) + 플러그인 `config.yml`의 `proximity-chat.range`를 함께 수정 |
| GUI 슬롯/아이콘 변경 | `plugin/.../gui/StatsMenu.java`의 `STAT_SLOTS` (베드락 폼 버튼도 이 목록을 그대로 사용합니다) |
| 베드락 네이티브 폼 끄기 | `plugin/src/main/resources/config.yml`의 `bedrock.use-native-forms: false` |

## 알려진 제약 / 검증 필요 사항

- 이 개발 환경은 실제 마인크래프트 서버를 띄우거나 외부 Maven 저장소(paper-api, floodgate, voicechat)에 접속할 수 없어서 **실서버 테스트와 실제 의존성으로의 빌드는 하지 못했습니다.** 대신 Bukkit/Paper/Adventure/Floodgate/Cumulus/VoiceChat API의 스텁(stub)을 만들어 전체 소스를 `javac -Xlint:all`로 컴파일 검증했고(경고 0), 데이터팩은 함수/태그/스코어보드/매크로 상호 참조를 스크립트로 전수 검사했습니다. 배포 전 테스트 서버 검증은 여전히 권장합니다.
- `pack_format`은 실제 서버 버전에 맞게 조정하세요.
- Simple Voice Chat 연동(`plugin/.../voice/SimpleVoiceChatIntegration.java`)은 SVC의 공개 예제 플러그인 구조를 따랐으나, SVC API는 버전마다 바뀔 수 있습니다. 설치한 SVC 버전과 맞지 않으면 자동으로 안전하게 비활성화되고(다른 기능에는 영향 없음) 로그에 경고만 남습니다 — 실패 시 [voicechat-api-bukkit 예제](https://github.com/henkelmax/voicechat-api-bukkit)를 참고해 클래스를 맞춰주세요.
- 무게 계산은 성능/안정성을 위해 슬롯 단위(칸에 아이템이 있으면 종류당 고정 무게)로 계산하며, 스택 개수는 반영하지 않습니다. 스택 수량까지 반영하고 싶다면 `CONFIG.md`의 설명을 참고하세요.

## 디렉터리 구조

```
datapack/
  pack.mcmeta
  data/minecraft/tags/function/          # load/tick 훅
  data/minecraft/loot_table/blocks/      # 원목 loot table 오버라이드 (벌목 트리거)
  data/rpgcore/tags/                     # 아이템 태그(무게), 도구 태그, 참고용 원목 목록
  data/rpgcore/function/
    load.mcfunction / tick.mcfunction    # 진입점
    player/                              # 최초 접속 초기화
    stats/                               # 레벨/경험치/스탯 배분/파생 능력치
    weight/                              # 무게 스캔 및 페널티 적용
    treefell/                            # 연쇄 벌목 엔진
    ui/                                  # HUD, 텍스트 메뉴
    voice/                               # 근접 감지 훅
    util/                                # 범용 어트리뷰트 설정 유틸
plugin/
  pom.xml
  src/main/java/com/rpgcore/plugin/
    RpgCorePlugin.java                   # 진입점, /stats, /rpgcorereload, 플랫폼 라우팅
    util/RpgScoreboard.java              # 데이터팩 스코어보드 read/trigger 브릿지
    gui/                                 # 상자 GUI (자바용, 베드락 폴백)
    chat/ProximityChatListener.java      # 근접 텍스트 채팅
    platform/                            # Geyser/Floodgate 감지 + 베드락 네이티브 폼
    voice/                               # Simple Voice Chat 소프트 연동
```
