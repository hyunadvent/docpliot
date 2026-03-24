# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

DocPilot is a document automation agent server that collects data from sources (GitLab, Jira), analyzes it via LLM (Claude or OpenAI), and automatically generates/updates documentation on target platforms (Confluence, Slack). Written in Korean — README.md serves as the full implementation specification (note: README references Gradle but the project actually uses Maven).

## Build & Run Commands

```bash
./mvnw clean package           # Build JAR
./mvnw spring-boot:run         # Run dev server (port 8080)
./mvnw test                    # Run all tests
./mvnw test -Dtest=ClassName   # Run a single test class
./mvnw test -Dtest=ClassName#methodName  # Run a single test method
java -jar target/docpilot-0.0.1-SNAPSHOT.jar  # Run built JAR
```

## Tech Stack

- **Java 17** / **Spring Boot 3.5.x** / **Maven** (wrapper)
- **Spring Web MVC** (REST controllers) + **Spring WebFlux** (WebClient for async HTTP)
- **gitlab4j-api 6.x** for GitLab integration
- **Jackson** for JSON config parsing, **Lombok** for boilerplate reduction
- **JUnit 5** + **MockWebServer** (OkHttp) + **reactor-test** for testing

## Architecture

Three-stage pipeline: **Source → LLM → Target**

```
GitLab ──┐                              ┌──▶ Confluence
         ├──▶ Webhook/Scheduler ──▶ LLM ──┤
Jira   ──┘                              └──▶ Slack (future)
```

Base package: `com.hancom.ai.docpilot.docpilot`

### Key Processing Flows

**Webhook-driven (runtime):**
1. `GitLabWebhookController` (`POST /webhook/gitlab`) validates `X-Gitlab-Token`, resolves event type from `X-Gitlab-Event` header, extracts project/branch/commit info, checks skip keyword and branch triggers
2. Delegates to `DocumentPipelineService.processEvent()` (`@Async` — returns 200 immediately)
3. `ConfigLoaderService.getPagesForEvent()` determines which Confluence pages need updating based on event type and changed files (glob matching via `AntPathMatcher`)
4. For each target page: collects file contents from GitLab → builds prompt via `PromptTemplateService` → routes to LLM via `LLMRouter` → upserts to Confluence via `ConfluenceTargetService`

**Startup initialization (runs on `ApplicationReadyEvent`):**
1. `ProjectInitializationService` (`@Order(1)`): For pages with explicit `source_file`, fetches from GitLab default branch, converts via LLM, creates Confluence pages if they don't exist
2. `ApiSpecInitializationService` (`@Order(2)`): Auto-discovers `*Controller.java` files in GitLab, uses LLM to extract endpoint metadata as JSON (`controller_analysis` template), then generates per-endpoint documentation pages (`api_detail` template)

### Service Dependencies

```
GitLabWebhookController → ConfigLoaderService, DocumentPipelineService
DocumentPipelineService → ConfigLoaderService, GitLabSourceService, LLMRouter, PromptTemplateService, ConfluenceTargetService
LLMRouter → ConfigLoaderService, ClaudeClientService, OpenAIClientService
ProjectInitializationService  ─┐
ApiSpecInitializationService  ─┴→ ConfigLoaderService, GitLabSourceService, LLMRouter, PromptTemplateService, ConfluenceTargetService
```

## Configuration Files

- **`config/llm_config.json`** — LLM provider selection. Exactly one entry must have `active: true`; app fails to start otherwise. Supported names: `"claude"`, `"openai"`.
- **`config/page_structure_config.json`** (README calls it `confluence_structure.json`) — Defines Confluence space key, per-project GitLab-to-Confluence mappings (project ID, parent page hierarchy, page definitions with glob patterns and prompt templates), branch trigger/ignore rules, and options (`skip_keyword`, `overwrite_manual_edits`).
- **`src/main/resources/application.yml`** — GitLab URL/token/webhook-secret, Confluence URL/credentials (supports both PAT and Basic Auth), config file paths. Use `application-local.yml` for local overrides.

## Important Conventions

- All LLM prompts must instruct the model to respond in **Confluence Storage Format (XML only)** — `PromptTemplateService` enforces this in all 7 templates
- Code content exceeding 8000 chars is truncated with `...(이하 생략)` before sending to LLM
- `DocumentPipelineService.cleanLlmResponse()` strips markdown code block markers and XML declarations from LLM output before writing to Confluence
- Webhook processing must be async (`@Async`) — return 200 OK immediately
- Confluence auth: use PAT (`Bearer`) if configured, fall back to Basic Auth
- WebClient timeouts: 10s connect, 60s read (configured in `WebClientConfig`)
- Page titles support `{project_name}` placeholder replacement (via `ConfigLoaderService.renderTitle()`)
- `ConfluenceTargetService.ensureParentPages()` navigates/creates the full parent page hierarchy before upserting content
