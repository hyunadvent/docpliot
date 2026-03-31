# DocPilot

> GitLab에서 코드를 수집하고, LLM(Claude/OpenAI)이 분석하여, Confluence에 API 명세서 등 기술 문서를 자동으로 생성/업데이트하는 문서 자동화 에이전트 서버입니다.

---

## 프로젝트 배경

백엔드 개발 현장에서 API 명세서는 프론트엔드 협업, QA, 운영 인수인계 등 모든 단계에 필수적인 문서입니다. 그러나 현실적으로 명세서가 제대로 작성되어 있는 경우는 드뭅니다.

**새로운 모듈을 개발할 때**, 기능 구현에 집중하다 보면 명세서 작성은 뒤로 밀리게 됩니다. **다른 사람이 개발한 모듈을 인계받았을 때**는 상황이 더 심각합니다. 코드를 한 줄씩 읽어가며 엔드포인트를 파악하고, 요청/응답 구조를 정리하고, 이를 문서화하는 작업은 본래 업무와 무관한 부가적인 시간 소모입니다.

특히 Controller가 수십 개에 달하는 프로젝트라면, 명세서를 처음부터 작성하는 데만 며칠이 소요될 수 있습니다. 그럼에도 이 작업을 건너뛸 수는 없습니다. 명세서 없이는 협업이 어렵고, 장애 대응도 느려지기 때문입니다.

DocPilot은 이 **반복적이고 시간 소모적인 문서화 작업을 자동화**하기 위해 시작되었습니다. GitLab에 코드가 있으면 LLM이 분석하여 Confluence에 API 명세서를 자동으로 생성합니다. 개발자는 코드를 작성하는 것만으로 문서가 만들어지고, 코드가 변경되면 문서도 자동으로 갱신됩니다.

---

## 기술 스택

| 항목 | 버전/기술 |
|------|----------|
| Java | 17+ |
| Spring Boot | 3.5.x |
| Build Tool | Maven (wrapper 포함) |
| 데이터베이스 | H2 (embedded, file-based) |
| ORM | Spring Data JPA (Hibernate) |
| 보안 | Spring Security 6 + BCrypt |
| 템플릿 엔진 | Thymeleaf 3 |
| GitLab 연동 | gitlab4j-api 6.x |
| LLM HTTP 호출 | Spring WebClient (WebFlux) |
| Confluence 연동 | Spring WebClient |
| JSON 설정 파싱 | Jackson ObjectMapper |
| 코드 간소화 | Lombok |

---

## 아키텍처 개요

DocPilot은 **Source → LLM → Target** 의 3단계 파이프라인 구조입니다.

```
[ Source ]                   [ Core ]                [ Target ]

 GitLab  ──┐                                ┌──▶  Confluence
            ├──▶  Webhook / Startup  ──▶  LLM  ──┤
 (Jira)  ──┘     (비동기 처리)               └──▶  (Slack - 추후)
```

### 핵심 처리 흐름

**1. Webhook 기반 (런타임)**

```
GitLab Push/MR → Webhook 수신 → 브랜치/파일 필터링 → 코드 수집 → LLM 분석 → Confluence Upsert
```

**2. 서버 기동 시 (자동 초기화)**

```
ApplicationReadyEvent
  ├─ @Order(1) ProjectInitializationService : README 기반 서비스 개요 페이지 생성
  └─ @Order(2) ApiSpecInitializationService : Controller 파일 탐색 → LLM 분석 → API 명세서 자동 생성
```

**3. UI 수동 실행**

```
프로젝트 추가 → 초기 페이지 생성 + API 명세서 자동 생성
API 명세서 화면 → 개별 Controller 재생성 / Confluence 동기화
```

---

## 프로젝트 구조

