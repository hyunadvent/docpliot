# DocPilot

> 다양한 소스(GitLab, Jira 등)에서 데이터를 읽어 LLM이 분석하고, Confluence 등 대상 플랫폼에 문서를 자동으로 생성·업데이트하는 문서 자동화 에이전트 서버입니다.

---

## 기술 스택

| 항목 | 버전 |
|---|---|
| Java | 17 이상 |
| Spring Boot | 3.5.x |
| Build Tool | Gradle 8.x |
| GitLab 연동 | gitlab4j-api 6.x |
| LLM HTTP 호출 | Spring WebClient (WebFlux) |
| Confluence 연동 | Spring WebClient |
| JSON 설정 파싱 | Jackson ObjectMapper |
| 환경변수 관리 | application.yml + @Value |

---

## 아키텍처 개요

DocPilot은 **Source → LLM → Target** 의 3단계 파이프라인 구조로 설계되어, 새로운 소스나 출력 대상을 독립적으로 추가할 수 있습니다.

```
[ Source ]                  [ Core ]               [ Target ]

 GitLab  ──┐                              ┌──▶  Confluence
           │                              │
 Jira    ──┼──▶  Webhook/Scheduler  ──▶  LLM  ──┼──▶  Slack (추후)
           │                              │
 (확장)  ──┘                              └──▶  (확장)
```

---

## 프로젝트 구조

```
project-root/
├── build.gradle
├── settings.gradle
│
├── config/
│   ├── confluence_structure.json      # Confluence 페이지 구조 설정
│   └── llm_config.json                # LLM 선택 및 API Key 설정
│
└── src/
    └── main/
        ├── resources/
        │   └── application.yml
        │
        └── java/com/yourcompany/docpilot/
            │
            ├── DocPilotApplication.java
            │
            ├── webhook/                                  # 외부 이벤트 수신
            │   └── GitLabWebhookController.java
            │
            ├── scheduler/                                # 주기적 실행 작업
            │   └── JiraReportScheduler.java              # Jira 주간보고 스케줄러 (추후 구현)
            │
            ├── source/                                   # 데이터 수집 모듈
            │   ├── gitlab/
            │   │   └── GitLabSourceService.java          # GitLab 코드/diff 수집
            │   └── jira/
            │       └── JiraSourceService.java            # Jira 이슈/스프린트 수집 (추후 구현)
            │
            ├── target/                                   # 문서 출력 모듈
            │   ├── confluence/
            │   │   └── ConfluenceTargetService.java      # Confluence 페이지 생성/업데이트
            │   └── slack/
            │       └── SlackTargetService.java           # Slack 메시지 발송 (추후 구현)
            │
            ├── llm/                                      # LLM 분석 모듈 (공통)
            │   ├── LLMRouter.java
            │   ├── ClaudeClientService.java
            │   ├── OpenAIClientService.java
            │   └── PromptTemplateService.java
            │
            └── config/                                   # 설정 관리 (공통)
                ├── ConfigLoaderService.java
                ├── WebClientConfig.java
                └── model/
                    ├── ConfluenceStructure.java
                    └── LLMConfig.java
```

---

## 설정 파일

### 1. `config/llm_config.json`

사용할 LLM을 설정합니다. `active: true`인 항목 **단 하나**만 실제 LLM 호출에 사용됩니다.

```json
{
  "llms": [
    {
      "name": "claude",
      "apiKey": "sk-ant-xxxxxxxxxxxxxxxx",
      "model": "claude-opus-4-5",
      "active": true
    },
    {
      "name": "openai",
      "apiKey": "sk-xxxxxxxxxxxxxxxx",
      "model": "gpt-4o",
      "active": false
    }
  ]
}
```

- `name` : LLM 식별자 (`claude` | `openai`)
- `model` : 사용할 모델명
- `apiKey` : 해당 LLM의 API Key
- `active` : `true`인 항목을 사용. 반드시 하나만 `true`로 설정. 0개 또는 2개 이상이면 애플리케이션 기동 시 예외 발생.

### 2. `config/confluence_structure.json`

