# 챙겼니 (CheckAgain)

탑승할 때 이름과 소지품을 말로 등록해 두면, 내릴 때 "○○님, ○○ 확인하세요"라고 알려 주는 Pleos 앱.

- Vehicle SDK: 문, 좌석, 기어, 주행거리
- TextToSpeech / SpeechToText SDK (온디바이스)
- 소지품, 확인 답변 해석에만 OpenAI API를 보조로 사용 (선택)

## 요구 사항

- Android Studio, minSdk 34
- Pleos Maven 저장소 접근 (`settings.gradle.kts`에 등록됨)
- 차량 신호는 Pleos 에뮬레이터(Connect v2.1.0, API 34) 또는 Pleos Connect 환경에서 확인

## 빌드

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- 릴리스: `./gradlew assembleRelease`
- 처음 실행 시 마이크, 알림 권한 필요
- Virtual Console 연결: `adb forward tcp:8088 tcp:8088`

## OpenAI 키 (선택)

`local.properties`에 추가. 이 파일은 `.gitignore`에 있어 올라가지 않음.

```properties
OPENAI_API_KEY=sk-...
OPENAI_MODEL=gpt-4o-mini   # 선택, json_schema 지원 모델
```

- 키가 없으면 LLM 호출 없이 규칙 기반으로만 동작. 빌드와 실행에는 문제 없음
- 상단 칩으로 온라인/오프라인 모드 전환. 오프라인이면 키가 있어도 호출 안 함
- 전송 내용: 등록된 이름을 뺀 40자 이하 텍스트. 음성 원본은 전송 안 함
- 이름 단계, 이름 정정, 하차 안내는 항상 기기 안에서 처리
- 7초 안에 응답이 없거나 실패하면 규칙 기반으로 이어서 처리
- 키는 빌드에 포함되므로 APK를 배포했다면 사용 후 키 폐기

## 테스트

```bash
./gradlew testDebugUnitTest
```

`TextParser` 검증 (이름, 짐, 취소, 되묻기, 소지품 정정)

## 화면

- 메인: 차량 위에서 본 그림(좌석 5곳, 트렁크), 등록 목록, 켜짐/꺼짐, 모드 칩, 알림 버튼
- 등록된 자리는 파란색, 앉아 있지만 미등록인 자리는 연한 파란색
- 음성 대화 중 하단에 질문 카드. 마이크가 열려 있으면 입력 소리 크기대로 파형이 움직이고, 조용하면 멈춤
- 확인 질문: "맞아요", "다시 말할게요" 버튼. 질문을 읽는 중에는 "등록 취소" 버튼
- 알림 버튼에 등록, 하차 확인 기록이 쌓이고 새 알림은 개수로 표시. 시스템 알림도 같이 표시
- 켜짐/꺼짐 표시를 누르거나 상단 알림의 "끄기"로 동작 중지
- 개발자 화면은 디버그 빌드에만 포함

## 디버그 (adb)

콘솔에서 신호를 바꿀 수 없을 때 앱 안의 센서 값을 덮어씀. 앱 실행 후 상단이 "켜짐"인지 확인.

```bash
B="adb shell am broadcast -n dev.hjun.checkagain/.DebugReceiver"

$B --es cmd seat --es name ROW_2_RIGHT --ez value true   # 좌석 true 점유 / false 비움
$B --es cmd door --es name ROW_2_RIGHT --ez value true   # 문 true 열림 / false 닫힘
$B --es cmd rolling --ez value true                      # 주행 true / false
$B --es cmd clear                                        # 주입 해제
$B --es cmd reset                                        # 등록 초기화
$B --es cmd notice --es name register                    # register, alight, info, warn
```

- 좌석: `ROW_1_RIGHT` `ROW_2_LEFT` `ROW_2_CENTER` `ROW_2_RIGHT`
- 문: `ROW_1_RIGHT` `ROW_2_LEFT` `ROW_2_RIGHT` `REAR`(트렁크)
- 주입값은 `clear` 전까지 콘솔의 실제 값보다 우선

시나리오

| 상황 | 순서 |
| --- | --- |
| 승차, 등록 | 문 열림 → 좌석 점유 → 문 닫힘. 이름, 소지품 답변 후 "맞아요". "짐"만 말하면 짐으로 등록 |
| 되묻기 | "뭐?", "못 들었어"라고 하면 같은 질문 반복 |
| 하차 | 문 열림 → 좌석 비움 → 문 닫힘. "○○님, ○○ 확인하세요" |
| 트렁크 | `REAR` 열림 → 닫힘 후 누구 짐인지 질문. 주행 후 다시 열면 안내 |

이전 개발자 화면: `adb shell am start -n dev.hjun.checkagain/.ProbeActivity` (디버그 빌드)

## 참고

- 등록은 앱을 다시 켜도 유지. 하차를 감지 못한 등록은 24시간 뒤 삭제
- 운전석 문, 좌석 변화는 무시
- 주행 중에는 질문, 안내 안 함