```
docpilot/
├── pom.xml                                      # Maven 설정 (Java 17)
├── config/
│   ├── llm_config.json                          # LLM 선택 및 API Key
│   └── page_structure_config.json               # Confluence 페이지 구조 + 프로젝트 매핑
│
└── src/main/
    ├── resources/
    │   ├── application.yml                      # Spring Boot 설정
    │   ├── templates/                           # Thymeleaf 템플릿 (10개)
    │   │   ├── layout/default.html              # 공통 레이아웃
    │   │   ├── login.html, register.html        # 인증
    │   │   ├── dashboard.html                   # 대시보드
    │   │   ├── projects.html                    # 프로젝트 관리
    │   │   ├── api-specs.html                   # API 명세서 현황
    │   │   ├── llm.html                         # LLM 설정
    │   │   ├── settings.html                    # 시스템 설정
    │   │   ├── webhook-logs.html                # 웹훅 이벤트 이력
    │   │   └── admin/user-management.html       # 사용자 관리
    │   └── static/                              # CSS, JS
    │
    └── java/com/hancom/ai/docpilot/docpilot/
        ├── DocpilotApplication.java             # @SpringBootApplication, @EnableScheduling
        │
        ├── config/                              # 설정 및 보안
        │   ├── SecurityConfig.java              # Spring Security 필터 체인
        │   ├── WebClientConfig.java             # WebClient (10s 연결, 60s 읽기 타임아웃)
        │   ├── AsyncConfig.java                 # @EnableAsync
        │   ├── ConfigLoaderService.java         # JSON 설정 파일 로드/저장/관리
        │   ├── DataInitializer.java             # 기본 admin 계정 자동 생성
        │   ├── CustomUserDetailsService.java    # Spring Security UserDetailsService
        │   └── model/
        │       ├── LLMConfig.java               # llm_config.json 모델
        │       └── ConfluenceStructure.java     # page_structure_config.json 모델
        │
        ├── entity/                              # JPA 엔티티
        │   ├── UserEntity.java                  # 사용자 계정
        │   ├── WebhookEventEntity.java          # 웹훅 이벤트 이력
        │   ├── ControllerPageMappingEntity.java # Controller ↔ Confluence 페이지 매핑
        │   └── ApiPageMappingEntity.java        # API 엔드포인트 ↔ Confluence 페이지 매핑
        │
        ├── repository/                          # Spring Data JPA Repository
        │   ├── UserRepository.java
        │   ├── WebhookEventRepository.java
        │   ├── ControllerPageMappingRepository.java
        │   └── ApiPageMappingRepository.java
        │
        ├── llm/                                 # LLM 연동
        │   ├── LLMRouter.java                   # Claude/OpenAI 라우팅
        │   ├── ClaudeClientService.java         # Anthropic API 클라이언트
        │   ├── OpenAIClientService.java         # OpenAI API 클라이언트
        │   └── PromptTemplateService.java       # 문서 유형별 프롬프트 템플릿 (11종)
        │
        ├── source/gitlab/
        │   └── GitLabSourceService.java         # gitlab4j-api 래퍼
        │
        ├── target/confluence/
        │   └── ConfluenceTargetService.java     # Confluence REST API 클라이언트
        │
        ├── webhook/                             # 웹훅 및 파이프라인
        │   ├── GitLabWebhookController.java     # POST /webhook/gitlab
        │   ├── DocumentPipelineService.java     # @Async 문서 생성 파이프라인
        │   ├── ProjectInitializationService.java # 서버 기동 시 프로젝트 초기 페이지 생성
        │   ├── ApiSpecInitializationService.java # Spring Boot API 명세서 자동 생성
        │   ├── DjangoApiSpecService.java        # Django/DRF API 명세서 생성
        │   └── ExpressApiSpecService.java       # Express.js API 명세서 (스텁)
        │
        └── web/                                 # 웹 컨트롤러 및 서비스
            ├── DashboardController.java         # GET / (대시보드)
            ├── AuthController.java              # 로그인/회원가입
            ├── UserManagementController.java    # 사용자 승인/삭제 (관리자)
            ├── PageController.java              # 각 페이지 렌더링
            ├── LlmApiController.java            # REST /api/llm/*
            ├── ProjectApiController.java        # REST /api/projects/*
            ├── ApiSpecApiController.java        # REST /api/api-specs/*
            ├── DashboardService.java            # 대시보드 비즈니스 로직
            ├── ApiSpecService.java              # API 명세서 현황 조회
            ├── WebhookEventStore.java           # 웹훅 이벤트 저장
            ├── ProcessingStatusTracker.java     # 동시 처리 제한 (최대 2개)
            └── dto/                             # DTO 클래스
```

---

## 실행 방법

### 빌드 및 실행