Confluence에 생성할 페이지 구조를 정의합니다. 코드 수정 없이 이 파일만 변경하면 페이지 구조가 바뀝니다.

```json
{
  "space_key": "DEV",
  "root_page_title": "서비스 문서",
  "pages": [
    {
      "id": "overview",
      "title": "{project_name}",
      "description": "프로젝트 루트 페이지",
      "auto_generate": false,
      "children": [
        {
          "id": "service_overview",
          "title": "서비스 개요 및 구성도",
          "auto_generate": true,
          "prompt_template": "service_overview",
          "trigger": ["push", "merge_request"],
          "target_files": ["README.md", "docker-compose.yml", "*.config.js"]
        },
        {
          "id": "api_spec",
          "title": "API 명세서",
          "auto_generate": true,
          "prompt_template": "api_spec",
          "trigger": ["push", "merge_request"],
          "target_files": ["**/routes/**", "**/controllers/**", "**/views.py", "**/urls.py"]
        },
        {
          "id": "db_schema",
          "title": "DB 스키마",
          "auto_generate": true,
          "prompt_template": "db_schema",
          "trigger": ["push"],
          "target_files": ["**/models/**", "**/migrations/**", "*.sql", "**/schema.prisma"]
        },
        {
          "id": "changelog",
          "title": "변경 이력",
          "auto_generate": true,
          "prompt_template": "changelog",
          "trigger": ["push", "merge_request"],
          "target_files": ["*"],
          "append_mode": true
        }
      ]
    }
  ],
  "branches": {
    "trigger_branches": ["main", "develop"],
    "ignore_branches": ["feature/*", "hotfix/*", "release/*"]
  },
  "options": {
    "require_approval": true,
    "skip_keyword": "[skip-doc]",
    "overwrite_manual_edits": false
  }
}
```

- `pages[].auto_generate` : `false`면 에이전트가 해당 페이지를 수정하지 않음
- `pages[].trigger` : `push` / `merge_request` 중 반응할 이벤트 지정
- `pages[].target_files` : glob 패턴으로 해당 파일이 변경될 때만 페이지 업데이트
- `pages[].append_mode` : `true`면 덮어쓰지 않고 내용을 누적 추가
- `options.skip_keyword` : 커밋 메시지에 포함 시 문서 생성 건너뜀
- `{project_name}` : 런타임에 실제 프로젝트명으로 치환되는 플레이스홀더

---

## 환경변수 (`application.yml`)

```yaml
server:
  port: 8080

# GitLab 설정
gitlab:
  url: https://your-gitlab.com
  private-token: your_gitlab_token
  webhook-secret: your_webhook_secret

# Jira 설정 (추후 구현)
jira:
  url: https://your-jira.com
  username: your_jira_username
  api-token: your_jira_api_token
  project-key: YOUR_PROJECT_KEY
  report-day: FRIDAY
  report-time: "17:00"

# Confluence Server 설정
confluence:
  url: https://your-confluence.com
  username: your_username
  password: your_password
  # Confluence Server 7.9+ 에서는 PAT 사용 권장 (설정 시 PAT 우선 적용)
  pat: your_personal_access_token

# Slack 설정 (추후 구현)
slack:
  webhook-url: https://hooks.slack.com/services/your/webhook/url

# 설정 파일 경로
config:
  llm-config-path: config/llm_config.json
  structure-config-path: config/confluence_structure.json
```

---

## 의존성 (`build.gradle`)

```groovy
plugins {
    id 'org.springframework.boot' version '3.5.x'
    id 'io.spring.dependency-management' version '1.1.x'
    id 'java'
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Spring Boot
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-webflux'    // WebClient
    implementation 'org.springframework.boot:spring-boot-starter-scheduling' // @Scheduled

    // GitLab API
    implementation 'org.gitlab4j:gitlab4j-api:6.0.0'

    // Jackson (JSON 설정 파일 파싱)
    implementation 'com.fasterxml.jackson.core:jackson-databind'

    // Lombok
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'

    // Test
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'io.projectreactor:reactor-test'
}
```

