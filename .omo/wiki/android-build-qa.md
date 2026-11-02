# Android 빌드와 화면 검증

확인일: 2026-11-02. 출처: [NATIVE-AUTH-UI-001](../../docs/tasks/NATIVE-AUTH-UI-001.md)의 signup logo follow-up과 같은 작업의 실행 결과.

## JDK

- 사실: 이 PC의 기본 `JAVA_HOME`은 JDK 25다. 프로젝트 기준은 JDK 17이므로 빌드 프로세스에서만 `C:\Program Files\Java\jdk-17`로 지정한다.

## 시작 화면 테마와 Lint

- 사실: 공통 테마는 `Base.Theme.STOG`이고 `Theme.STOG`가 이를 상속한다. API 31 전용 시작 화면 항목은 `values-v31`, `values-night-v31`에만 둔다. 공통 `values`에 다시 넣으면 Lint가 오류로 실패한다 (최소 지원 API 23).
- 미확인: 시작 화면의 실제 표시와 API 31 미만 기기 실행.

## Windows에서 Gradle 실행

- 사실: PowerShell 명령을 문자열로 넘겼을 때 경로의 역슬래시가 사라져 Gradle이 실행되지 않았는데도 종료 코드는 0이었다. 종료 코드만 보지 말고 출력에서 빌드 결과를 확인한다.
- 해결: 스크립트를 UTF-16LE Base64로 인코딩해 `powershell.exe -NoProfile -EncodedCommand`로 실행했다. `cmd /c`로 실행할 때는 `gradlew.bat`을 절대 경로로 지정해야 찾는다.

## 에뮬레이터 확인

- 사실: Pixel_8 `emulator-5554`는 1080x2400, 밀도 420이다. 96dp는 252px로 표시된다.
- 방법: 화면 요소 위치는 `adb exec-out uiautomator dump /dev/tty`로, 화면은 `adb exec-out screencap -p`로 얻는다. 앱 데이터를 지우지 않으려면 `adb install -r`을 쓴다.
- 주의: 글꼴 배율과 야간 모드를 바꿨다면 끝날 때 복원한다. 기본값은 글꼴 1.0, 야간 모드 auto였다.