```bash
# 빌드
./mvnw clean package

# 개발 서버 실행 (포트 8001)
./mvnw spring-boot:run

# 또는 JAR 직접 실행
java -jar target/docpilot-0.0.1-SNAPSHOT.jar

# 테스트
./mvnw test
./mvnw test -Dtest=ClassName              # 특정 클래스
./mvnw test -Dtest=ClassName#methodName   # 특정 메서드
```

### 초기 설정

1. `src/main/resources/application.yml`에 GitLab, Confluence 접속 정보 입력
2. `config/llm_config.json`에 LLM API Key 설정 (`active: true`인 항목이 정확히 1개)
3. `config/page_structure_config.json`에 프로젝트 매핑 설정
4. 서버 실행 후 `http://localhost:8001` 접속
5. 기본 관리자 계정: `admin` / `admin`

---

## 설정 파일

### `application.yml`

```yaml
server:
  port: 8001

# GitLab
gitlab:
  url: https://your-gitlab.com
  private-token: glpat-xxxxxxxxxxxx       # GitLab Personal Access Token
  webhook-secret: your-webhook-secret      # Webhook 검증용 시크릿

# Confluence
confluence:
  url: https://your-confluence.com/wiki    # Confluence Cloud/Server URL
  username: user@example.com               # Basic Auth 사용자명
  password: your-password                  # Basic Auth 비밀번호 (또는 API 토큰)
  pat:                                     # PAT 설정 시 Bearer 인증 우선 적용

# 설정 파일 경로
config:
  llm-config-path: config/llm_config.json
  structure-config-path: config/page_structure_config.json

# API 명세서 생성 옵션
api-spec:
  max-controllers: 1                       # 프로젝트 추가 시 자동 생성할 Controller 수 (0=비활성, -1=전체)
  template-page-title: API 명세서 예시     # Confluence 서식 템플릿 페이지 제목 (빈값=기본 프롬프트)

# H2 데이터베이스
spring:
  datasource:
    url: jdbc:h2:file:./data/docpilot
  h2:
    console:
      enabled: true                        # http://localhost:8001/h2-console (ID: sa, 비밀번호 없음)
```

### `config/llm_config.json`

사용할 LLM을 설정합니다. `active: true`인 항목이 **정확히 1개**여야 합니다.

```json
{
  "llms": [
    {
      "name": "claude",
      "api_key": "sk-ant-xxxxxxxxxxxx",
      "model": "claude-sonnet-4-20250514",
      "active": false
    },
    {
      "name": "openai",
      "api_key": "sk-proj-xxxxxxxxxxxx",
      "model": "gpt-4o",
      "active": true
    }
  ]
}
```

### `config/page_structure_config.json`

Confluence 페이지 구조와 GitLab 프로젝트 매핑을 정의합니다.

```json
{
  "space_key": "DEV",
  "projects": [
    {
      "gitlab_project_id": 123,
      "gitlab_path": "group/my-project",
      "platform": "springboot",
      "confluence_parent_pages": ["프로젝트", "마이프로젝트", "[마이프로젝트 백엔드]"],
      "page_title_prefix": "[마이프로젝트 백엔드]",
      "dependency_projects": [456],
      "pages": [
        {
          "id": "service_overview",
          "title": "서비스 개요 및 구성도",
          "auto_generate": true,
          "prompt_template": "service_overview",
          "source_file": "README.md",
          "trigger": ["push", "merge_request"],
          "target_files": ["README.md"]
        },
        {
          "id": "api_spec",
          "title": "API 명세서",
          "auto_generate": true,
          "prompt_template": "api_spec",
          "trigger": ["push", "merge_request"],
          "target_files": ["**/controller/**", "**/routes/**"]
        }
      ]
    }
  ],
  "common_libraries": [
    {
      "gitlab_project_id": 270,
      "gitlab_path": "group/common-lib",
      "description": "공통 DTO 라이브러리"
    }
  ],
  "branches": {
    "trigger_branches": ["main", "develop"],
    "ignore_branches": ["feature/*", "hotfix/*"]
  },
  "options": {
    "skip_keyword": "[skip-doc]",
    "overwrite_manual_edits": false
  }
}
```