---

## 구현 명세

### `DocPilotApplication.java`
- `@SpringBootApplication`, `@EnableScheduling`으로 선언한다.
- 기동 시 `ConfigLoaderService`를 통해 `llm_config.json`을 로드하고, `active: true`인 LLM이 정확히 1개인지 검증한다.
- 검증 실패 시 `IllegalStateException`을 발생시켜 기동을 중단한다.

---

### `webhook/GitLabWebhookController.java`
- `@RestController`로 선언한다.
- `POST /webhook/gitlab` 엔드포인트를 구현한다.
- 요청 헤더 `X-Gitlab-Token` 값을 `application.yml`의 `gitlab.webhook-secret`과 비교하여 검증한다. 불일치 시 `401 Unauthorized` 반환.
- 요청 헤더 `X-Gitlab-Event` 값으로 이벤트 유형을 구분한다.
    - `Push Hook` → Push 이벤트 처리
    - `Merge Request Hook` → MR 이벤트 처리
    - 그 외 → `200 OK` 반환 후 처리 종료
- payload에서 아래 정보를 추출한다.
    - `projectId`, `projectName`
    - `branch` (Push: `ref` 필드에서 `refs/heads/` 제거, MR: `targetBranch`)
    - 변경된 파일 경로 목록
- 커밋 메시지에 `confluence_structure.json`의 `options.skipKeyword` 값이 포함되면 처리를 중단하고 `200 OK` 반환.
- `ConfigLoaderService.isTriggerBranch(branch)`로 대상 브랜치가 아니면 처리를 중단한다.
- 대상 페이지 목록을 결정한 뒤, 각 페이지에 대해 GitLab 코드 수집 → LLM 분석 → Confluence Upsert 순으로 처리한다.
- 전체 처리는 `@Async`로 비동기 실행하고, Webhook에는 즉시 `200 OK`를 반환한다.

---

### `scheduler/JiraReportScheduler.java` _(추후 구현)_
- `@Component`로 선언한다.
- `@Scheduled(cron = "...")`으로 `application.yml`의 `jira.report-day`, `jira.report-time` 설정에 따라 주기적으로 실행한다.
- 실행 흐름:
    1. `JiraSourceService`로 해당 주의 스프린트 이슈 목록 수집
    2. `PromptTemplateService`의 `weekly_report` 템플릿으로 LLM에 분석 요청
    3. `ConfluenceTargetService`로 주간보고 페이지 생성
    4. (선택) `SlackTargetService`로 생성 완료 알림 발송

---

### `source/gitlab/GitLabSourceService.java`
- `@Service`로 선언한다.
- `gitlab4j-api`의 `GitLabApi`를 사용한다. (`application.yml`의 `gitlab.url`, `gitlab.private-token` 사용)
- 아래 메서드를 구현한다.
    - `List<String> getChangedFiles(Long projectId, String commitSha)` : 커밋에서 변경된 파일 경로 목록 반환
    - `String getFileContent(Long projectId, String filePath, String ref)` : 특정 브랜치 기준 파일 내용 반환. Base64 디코딩 처리 포함.
    - `String getCommitMessage(Long projectId, String commitSha)` : 커밋 메시지 반환

---

### `source/jira/JiraSourceService.java` _(추후 구현)_
- `@Service`로 선언한다.
- `WebClient`를 사용하여 Jira REST API를 호출한다.
- 인증: `Authorization: Basic {base64(username:apiToken)}`
- 아래 메서드를 구현한다.
    - `List<Map<String, Object>> getSprintIssues(String projectKey, String sprintId)` : 스프린트 이슈 목록 반환
    - `List<Map<String, Object>> getWeeklyIssues(String projectKey, LocalDate from, LocalDate to)` : 기간별 이슈 목록 반환
    - `Map<String, Object> getIssueDetail(String issueKey)` : 이슈 상세 정보 반환

---