| 항목 | 설명 |
|------|------|
| `platform` | 프로젝트 플랫폼: `springboot`(기본), `django`, `express` |
| `confluence_parent_pages` | Confluence 페이지 계층 경로 (자동 생성) |
| `page_title_prefix` | 생성 페이지 제목 접두사 |
| `dependency_projects` | DTO 검색 시 참조할 의존 프로젝트 ID |
| `common_libraries` | 공통 라이브러리 프로젝트 (DTO/Model 검색 대상) |
| `trigger` | 반응할 이벤트 유형 (`push`, `merge_request`) |
| `target_files` | glob 패턴으로 변경 감지 대상 파일 지정 |
| `append_mode` | `true`이면 덮어쓰지 않고 내용 누적 추가 |
| `skip_keyword` | 커밋 메시지에 포함 시 문서 생성 건너뜀 |

---

## 웹 UI

| 페이지 | 경로 | 설명 |
|--------|------|------|
| 대시보드 | `/` | 프로젝트 현황, 연결 상태, Controller 처리 통계 |
| 프로젝트 관리 | `/projects` | 프로젝트 CRUD, 공통 라이브러리 관리, 플랫폼 선택 |
| API 명세서 현황 | `/api-specs` | Controller별 처리 상태, 개별 재생성, Confluence 동기화 |
| LLM 설정 | `/llm` | LLM 제공자 관리, API Key 설정, 활성 LLM 전환 (관리자) |
| 시스템 설정 | `/settings` | 시스템 설정 관리 (관리자) |
| 웹훅 이벤트 | `/webhook-logs` | GitLab 웹훅 수신 이력 조회 |
| 사용자 관리 | `/admin/users` | 사용자 승인/삭제 (관리자) |

---

## REST API

### 인증

- **로그인**: Spring Security 폼 기반 로그인 (`POST /login`)
- **역할**: `ROLE_ADMIN` (관리자), `ROLE_USER` (일반 사용자)
- **신규 사용자**: 가입 후 관리자 승인 필요

### 웹훅

| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/webhook/gitlab` | GitLab Push/MR 웹훅 수신 (토큰 검증) |

### 프로젝트 관리 (`/api/projects`)

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/api/projects` | 프로젝트 목록 조회 |
| GET | `/api/projects/{id}` | 프로젝트 상세 조회 |
| POST | `/api/projects` | 프로젝트 추가 (초기 페이지 자동 생성) |
| PUT | `/api/projects/{id}` | 프로젝트 수정 |
| DELETE | `/api/projects/{id}` | 프로젝트 삭제 |
| DELETE | `/api/projects/by-index/{index}` | 인덱스 기반 삭제 (비정상 데이터 정리용) |

### 공통 라이브러리 관리 (`/api/projects/common-libraries`)

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/api/projects/common-libraries` | 라이브러리 목록 |
| POST | `/api/projects/common-libraries` | 라이브러리 추가 |
| PUT | `/api/projects/common-libraries/{id}` | 라이브러리 수정 |
| DELETE | `/api/projects/common-libraries/{id}` | 라이브러리 삭제 |

### API 명세서 (`/api/api-specs`)

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/api/api-specs/processing` | 현재 처리 중인 Controller 목록 |
| POST | `/api/api-specs/sync` | DB ↔ Confluence 수동 동기화 |
| POST | `/api/api-specs/regenerate` | 특정 Controller API 명세서 재생성 |

### LLM 설정 (`/api/llm`) - 관리자 전용

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/api/llm` | LLM 설정 목록 (API Key 마스킹) |
| POST | `/api/llm` | LLM 추가 |
| PUT | `/api/llm/{name}` | LLM 수정 |
| PUT | `/api/llm/{name}/activate` | LLM 활성화 |
| DELETE | `/api/llm/{name}` | LLM 삭제 |

---

## API 명세서 자동 생성

DocPilot의 핵심 기능으로, GitLab 프로젝트의 Controller/View 파일을 분석하여 Confluence에 API 명세서를 자동 생성합니다.

### 지원 플랫폼

| 플랫폼 | 상태 | 파일 탐색 패턴 | 분석 대상 |
|--------|------|---------------|----------|
| **Spring Boot** (Java) | 완전 지원 | `*Controller.java` in `/controller/` | `@GetMapping`, `@PostMapping` 등 어노테이션 |
| **Django** (Python) | 지원 | `views.py`, `viewsets.py`, `/views/` | `@api_view`, `APIView`, `ViewSet`, `@action` |
| **Express** (Node.js) | 스텁 (미구현) | `.js`/`.ts` in `/routes/`, `/controllers/` | - |

### 생성 파이프라인

```
1. Controller/View 파일 탐색 (GitLab)
      ↓