### `target/confluence/ConfluenceTargetService.java`
- `@Service`로 선언한다.
- `WebClient`를 사용한다.
- `application.yml`의 `confluence.pat`이 설정된 경우 `Authorization: Bearer {pat}` 헤더를 사용하고, 없으면 Basic Auth를 사용한다.
- Confluence Server REST API 기본 경로: `{confluence.url}/rest/api/content`
- 아래 메서드를 구현한다.
    - `Map<String, Object> getPage(String spaceKey, String title)` : 제목으로 페이지 조회. 없으면 `null` 반환.
    - `void createPage(String spaceKey, String title, String body, Long parentId)` : 페이지 신규 생성.
    - `void updatePage(Long pageId, String title, String body, int version)` : 페이지 업데이트. `version`은 기존 버전 +1.
    - `void appendToPage(Long pageId, String title, String additionalBody, int version)` : 기존 body 뒤에 내용 추가.
    - `void upsertPage(String spaceKey, String title, String body, String parentTitle, boolean appendMode)` : 위 메서드들을 조합한 Upsert 처리.

---

### `target/slack/SlackTargetService.java` _(추후 구현)_
- `@Service`로 선언한다.
- `WebClient`를 사용하여 Slack Incoming Webhook URL로 메시지를 발송한다.
- 아래 메서드를 구현한다.
    - `void sendMessage(String text)` : 단순 텍스트 메시지 발송
    - `void sendDocCreatedNotification(String projectName, String pageTitle, String pageUrl)` : 문서 생성 완료 알림 발송

---

### `llm/LLMRouter.java`
- `@Service`로 선언한다.
- `ConfigLoaderService`에서 활성 LLM 설정을 가져와 `ClaudeClientService` 또는 `OpenAIClientService` 중 하나로 라우팅한다.
- 공통 메서드: `String generate(String prompt)`
- `reloadConfig()` 메서드로 런타임 중 `llm_config.json` 변경 사항을 반영할 수 있게 한다.

---

### `llm/ClaudeClientService.java`
- `@Service`로 선언한다.
- `WebClient`를 사용하여 `https://api.anthropic.com/v1/messages`에 POST 요청을 보낸다.
- 요청 헤더: `x-api-key: {apiKey}`, `anthropic-version: 2023-06-01`, `Content-Type: application/json`
- 요청 Body:
  ```json
  {
    "model": "{model}",
    "max_tokens": 4096,
    "messages": [{ "role": "user", "content": "{prompt}" }]
  }
  ```
- 응답의 `content[0].text` 필드를 추출하여 반환한다.
- `apiKey`와 `model`은 `llm_config.json`에서 읽어온 값을 사용한다.

---

### `llm/OpenAIClientService.java`
- `@Service`로 선언한다.
- `WebClient`를 사용하여 `https://api.openai.com/v1/chat/completions`에 POST 요청을 보낸다.
- 요청 헤더: `Authorization: Bearer {apiKey}`, `Content-Type: application/json`
- 요청 Body:
  ```json
  {
    "model": "{model}",
    "max_tokens": 4096,
    "messages": [{ "role": "user", "content": "{prompt}" }]
  }
  ```
- 응답의 `choices[0].message.content` 필드를 추출하여 반환한다.
- `apiKey`와 `model`은 `llm_config.json`에서 읽어온 값을 사용한다.

---

### `llm/PromptTemplateService.java`
- `@Service`로 선언한다.
- 문서 유형별 프롬프트 템플릿을 `Map<String, String>`으로 관리한다.
- `String getPrompt(String templateName, String code, String projectName)` 메서드를 구현한다.
- 지원 템플릿:

| templateName | 설명 | 사용 시점 |
|---|---|---|
| `service_overview` | 서비스 전체 구성도 및 개요 | GitLab Push/MR |
| `api_spec` | REST API 엔드포인트 명세 | GitLab Push/MR |
| `db_schema` | DB 테이블 및 관계 명세 | GitLab Push/MR |
| `changelog` | 변경 이력 (커밋 메시지 기반) | GitLab Push/MR |
| `weekly_report` | Jira 이슈 기반 주간업무 보고서 | Jira 스케줄러 |

- 모든 프롬프트는 LLM에게 **Confluence Storage Format(XML)으로만 응답**하도록 명시한다.
- 코드 내용이 8000자를 초과할 경우 잘라낸 뒤 `...(이하 생략)`을 붙인다.

---

### `config/ConfigLoaderService.java`
- `@Service`로 선언한다.
- Jackson `ObjectMapper`로 `llm_config.json`과 `confluence_structure.json`을 로드한다.
- 설정 파일 경로는 `application.yml`의 `config.llm-config-path`, `config.structure-config-path`에서 읽는다.
- `reload()` 메서드로 런타임 중 재로드가 가능하게 한다.
- 아래 메서드를 구현한다.
    - `LLMConfig.LLM getActiveLLM()` : `active: true`인 LLM 반환. 없거나 2개 이상이면 `IllegalStateException` 발생.
    - `boolean isTriggerBranch(String branch)` : ignore 패턴 우선 확인 후 trigger 브랜치 여부 반환. glob 패턴 매칭은 `AntPathMatcher` 사용.
    - `List<ConfluenceStructure.Page> getPagesForEvent(String eventType, List<String> changedFiles)` : 이벤트 유형과 변경 파일 목록 기준으로 업데이트 필요한 페이지 목록 반환.
    - `String renderTitle(ConfluenceStructure.Page page, String projectName)` : `{project_name}` 플레이스홀더를 실제 프로젝트명으로 치환.

---

### `config/WebClientConfig.java`
- `@Configuration`으로 선언한다.
- `WebClient.Builder` Bean을 등록한다.
- 타임아웃: 연결 10초, 읽기 60초로 설정한다.

---

### `config/model/LLMConfig.java`
- `llm_config.json` 전체 구조를 매핑하는 POJO.
- 내부 클래스 `LLM`: `name`, `apiKey`, `model`, `active` 필드.
- Jackson 역직렬화를 위해 `@JsonProperty("apiKey")` 명시.

---

### `config/model/ConfluenceStructure.java`
- `confluence_structure.json` 전체 구조를 매핑하는 POJO.
- 내부 클래스 `Page`: `id`, `title`, `autoGenerate`, `promptTemplate`, `trigger`, `targetFiles`, `appendMode`, `children` 필드.
- 내부 클래스 `Branches`: `triggerBranches`, `ignoreBranches` 필드.
- 내부 클래스 `Options`: `requireApproval`, `skipKeyword`, `overwriteManualEdits` 필드.

---

## 실행 방법

```bash
# 1. 프로젝트 빌드 (Maven 사용)
./mvnw clean package

# 2. 환경변수 설정
# src/main/resources/application.yml 에 각 서비스 접속 정보 입력

# 3. LLM 설정
# config/llm_config.json 에서 사용할 LLM의 active를 true로 설정하고 apiKey 입력

# 4. 서버 실행
./mvnw spring-boot:run
# 또는 JAR 직접 실행
java -jar target/docpilot-0.0.1-SNAPSHOT.jar

# 5. GitLab Webhook 등록 (아래 "외부 서비스 연동 정보" 참조)
```

---

## 외부 서비스 연동 정보

각 서비스의 접속 정보는 `src/main/resources/application.yml`에 정의합니다.

### GitLab

`application.yml`에 아래 항목을 설정합니다:

| 항목 | yml 키 | 설명 |
|------|--------|------|
| GitLab URL | `gitlab.url` | GitLab 인스턴스 주소 (예: `https://gitlab.yourcompany.com`) |
| Private Token | `gitlab.private-token` | API 호출용 개인 액세스 토큰 |
| Webhook Secret | `gitlab.webhook-secret` | Webhook 요청 검증용 시크릿 |

**Private Token 발급 방법:**
1. GitLab 로그인 → 우측 상단 프로필 아이콘 → **Edit profile**
2. 좌측 메뉴 → **Access Tokens**
3. **Add new token** 클릭
4. Token name 입력, Expiration date 설정
5. Scopes: `api` (전체 API 접근) 체크
6. **Create personal access token** 클릭 → 생성된 토큰을 복사하여 `gitlab.private-token`에 입력