2. API 시그니처 추출 (본문 제거, 어노테이션/데코레이터 유지)
      ↓
3. LLM 분석 (controller_analysis) → JSON
   {controllerKoreanName, apis: [{method, path, methodName, koreanName}]}
      ↓
4. Controller 그룹 페이지 생성 (API 목록 테이블)
      ↓
5. 각 API별:
   ├─ 메서드 코드 추출
   ├─ DTO/Serializer 소스 수집 (import 기반 다단계 검색)
   ├─ Map/dict 파라미터 key 분석
   ├─ LLM 분석 (api_detail) → Confluence Storage Format XML
   └─ API 상세 페이지 생성
```

### Confluence 페이지 구조

```
[프로젝트] API 명세서
├── [프로젝트] 인증
│   ├── [프로젝트] API - 로그인
│   ├── [프로젝트] API - 로그아웃
│   └── [프로젝트] API - 토큰갱신
├── [프로젝트] 사용자
│   ├── [프로젝트] API - 회원가입
│   ├── [프로젝트] API - 프로필조회
│   └── [프로젝트] API - 프로필수정
└── ...
```

### DTO/Serializer 검색 전략

**Spring Boot (Java):**
1. import 문에서 FQCN 추출 → `src/main/java/패키지/클래스.java` 직접 검색
2. 같은 패키지 / 와일드카드 import 패키지 검색
3. 파일명 폴백 검색
4. 의존 프로젝트 + 공통 라이브러리까지 확장 검색
5. 중첩 DTO 재귀 수집

**Django (Python):**
1. `from .serializers import` 등 import 문 파싱 (상대/절대)
2. 뷰 파일 경로 기반 앱 디렉토리 유추 → `serializers.py`, `models.py` 검색
3. 파일명 폴백 검색
4. `class XxxSerializer` 블록만 추출
5. 중첩 Serializer + Meta.model 재귀 수집

---

## 웹훅 처리 흐름

```
GitLab Push/MR
    │
    ▼
[GitLabWebhookController]
  ├─ X-Gitlab-Token 헤더 검증
  ├─ X-Gitlab-Event 헤더로 이벤트 유형 결정
  ├─ projectId, branch, 변경 파일 추출
  ├─ 커밋 메시지에 skip_keyword 포함 여부 확인
  └─ 브랜치 트리거/무시 패턴 확인 (AntPathMatcher)
    │
    ▼  (즉시 200 OK 반환, 이하 @Async 비동기 처리)
[DocumentPipelineService.processEvent()]
  ├─ 변경 파일 기반으로 대상 페이지 결정 (glob 매칭)
  ├─ 각 페이지별: 코드 수집 → LLM 분석 → Confluence Upsert
  └─ Controller 파일 변경 감지 시:
     └─ 플랫폼별 분기 → API 명세서 재생성
        ├─ springboot → ApiSpecInitializationService
        ├─ django    → DjangoApiSpecService
        └─ express   → ExpressApiSpecService (스텁)
```

---

## LLM 연동

### 지원 제공자

| 제공자 | API 엔드포인트 | 인증 헤더 |
|--------|---------------|----------|
| Claude | `https://api.anthropic.com/v1/messages` | `x-api-key: {key}` |
| OpenAI | `https://api.openai.com/v1/chat/completions` | `Authorization: Bearer {key}` |

- `max_tokens: 4096`
- LLM 설정은 UI에서 런타임 전환 가능
- API Key는 UI 응답에서 마스킹 처리 (앞 4자 + *** + 뒤 4자)

### 프롬프트 템플릿 (11종)