**Webhook 등록 방법:**
1. GitLab 프로젝트 → **Settings** → **Webhooks**
2. URL: `http://your-server:8080/webhook/gitlab`
3. Secret token: `application.yml`의 `gitlab.webhook-secret`에 설정한 값과 동일하게 입력
4. Trigger: **Push events**, **Merge request events** 체크
5. **Add webhook** 클릭

### Confluence

`application.yml`에 아래 항목을 설정합니다:

| 항목 | yml 키 | 설명 |
|------|--------|------|
| Confluence URL | `confluence.url` | Confluence Server 주소 (예: `https://confluence.yourcompany.com`) |
| Username | `confluence.username` | Confluence 로그인 사용자명 |
| Password | `confluence.password` | Confluence 로그인 비밀번호 (PAT 미사용 시) |
| PAT | `confluence.pat` | Personal Access Token (Server 7.9+ 권장) |

> `pat`이 설정된 경우 PAT 인증(`Bearer`)이 우선 적용되고, 비어있으면 Basic Auth(`username/password`)를 사용합니다.

**PAT(Personal Access Token) 발급 방법 (Confluence Server 7.9+):**
1. Confluence 로그인 → 우측 상단 프로필 아이콘 → **설정(Settings)**
2. **개인용 액세스 토큰(Personal Access Tokens)** 메뉴 선택
3. **토큰 만들기(Create token)** 클릭
4. 토큰 이름 입력 → 만료일 설정 → **만들기(Create)** 클릭
5. 생성된 토큰을 복사하여 `confluence.pat`에 입력

**Basic Auth 사용 시 (PAT 미지원 환경):**
- `confluence.username`: Confluence 로그인 ID
- `confluence.password`: Confluence 로그인 비밀번호
- `confluence.pat`: 비워두거나 항목 제거

### LLM (Claude / OpenAI)

LLM 설정은 `config/llm_config.json`에 정의합니다. `active: true`인 항목이 **정확히 1개**여야 합니다.

**Claude API Key 발급:**
1. [Anthropic Console](https://console.anthropic.com/) 접속 → 로그인
2. **API Keys** 메뉴 → **Create Key**
3. 생성된 키(`sk-ant-...`)를 `llm_config.json`의 `apiKey`에 입력

**OpenAI API Key 발급:**
1. [OpenAI Platform](https://platform.openai.com/) 접속 → 로그인
2. **API Keys** 메뉴 → **Create new secret key**
3. 생성된 키(`sk-...`)를 `llm_config.json`의 `apiKey`에 입력

---

## 전체 처리 흐름

### 흐름 1 - GitLab 코드 문서화

```
GitLab Push/MR
    │
    ▼
[GitLabWebhookController] X-Gitlab-Token 검증 → 이벤트 파싱 → @Async 비동기 처리
    │
    ▼
[ConfigLoaderService] 브랜치 필터 → skipKeyword 확인 → 대상 페이지 결정
    │
    ▼
[GitLabSourceService] 변경 파일 내용 수집 (gitlab4j-api)
    │
    ▼
[LLMRouter] llm_config.json active LLM 선택
    ├─ claude → [ClaudeClientService]
    └─ openai → [OpenAIClientService]
    │
    ▼
[PromptTemplateService] 문서 유형별 프롬프트 적용 → Confluence Storage Format 생성
    │
    ▼
[ConfluenceTargetService] 페이지 Upsert (appendMode 적용)
```

### 흐름 2 - Jira 주간보고 자동 생성 _(추후 구현)_

```
매주 지정 요일/시각 (@Scheduled)
    │
    ▼
[JiraReportScheduler]
    │
    ▼
[JiraSourceService] 주간 이슈 목록 수집
    │
    ▼
[LLMRouter] → [PromptTemplateService] weekly_report 템플릿 적용
    │
    ▼
[ConfluenceTargetService] 주간보고 페이지 생성
    │
    ▼
[SlackTargetService] 생성 완료 알림 발송 (선택)
```