| 템플릿 | 용도 |
|--------|------|
| `service_overview` | README.md → Confluence 변환 |
| `api_spec` | 코드 → API 명세서 요약 |
| `controller_analysis` | Java Controller → JSON 엔드포인트 메타데이터 |
| `api_detail` | 단일 API → Confluence XML 상세 명세 |
| `api_detail_with_template` | 서식 템플릿 기반 API 명세 (XML 구조 복제) |
| `django_controller_analysis` | Django View → JSON 엔드포인트 메타데이터 |
| `django_api_detail` | Django API → Confluence XML 상세 명세 |
| `django_api_detail_with_template` | 서식 템플릿 기반 Django API 명세 |
| `db_schema` | Entity/Model → DB 스키마 문서 |
| `changelog` | 변경 이력 문서 |
| `weekly_report` | Jira 이슈 기반 주간보고서 (추후) |

- 모든 프롬프트는 **Confluence Storage Format(XML)** 출력을 강제
- 코드 16,000자 초과 시 `...(이하 생략)`으로 잘라냄
- 모든 문서는 **한국어**로 작성

---

## 데이터베이스

H2 임베디드 데이터베이스 사용 (파일 기반: `./data/docpilot`)

### 테이블

| 테이블 | 용도 | 주요 컬럼 |
|--------|------|----------|
| `users` | 사용자 계정 | username, password(BCrypt), role, approved |
| `webhook_events` | 웹훅 이벤트 이력 | eventType, projectName, branch, status |
| `controller_page_mappings` | Controller ↔ Confluence 매핑 | gitlabProjectId, controllerPath, confluencePageId, processed, apiCount |
| `api_page_mappings` | API ↔ Confluence 매핑 | methodName, httpMethod, path, confluencePageId |

- H2 콘솔: `http://localhost:8001/h2-console` (ID: `sa`, 비밀번호 없음)

---

## 보안

### 접근 권한

| 경로 | 권한 |
|------|------|
| `/login`, `/register`, `/css/**`, `/js/**` | 공개 |
| `/webhook/**` | 공개 (토큰 검증) |
| `/admin/**`, `/llm`, `/settings`, `/api/llm/**` | ADMIN 전용 |
| 나머지 | 인증 필요 |

### 인증 방식

- Spring Security 폼 기반 로그인
- 비밀번호: BCrypt 해시
- 신규 사용자: 가입 후 관리자 승인 필요 (`approved: false` → `true`)
- 기본 관리자: `admin` / `admin` (DataInitializer에서 자동 생성)

---

## 외부 서비스 연동

### GitLab

**Private Token 발급:**
1. GitLab → 프로필 → Edit profile → Access Tokens
2. Scopes: `api` 체크 → Create personal access token
3. `application.yml`의 `gitlab.private-token`에 입력

**Webhook 등록:**
1. GitLab 프로젝트 → Settings → Webhooks
2. URL: `http://서버주소:8001/webhook/gitlab`
3. Secret token: `application.yml`의 `gitlab.webhook-secret`과 동일
4. Trigger: Push events, Merge request events 체크

### Confluence

**인증 방식:**
- `confluence.pat` 설정 시: Bearer 토큰 인증 (우선)
- `confluence.pat` 미설정 시: Basic Auth (`username:password`)

### LLM (Claude / OpenAI)

- **Claude**: [Anthropic Console](https://console.anthropic.com/) → API Keys → Create Key
- **OpenAI**: [OpenAI Platform](https://platform.openai.com/) → API Keys → Create new secret key
- `config/llm_config.json`에 입력하거나 UI(LLM 설정 페이지)에서 관리

---

## 주요 규칙

- LLM 응답은 반드시 **Confluence Storage Format (XML)** — `PromptTemplateService`에서 모든 템플릿에 강제
- 코드 16,000자 초과 시 `...(이하 생략)` 첨부 후 LLM에 전달
- `DocumentPipelineService.cleanLlmResponse()`가 마크다운 코드블록 마커, XML 선언을 제거
- 웹훅 처리는 반드시 `@Async` — 즉시 200 OK 반환
- Confluence 인증: PAT(`Bearer`) 설정 시 우선, 미설정 시 Basic Auth
- WebClient 타임아웃: 연결 10초, 읽기 60초 (`WebClientConfig`)
- 페이지 제목의 `{project_name}`은 런타임에 실제 프로젝트명으로 치환
- `ConfluenceTargetService.ensureParentPages()`가 부모 페이지 계층을 자동 생성
- 동일 제목 페이지 존재 시 `createPage()`에서 자동으로 기존 페이지 업데이트
- 동시 API 명세서 재생성은 최대 2개까지 (`ProcessingStatusTracker`)
